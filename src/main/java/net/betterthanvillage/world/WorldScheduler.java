package net.betterthanvillage.world;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The world scheduler (docs_v2/08-simulation.md §World scheduler): advances every abstract village once per step
 * (an in-game hour by default), the caravans and the countries, whether or not any chunk is loaded. Villages are
 * offset from each other so their steps do not fall on the same tick, and all work of a tick stays within the time
 * budget; what does not fit waits for the next tick.
 */
public final class WorldScheduler {
    /** Colony id -> game tick of its next step (runtime; offsets are recomputed after a restart). */
    private static final Map<UUID, Long> NEXT = new HashMap<>();
    private static final Deque<UUID> QUEUE = new ArrayDeque<>();

    private WorldScheduler() {
    }

    public static boolean enabled() {
        return ColonyConfig.get().world.abstract_simulation;
    }

    public static void clearCaches() {
        NEXT.clear();
        QUEUE.clear();
        SimWorkers.shutdown();
        Ledgers.clearCaches();
    }

    public static void tick(ServerLevel level) {
        if (!enabled()) return;
        long start = System.nanoTime();
        long deadline = start + (long) (ColonyConfig.get().world.tick_budget_ms * 1_000_000L);
        long now = level.getGameTime();
        SimWorkers.drain(deadline);
        int step = ColonyConfig.get().world.step_ticks;
        for (Colony colony : ColonyManager.data(level).colonies()) {
            long next = NEXT.computeIfAbsent(colony.id(), id -> now + Math.floorMod(id.getLeastSignificantBits(), (long) step));
            if (now >= next && !QUEUE.contains(colony.id())) {
                QUEUE.add(colony.id());
                NEXT.put(colony.id(), now + step);
            }
        }
        while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
            Colony colony = ColonyManager.data(level).colony(QUEUE.poll());
            if (colony == null) continue;
            // A backlog of missed gatherings is worked off over the next ticks, one gathering per step.
            if (AbstractEconomy.step(level, colony)) NEXT.put(colony.id(), now + 1);
        }
        if (System.nanoTime() < deadline) Caravans.tick(level, now);
        net.betterthanvillage.warfare.Campaigns.tick(level, now);
        if (System.nanoTime() < deadline) net.betterthanvillage.warfare.NightOps.tick(level, now);
        if (System.nanoTime() < deadline) net.betterthanvillage.politics.Politics.tick(level, now, deadline);
    }

    /** Debug: the next abstract step of every village comes at once. */
    public static void forceStep(ServerLevel level) {
        for (Colony colony : ColonyManager.data(level).colonies()) NEXT.put(colony.id(), level.getGameTime());
    }

    public static long nextStep(Colony colony) {
        return NEXT.getOrDefault(colony.id(), -1L);
    }
}
