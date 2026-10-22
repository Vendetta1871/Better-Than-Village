package net.betterthanvillage.warfare;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Relation;
import net.betterthanvillage.society.ColonyCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** Debug commands for warfare (docs_v2/07-warfare.md): /btv war inspects armies and campaigns and stages battles. */
public final class WarfareCommands {
    private WarfareCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> war() {
        return Commands.literal("war").requires(s -> s.hasPermission(2))
                .executes(WarfareCommands::status)
                .then(Commands.literal("launch").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    Colony other = ColonyManager.colonyAt(level, BlockPosArgument.getBlockPos(ctx, "pos"));
                    if (c == null || other == null || c.country.equals(other.country)) return fail(ctx, "No foreign village there");
                    if (!Diplomacy.atWar(level, c.country, other.country)) return fail(ctx, "Not at war");
                    Campaign campaign = Campaigns.launch(level, c.country, other.country);
                    return campaign == null ? fail(ctx, "No army") : ok(ctx, "Campaign " + Campaigns.shortId(campaign) + " with "
                            + campaign.soldiers.size() + " soldiers");
                })))
                .then(Commands.literal("step").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    int n = Campaigns.forceStep(level);
                    return ok(ctx, "Stepped " + n + " campaigns");
                }))
                .then(Commands.literal("fortify").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    int n = Fortifications.buildNow(level, c);
                    return ok(ctx, "Built " + n + " wall blocks, rings " + Fortifications.ringRadii(c) + " built " + Fortifications.builtRings(c) + ", managed "
                            + c.fortifications.managed.size() + ", pending " + c.fortifications.pending.size());
                }))
                .then(Commands.literal("army").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    Army.assign(level, c);
                    for (String s : army(level, c)) ok(ctx, s);
                    return 1;
                }))
                .then(Commands.literal("recruit").then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                    // Debug: adults of the village are conscripted at once (abstract villages too).
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    if (c == null) return fail(ctx, "No village");
                    int want = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"), n = 0;
                    long day = net.betterthanvillage.economy.Credit.day(level);
                    for (UUID id : new java.util.ArrayList<>(c.households.keySet())) {
                        if (n >= want) break;
                        if (id.equals(c.elder) || c.roleOf(id) == net.betterthanvillage.colony.ColonyRole.WARRIOR) continue;
                        c.conscripts.put(id, day + 1000);
                        if (level.getEntity(id) instanceof net.minecraft.world.entity.npc.Villager v) net.betterthanvillage.colony.Professions.applyRole(level, c, v);
                        n++;
                    }
                    Army.assign(level, c);
                    ColonyManager.markDirty(level);
                    return ok(ctx, "Recruited " + n + ": " + Army.warriors(level, c).size() + " warriors");
                })))
                .then(Commands.literal("night").executes(ctx -> {
                    int n = NightOps.force(ctx.getSource().getLevel());
                    return ok(ctx, "Sent " + n + " agents");
                }))
                .then(Commands.literal("intel").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    PlayerBases.tick(level, 0);
                    for (String s : PlayerBases.status(level)) ok(ctx, s);
                    return 1;
                }))
                .then(Commands.literal("player").then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                    // Debug: the nearest village's country declares war on the player (a bot too).
                    ServerLevel level = ctx.getSource().getLevel();
                    Colony c = here(ctx);
                    String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
                    net.minecraft.world.entity.player.Player p = level.getServer().getPlayerList().getPlayerByName(name);
                    if (p == null) p = net.betterthanvillage.debug.TestBot.get(name);
                    if (c == null || p == null) return fail(ctx, "No village or no such player");
                    net.betterthanvillage.law.Wars.declare(level, c, p, net.betterthanvillage.law.Offence.ATTACK);
                    return ok(ctx, "War on " + name);
                })))
                .then(Commands.literal("offensive").executes(ctx -> {
                    PlayerBases.daily(ctx.getSource().getLevel());
                    return ok(ctx, "Offensives decided");
                }));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        for (String s : Campaigns.status(level)) ok(ctx, s);
        for (Relation r : ColonyManager.data(level).relations.values()) {
            if (r.state != Relation.State.WAR) continue;
            ok(ctx, "war " + Countries.plainName(level, r.a) + " vs " + Countries.plainName(level, r.b) + ": " + side(level, r, r.a) + " | "
                    + side(level, r, r.b));
        }
        Colony c = here(ctx);
        if (c != null) for (String s : army(level, c)) ok(ctx, s);
        return 1;
    }

    private static String side(ServerLevel level, Relation r, UUID country) {
        Relation.Side s = r.side(country);
        return "warriors " + s.warriors + ", losses " + s.losses + ", villages lost " + s.villagesLost + ", besieged " + s.besiegedHours
                + "h, spent " + s.spent + ", weariness " + Math.round(Weariness.weariness(level, r, country)) + "/"
                + Math.round(Weariness.tolerance(level, country));
    }

    static java.util.List<String> army(ServerLevel level, Colony c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Map<WarriorClass, Integer> classes = new java.util.EnumMap<>(WarriorClass.class);
        for (UUID id : Army.warriors(level, c)) classes.merge(Army.classOf(c, id), 1, Integer::sum);
        out.add(Countries.villageName(c) + ": warriors " + Army.warriors(level, c).size() + " " + classes + ", night watch " + c.nightWatch.size()
                + ", horses " + c.horses + ", golem " + (c.golem == null ? "-" : c.golem.toString().substring(0, 8)) + ", gear "
                + Battle.round1(c.warriorGear) + ", rings " + Fortifications.builtRings(c) + "/" + Fortifications.ringRadii(c) + ", defence " + Battle.round1(Battle.defencePower(level, c, null))
                + ", pending breaches " + c.pendingBreaches.size());
        return out;
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
