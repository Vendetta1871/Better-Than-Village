package net.betterthanvillage.politics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Values;
import net.betterthanvillage.society.ColonyCommands;
import net.betterthanvillage.world.AbstractEconomy;
import net.betterthanvillage.world.Caravans;
import net.betterthanvillage.world.Ledgers;
import net.betterthanvillage.world.SimWorkers;
import net.betterthanvillage.world.WorldScheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.Locale;

/**
 * Debug commands for countries and the abstract world (docs_v2/08-simulation.md §Debugging): /btv country inspects a
 * country and stages politics, /btv world inspects ledgers and caravans and forces scheduler steps.
 */
public final class PoliticsCommands {
    private PoliticsCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> country() {
        return Commands.literal("country").requires(s -> s.hasPermission(2))
                .executes(PoliticsCommands::countryStatus)
                .then(Commands.literal("list").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    java.util.Set<java.util.UUID> seen = new java.util.LinkedHashSet<>();
                    for (Colony c : ColonyManager.data(level).colonies()) {
                        if (c.country != null && seen.add(c.country)) {
                            for (String line : Politics.status(level, c)) ok(ctx, line);
                        }
                    }
                    return 1;
                }))
                .then(Commands.literal("join").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("loyalty", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            Colony c = here(ctx);
                            Colony other = ColonyManager.colonyAt(level, BlockPosArgument.getBlockPos(ctx, "pos"));
                            if (c == null || other == null || other == c) return fail(ctx, "No village there");
                            Countries.join(level, c, other.country, IntegerArgumentType.getInteger(ctx, "loyalty"));
                            Countries.prune(level);
                            return ok(ctx, Countries.villageName(c) + " joins " + Countries.plainName(level, other.country));
                        }))))
                .then(Commands.literal("secede").executes(ctx -> {
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    Countries.secede(ctx.getSource().getLevel(), c);
                    return ok(ctx, Countries.villageName(c) + " is independent");
                }))
                .then(Commands.literal("loyalty").then(Commands.argument("value", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    c.loyalty = IntegerArgumentType.getInteger(ctx, "value");
                    ColonyManager.markDirty(ctx.getSource().getLevel());
                    return ok(ctx, "Loyalty of " + Countries.villageName(c) + " is " + Math.round(c.loyalty));
                })))
                .then(Commands.literal("order").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Order.Kind.values()).map(Order.Kind::key), b))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 4096)).executes(PoliticsCommands::order))))
                .then(Commands.literal("relation").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("score", IntegerArgumentType.integer(-100, 100)).executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            Colony c = here(ctx);
                            Colony other = ColonyManager.colonyAt(level, BlockPosArgument.getBlockPos(ctx, "pos"));
                            if (c == null || other == null || c.country.equals(other.country)) return fail(ctx, "No foreign village there");
                            Diplomacy.relation(level, c.country, other.country).score = IntegerArgumentType.getInteger(ctx, "score");
                            ColonyManager.markDirty(level);
                            return ok(ctx, "Relation set");
                        }))))
                .then(Commands.literal("war").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("cause", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Relation.Cause.values()).map(Relation.Cause::key), b))
                                .executes(ctx -> {
                                    ServerLevel level = ctx.getSource().getLevel();
                                    Colony c = here(ctx);
                                    Colony other = ColonyManager.colonyAt(level, BlockPosArgument.getBlockPos(ctx, "pos"));
                                    if (c == null || other == null || c.country.equals(other.country)) return fail(ctx, "No foreign village there");
                                    Relation.Cause cause;
                                    try {
                                        cause = Relation.Cause.valueOf(StringArgumentType.getString(ctx, "cause").toUpperCase(Locale.ROOT));
                                    } catch (IllegalArgumentException e) {
                                        return fail(ctx, "Unknown cause");
                                    }
                                    Diplomacy.declareWar(level, c.country, other.country, cause);
                                    return ok(ctx, "War declared");
                                }))))
                .then(Commands.literal("peace").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    Colony other = ColonyManager.colonyAt(level, BlockPosArgument.getBlockPos(ctx, "pos"));
                    if (c == null || other == null || c.country.equals(other.country)) return fail(ctx, "No foreign village there");
                    Diplomacy.makePeace(level, Diplomacy.relation(level, c.country, other.country), "debug");
                    return ok(ctx, "Peace");
                })))
                .then(Commands.literal("border").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    BlockPos site = BlockPosArgument.getBlockPos(ctx, "pos");
                    boolean got = Borders.acquire(level, c, site);
                    return ok(ctx, got ? "The land around " + site.toShortString() + " belongs to the village's country" : "Not acquired");
                })))
                .then(Commands.literal("day").executes(ctx -> {
                    Politics.forceDay(ctx.getSource().getLevel());
                    return ok(ctx, "Politics day done");
                }));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> world() {
        return Commands.literal("world").requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    ok(ctx, "abstract simulation " + WorldScheduler.enabled() + ", " + SimWorkers.pending() + " background jobs, "
                            + ColonyManager.data(level).caravans.size() + " caravans");
                    for (Colony c : ColonyManager.data(level).colonies()) ok(ctx, ledgerLine(level, c));
                    for (String s : Caravans.status(level)) ok(ctx, s);
                    for (String s : Borders.status(level)) ok(ctx, s);
                    for (Colony c : ColonyManager.data(level).colonies()) {
                        String party = net.betterthanvillage.society.AbstractExpedition.status(level, c);
                        if (party != null) ok(ctx, party);
                    }
                    return 1;
                })
                .then(Commands.literal("step").executes(ctx -> {
                    WorldScheduler.forceStep(ctx.getSource().getLevel());
                    return ok(ctx, "Abstract steps scheduled");
                }))
                .then(Commands.literal("caravan").executes(ctx -> {
                    Caravans.forcePlan(ctx.getSource().getLevel());
                    return ok(ctx, "Caravans planned");
                }))
                .then(Commands.literal("stock").then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096)).executes(ctx -> stock(ctx, null))
                                .then(Commands.argument("potion", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> stock(ctx, net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "potion")))))))
                .then(Commands.literal("ledger").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    ok(ctx, ledgerLine(level, c));
                    java.util.Map<String, Integer> goods = new java.util.TreeMap<>();
                    for (ColonyStorage.Slot slot : ColonyStorage.treasury(level, c)) {
                        for (int i = 0; i < slot.container().getContainerSize(); i++) {
                            var s = slot.container().getItem(i);
                            if (!s.isEmpty()) goods.merge(Values.name(s.getItem()), s.getCount(), Integer::sum);
                        }
                    }
                    return ok(ctx, "treasury " + goods);
                }));
    }

    private static String ledgerLine(ServerLevel level, Colony c) {
        int treasury = Chests.count(ColonyStorage.treasury(level, c), s -> s.is(Items.EMERALD));
        int village = Chests.count(ColonyStorage.villageStock(level, c), s -> s.is(Items.EMERALD));
        return Countries.villageName(c) + ": " + Ledgers.summary(level, c) + ", emeralds " + village + " (treasury " + treasury + ", "
                + AbstractEconomy.emeralds(level, c) + "), last gathering " + c.lastGathering + ", in flight " + AbstractEconomy.inFlight(c)
                + ", labour " + c.catchupLabor + ", houses " + c.catchupHouses;
    }

    private static int countryStatus(CommandContext<CommandSourceStack> ctx) {
        Colony c = here(ctx);
        if (c == null) return fail(ctx, "No village");
        for (String line : Politics.status(ctx.getSource().getLevel(), c)) ok(ctx, line);
        return 1;
    }

    /** Debug: goods put into the nearest village's treasury (its virtual chests while abstract); a potion's kind. */
    private static int stock(CommandContext<CommandSourceStack> ctx, net.minecraft.resources.ResourceLocation potion) {
        ServerLevel level = ctx.getSource().getLevel();
        Colony c = here(ctx);
        if (c == null) return fail(ctx, "No village");
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item"));
        var kind = potion == null ? null : net.minecraft.core.registries.BuiltInRegistries.POTION.getHolder(potion).orElse(null);
        int left = IntegerArgumentType.getInteger(ctx, "count");
        int put = 0;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            var stack = new net.minecraft.world.item.ItemStack(item, n);
            if (kind != null) stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(kind));
            net.betterthanvillage.economy.StockScope.treasury(() -> ColonyStorage.deposit(level, c, stack));
            put += n - stack.getCount();
            left -= n;
            if (!stack.isEmpty()) break;
        }
        return ok(ctx, "Put " + put + " " + Values.name(item) + " into the treasury of " + Countries.villageName(c));
    }

    private static int order(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Colony c = here(ctx);
        if (c == null) return fail(ctx, "No village");
        Country country = Countries.get(level, c.country);
        Colony capital = Countries.capital(level, c.country);
        Order.Kind kind;
        try {
            kind = Order.Kind.valueOf(StringArgumentType.getString(ctx, "kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fail(ctx, "Unknown order");
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        Order o = switch (kind) {
            case TAX -> Orders.issue(level, country, kind, c, capital, "emerald", amount);
            case REDISTRIBUTE -> Orders.issue(level, country, kind, c, capital, Values.Family.FOOD.name(), amount);
            case MOBILIZE -> Orders.issue(level, country, kind, c, capital, "warriors", amount);
        };
        return ok(ctx, "Order " + kind.key() + ": " + o.status.key());
    }

    private static Colony here(CommandContext<CommandSourceStack> ctx) {
        return ColonyCommands.colonyNear(ctx.getSource());
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text));
        return 0;
    }
}
