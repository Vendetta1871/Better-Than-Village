package net.betterthanvillage.society;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.RoleBalancer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.EnumMap;
import java.util.Map;

/** /btv commands (01 §10, 06 §8). */
public final class ColonyCommands {
    private ColonyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("btv")
                .then(Commands.literal("status").executes(ColonyCommands::status)));
    }

    /** Colony at the command position, else the nearest colony core. */
    public static Colony colonyNear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null) {
            double best = Double.MAX_VALUE;
            for (Colony c : ColonyManager.data(level).colonies()) {
                double d = c.core().distSqr(pos);
                if (d < best) {
                    best = d;
                    colony = c;
                }
            }
        }
        return colony;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Component.literal("No registered village nearby"));
            return 0;
        }
        long now = level.getGameTime();
        StringBuilder sb = new StringBuilder();
        sb.append("Colony ").append(c.id()).append(" core ").append(c.core().toShortString()).append(" radius ").append(c.radius()).append('\n');
        sb.append("population ").append(c.population()).append(" beds ").append(c.beds())
                .append(" food ").append(c.cachedFood).append(" carried ").append(ColonyStorage.carriedFood(ColonyManager.members(level, c)))
                .append(" logs ").append(c.cachedLogs).append(" stone ").append(c.cachedStone)
                .append(" iron ").append(c.cachedIron).append(" diamonds ").append(c.cachedDiamonds).append('\n');
        sb.append("storages ").append(c.storages.size()).append(" managed ").append(c.managedStorages.size())
                .append(" free slots ").append(ColonyStorage.emptySlots(level, c)).append('\n');
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            counts.merge(c.roleOf(v.getUUID()), 1, Integer::sum);
        }
        sb.append("roles ").append(counts).append('\n');
        EnumMap<ColonyRole, Integer> weights = RoleBalancer.demand(level, c);
        sb.append("demand ").append(weights).append('\n');
        sb.append("threat age ").append(c.lastThreatTick == Colony.NEVER ? "never" : String.valueOf(now - c.lastThreatTick)).append('\n');
        int active = 0, complete = 0;
        for (ConstructionJob job : c.jobs) {
            if (job.isActive()) active++;
            if (job.isComplete()) complete++;
        }
        sb.append("jobs ").append(c.jobs.size()).append(" active ").append(active).append(" complete ").append(complete)
                .append(" roads ").append(c.roadQueue.size()).append(" repairs ").append(c.repairs.size())
                .append(" walls pending ").append(c.fortifications.pending.size()).append('\n');
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            ColonyRole role = c.roleOf(v.getUUID());
            if (role == ColonyRole.CITIZEN) continue;
            sb.append(" - ").append(role.serializedName()).append(" @").append(v.blockPosition().toShortString())
                    .append(" activity ").append(v.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("-"))
                    .append(": ").append(WorkTracker.status(v)).append('\n');
        }
        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Helper for subcommands added by other features. */
    public static Map<ColonyRole, Integer> roleCounts(ServerLevel level, Colony c) {
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            counts.merge(c.roleOf(v.getUUID()), 1, Integer::sum);
        }
        return counts;
    }
}
