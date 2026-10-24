package net.betterthanvillage.society;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.debug.TestBot;
import net.betterthanvillage.economy.Credit;
import net.betterthanvillage.economy.Debt;
import net.betterthanvillage.law.Law;
import net.betterthanvillage.law.Wars;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Debug commands for headless law tests: /btv bot drives a {@link TestBot} (hold, place, break, attack, take, trade)
 * and /btv law shows the law state near the source or stages an overdue debt.
 */
final class BotCommands {
    private BotCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> bot() {
        return Commands.literal("bot").requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn").then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> spawn(ctx, BlockPos.containing(ctx.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> spawn(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                    TestBot.remove(StringArgumentType.getString(ctx, "name"));
                    return 1;
                })))
                .then(Commands.literal("tp").then(name().then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                    TestBot bot = bot(ctx);
                    BlockPos p = surface(ctx.getSource().getLevel(), BlockPosArgument.getBlockPos(ctx, "pos"));
                    bot.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                    return ok(ctx, "Bot at " + p.toShortString());
                }))))
                .then(Commands.literal("hold").then(name().then(Commands.argument("item", ResourceLocationArgument.id())
                        .executes(ctx -> hold(ctx, InteractionHand.MAIN_HAND)))))
                .then(Commands.literal("offhand").then(name().then(Commands.argument("item", ResourceLocationArgument.id())
                        .executes(ctx -> hold(ctx, InteractionHand.OFF_HAND)))))
                .then(Commands.literal("give").then(name().then(Commands.argument("item", ResourceLocationArgument.id())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                            TestBot bot = bot(ctx);
                            ItemStack stack = new ItemStack(item(ctx), IntegerArgumentType.getInteger(ctx, "count"));
                            bot.getInventory().add(stack);
                            return ok(ctx, "Given");
                        })))))
                .then(Commands.literal("place").then(name().then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("item", ResourceLocationArgument.id()).executes(BotCommands::place)))))
                .then(Commands.literal("break").then(name().then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                    TestBot bot = bot(ctx);
                    boolean done = bot.gameMode.destroyBlock(BlockPosArgument.getBlockPos(ctx, "pos"));
                    return ok(ctx, "Break " + (done ? "done" : "failed"));
                }))))
                .then(Commands.literal("attack").then(name().then(Commands.argument("target", EntityArgument.entity()).executes(ctx -> {
                    TestBot bot = bot(ctx);
                    Entity target = EntityArgument.getEntity(ctx, "target");
                    bot.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                    bot.attack(target);
                    return ok(ctx, "Attacked " + target.getType().toShortString());
                }))))
                .then(Commands.literal("take").then(name().then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1728))
                                .executes(ctx -> take(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))))
                .then(Commands.literal("steal").then(name().then(Commands.argument("count", IntegerArgumentType.integer(1, 1728))
                        .executes(ctx -> {
                            TestBot bot = bot(ctx);
                            BlockPos storage = nearestStorage(ctx.getSource().getLevel(), bot);
                            if (storage == null) return ok(ctx, "No colony storage");
                            // The thief stands at the chest, where villagers can see it.
                            BlockPos at = surface(ctx.getSource().getLevel(), storage.south(2));
                            bot.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                            return take(ctx, storage);
                        }))))
                .then(Commands.literal("trade").then(name().then(Commands.argument("villager", EntityArgument.entity())
                        .executes(ctx -> trade(ctx, villager(ctx), -1))
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 63))
                                .executes(ctx -> trade(ctx, villager(ctx), IntegerArgumentType.getInteger(ctx, "index")))))))
                .then(Commands.literal("deed").then(name().then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                    // Debug: the bot uses the land deed it carries on the ground at the position (marks its plot).
                    TestBot bot = bot(ctx);
                    ServerLevel level = ctx.getSource().getLevel();
                    BlockPos asked = BlockPosArgument.getBlockPos(ctx, "pos");
                    BlockPos pos = surface(level, asked).below();
                    // Where the deed is refused, the nearest valid plot centre within 48 blocks is taken.
                    Colony at = ColonyManager.colonyAt(level, asked);
                    if (at != null && !Deeds.plotValid(level, at, bot.getUUID(), pos)) {
                        BlockPos found = null;
                        for (int r = 4; r <= 48 && found == null; r += 4) {
                            for (int dx = -r; dx <= r && found == null; dx += 4) {
                                for (int dz = -r; dz <= r && found == null; dz += 4) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                                    BlockPos c = surface(level, asked.offset(dx, 0, dz)).below();
                                    if (Deeds.plotValid(level, at, bot.getUUID(), c)) found = c;
                                }
                            }
                        }
                        if (found != null) pos = found;
                    }
                    var inv = bot.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        if (!Deeds.isLandDeed(inv.getItem(i))) continue;
                        ItemStack held = bot.getMainHandItem();
                        bot.setItemInHand(InteractionHand.MAIN_HAND, inv.getItem(i).copy());
                        inv.setItem(i, ItemStack.EMPTY);
                        Deeds.onUseOnBlock(level, bot, InteractionHand.MAIN_HAND, pos);
                        ItemStack after = bot.getMainHandItem();
                        bot.setItemInHand(InteractionHand.MAIN_HAND, held);
                        inv.add(after);
                        boolean plot = ColonyManager.data(level).landAt(pos) != null && bot.getUUID().equals(ColonyManager.data(level).landAt(pos).owner());
                        return ok(ctx, plot ? "Plot at " + pos.toShortString() : "Deed refused at " + pos.toShortString());
                    }
                    // Without a deed nothing is bought: the valid plot centre found (if any) is reported.
                    return ok(ctx, "No land deed; " + (at != null && Deeds.plotValid(level, at, bot.getUUID(), pos)
                            ? "a valid plot at " + pos.toShortString() : "no valid plot near " + asked.toShortString()));
                }))))
                .then(Commands.literal("deal").then(name().then(Commands.argument("kind", StringArgumentType.word())
                        .executes(ctx -> deal(ctx, StringArgumentType.getString(ctx, "kind"))))))
                .then(Commands.literal("elder").then(name()
                        .executes(ctx -> trade(ctx, elder(ctx), -1))
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 63))
                                .executes(ctx -> trade(ctx, elder(ctx), IntegerArgumentType.getInteger(ctx, "index"))))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> law() {
        return Commands.literal("law").requires(s -> s.hasPermission(2))
                .executes(BotCommands::lawStatus)
                .then(Commands.literal("tag-elder").executes(ctx -> {
                    // Debug: tags the elder "btv_elder" so test selectors can leave it out.
                    Colony c = ColonyCommands.colonyNear(ctx.getSource());
                    if (c == null || c.elder == null || !(ctx.getSource().getLevel().getEntity(c.elder) instanceof Villager v)) return 0;
                    v.addTag("btv_elder");
                    return ok(ctx, "Elder at " + v.blockPosition().toShortString());
                }))
                .then(Commands.literal("debt").then(name().then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes(BotCommands::overdueDebt))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name() {
        return Commands.argument("name", StringArgumentType.word());
    }

    private static TestBot bot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TestBot bot = TestBot.get(StringArgumentType.getString(ctx, "name"));
        if (bot == null) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("No such bot")).create();
        return bot;
    }

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return BuiltInRegistries.ITEM.get(ResourceLocationArgument.getId(ctx, "item"));
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static BlockPos surface(ServerLevel level, BlockPos pos) {
        return new BlockPos(pos.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()), pos.getZ());
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = surface(level, pos);
        TestBot.spawn(level, StringArgumentType.getString(ctx, "name"), at);
        return ok(ctx, "Bot spawned at " + at.toShortString());
    }

    private static int hold(CommandContext<CommandSourceStack> ctx, InteractionHand hand) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        bot.setItemInHand(hand, new ItemStack(item(ctx)));
        return ok(ctx, "Holding " + bot.getItemInHand(hand).getHoverName().getString());
    }

    /** Places a block on top of the block below pos, as a right click with the item would. */
    private static int place(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        ItemStack held = bot.getMainHandItem();
        ItemStack stack = new ItemStack(item(ctx));
        bot.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0), Direction.UP, pos.below(), false);
        var result = bot.gameMode.useItemOn(bot, level, stack, InteractionHand.MAIN_HAND, hit);
        bot.setItemInHand(InteractionHand.MAIN_HAND, held);
        return ok(ctx, "Place " + result);
    }

    /** Takes items from a colony container as the bot, then reports the theft as the chest menu would. */
    private static int take(CommandContext<CommandSourceStack> ctx, BlockPos pos) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        Colony colony = Theft.storageOwner(level, pos);
        if (colony == null || !(level.getBlockEntity(pos) instanceof Container c)) {
            ctx.getSource().sendFailure(Component.literal("Not a colony storage"));
            return 0;
        }
        int wanted = IntegerArgumentType.getInteger(ctx, "count");
        int taken = 0;
        boolean valuable = false;
        StringBuilder takenNames = new StringBuilder();
        // Common goods first (a petty theft is warned), valuables only when nothing else is left.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < c.getContainerSize() && taken < wanted; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || (pass == 0 && Theft.valuable(s))) continue;
                ItemStack part = c.removeItem(i, Math.min(wanted - taken, s.getCount()));
                valuable |= Theft.valuable(part);
                takenNames.append(' ').append(part.getCount()).append(' ').append(part.getItem());
                taken += part.getCount();
                if (!bot.getInventory().add(part)) bot.drop(part, false);
            }
        }
        c.setChanged();
        Theft.taken(level, colony, bot, taken, valuable);
        return ok(ctx, "Took " + taken + " items from " + pos.toShortString() + ":" + takenNames);
    }

    /** Lists the villager's offers to the bot, or makes one trade as the trade screen would. */
    private static Villager villager(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return EntityArgument.getEntity(ctx, "villager") instanceof Villager v ? v : null;
    }

    /** The elder of the colony the bot stands in (or the nearest one). */
    private static Villager elder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        Colony c = colonyOf(level, bot);
        return c != null && c.elder != null && level.getEntity(c.elder) instanceof Villager v ? v : null;
    }

    private static Colony colonyOf(ServerLevel level, TestBot bot) {
        Colony c = ColonyManager.colonyAt(level, bot.blockPosition());
        if (c != null) return c;
        double best = Double.MAX_VALUE;
        for (Colony other : ColonyManager.data(level).colonies()) {
            double d = other.core().distSqr(bot.blockPosition());
            if (d < best) {
                best = d;
                c = other;
            }
        }
        return c;
    }

    /** The colony storage (with something in it) nearest to the bot. */
    private static BlockPos nearestStorage(ServerLevel level, TestBot bot) {
        Colony c = colonyOf(level, bot);
        if (c == null) return null;
        java.util.Set<BlockPos> all = new java.util.LinkedHashSet<>(c.storages);
        all.addAll(c.managedStorages.keySet());
        // The nearest one holding common goods (a petty theft), else the nearest at all.
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : all) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof Container box) || box.isEmpty() || Theft.storageOwner(level, p) == null) continue;
            boolean common = false;
            for (int i = 0; i < box.getContainerSize() && !common; i++) {
                ItemStack s = box.getItem(i);
                common = !s.isEmpty() && !Theft.valuable(s);
            }
            double d = p.distSqr(bot.blockPosition()) + (common ? 0 : 1e9);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static int trade(CommandContext<CommandSourceStack> ctx, Villager v, int index) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No villager"));
            return 0;
        }
        v.setTradingPlayer(null);
        Trading.interact(level, v, bot, InteractionHand.MAIN_HAND);
        try {
            if (v.getTradingPlayer() != bot) return ok(ctx, "Refused");
            var offers = v.getOffers();
            if (index < 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < offers.size(); i++) {
                    MerchantOffer o = offers.get(i);
                    Trading.Desc d = Trading.desc(v, o);
                    sb.append(i).append(": ").append(o.getCostA().getCount()).append(' ').append(o.getCostA().getItem())
                            .append(" -> ").append(o.getResult().getCount()).append(' ').append(o.getResult().getItem())
                            .append(d == null ? "" : " [" + d.kind() + "]").append(o.isOutOfStock() ? " (out of stock)" : "").append("; ");
                }
                return ok(ctx, "Offers: " + sb);
            }
            if (index >= offers.size()) return ok(ctx, "No offer " + index);
            MerchantOffer offer = offers.get(index);
            if (offer.isOutOfStock() || !Trading.validate(level, v, offer)) return ok(ctx, "Trade " + index + " not available");
            if (!pay(bot, offer.getItemCostA()) || (offer.getItemCostB().isPresent() && !pay(bot, offer.getItemCostB().get()))) {
                return ok(ctx, "Trade " + index + ": cannot pay");
            }
            ItemStack result = offer.assemble();
            offer.increaseUses();
            Trading.afterUsing(level, v, offer);
            if (!bot.getInventory().add(result)) bot.drop(result, false);
            return ok(ctx, "Traded " + index + ": got " + result.getCount() + " " + result.getItem());
        } finally {
            v.setTradingPlayer(null);
        }
    }

    /**
     * Debug: the bot takes the first offer of a kind (sell_on_credit, repay, pay_with_note, collect, ...) that any
     * adult of the village near it makes, as a player would on the trade screen.
     */
    private static int deal(CommandContext<CommandSourceStack> ctx, String kindName) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        Trading.Kind kind;
        try {
            kind = Trading.Kind.valueOf(kindName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown kind " + kindName));
            return 0;
        }
        Colony c = ColonyManager.colonyAt(level, bot.blockPosition());
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("No village here"));
            return 0;
        }
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            v.setTradingPlayer(null);
            Trading.interact(level, v, bot, InteractionHand.MAIN_HAND);
            try {
                if (v.getTradingPlayer() != bot) continue;
                var offers = v.getOffers();
                for (int i = 0; i < offers.size(); i++) {
                    MerchantOffer o = offers.get(i);
                    Trading.Desc d = Trading.desc(v, o);
                    if (d == null || d.kind() != kind || o.isOutOfStock() || !Trading.validate(level, v, o)) continue;
                    if (!pay(bot, o.getItemCostA()) || (o.getItemCostB().isPresent() && !pay(bot, o.getItemCostB().get()))) continue;
                    ItemStack result = o.assemble();
                    String got = result.getCount() + " " + result.getItem();
                    o.increaseUses();
                    Trading.afterUsing(level, v, o);
                    if (!bot.getInventory().add(result)) bot.drop(result, false);
                    return ok(ctx, "Deal " + kind + " with " + c.roleOf(v.getUUID()).serializedName() + " " + v.getUUID().toString().substring(0, 8)
                            + ": paid " + o.getCostA().getCount() + " " + o.getCostA().getItem() + ", got " + got);
                }
            } finally {
                v.setTradingPlayer(null);
            }
        }
        return ok(ctx, "No " + kind + " offer");
    }

    /** Removes the cost from the bot's inventory if it has all of it. */
    private static boolean pay(TestBot bot, ItemCost cost) {
        var inv = bot.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (cost.test(inv.getItem(i))) have += inv.getItem(i).getCount();
        }
        if (have < cost.count()) return false;
        int left = cost.count();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!cost.test(s)) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
        return true;
    }

    private static int lawStatus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Colony c = ColonyCommands.colonyNear(ctx.getSource());
        if (c == null) return 0;
        StringBuilder gossip = new StringBuilder();
        for (TestBot bot : TestBot.all()) {
            gossip.append(' ').append(bot.getGameProfile().getName()).append('=').append(Law.gossip(level, c, bot.getUUID()))
                    .append(Law.warningActive(level, c, bot.getUUID()) ? " (warned)" : "");
        }
        return ok(ctx, "Law at " + c.core().toShortString() + " country " + c.country.toString().substring(0, 8) + ": "
                + Wars.lawStatus(level, c) + "; gossip:" + gossip);
    }

    /** Debug: an overdue debt of the bot to the nearest treasury, then the morning default steps run twice (warning, war). */
    private static int overdueDebt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TestBot bot = bot(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        Colony c = ColonyCommands.colonyNear(ctx.getSource());
        if (c == null) return 0;
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        long today = Credit.day(level);
        Debt d = new Debt(UUID.randomUUID(), Debt.Party.treasury(c.id()), Debt.Party.player(bot.getUUID()));
        d.principal = amount;
        d.balance = amount;
        d.createdDay = today - 5;
        d.dueDay = today - 2;
        d.lastAccrualDay = today;
        d.debtorName = bot.getGameProfile().getName();
        d.creditorName = "treasury";
        ColonyManager.data(level).debts.put(d.id, d);
        Map<UUID, Villager> members = new HashMap<>();
        for (Villager v : ColonyManager.members(level, c)) members.put(v.getUUID(), v);
        Credit.defaults(level, c, members);
        Credit.defaults(level, c, members);
        return ok(ctx, "Debt " + amount + " overdue, strikes " + d.strikes + "; " + Wars.lawStatus(level, c));
    }
}
