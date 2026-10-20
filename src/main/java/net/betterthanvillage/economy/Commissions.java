package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

/**
 * Public works commissioned by the treasury (docs_v2/04-land.md §Construction): walls, lamp posts, terraforming
 * and demolition. Workers are paid per unit of work from the treasury (Treasury.payWages), so the treasury only
 * keeps such work going while it can pay for it: it holds more emeralds than the army's wages for a few days plus
 * the pay it already owes. Houses are paid by the parents and repairs are always done.
 */
public final class Commissions {
    /** Days of army wages the treasury keeps back from public works. */
    private static final int ARMY_DAYS = 3;
    private static final String KEY = "btv_commission_budget";
    private static final long CACHE_TICKS = 200;

    private record Cached(long tick, int budget) {
    }

    private Commissions() {
    }

    /** Emeralds the treasury can still spend on public works (cached for a few seconds). */
    public static int budget(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        Object c = colony.runtime.get(KEY);
        if (c instanceof Cached cached && now - cached.tick() >= 0 && now - cached.tick() < CACHE_TICKS) return cached.budget();
        int held = Chests.count(ColonyStorage.treasury(level, colony), s -> s.is(Items.EMERALD));
        int units = Math.max(1, ColonyConfig.get().economy.labor_units_per_emerald);
        int owed = 0;
        for (Household h : colony.households.values()) owed += h.wagesOwed + h.labor / units;
        int budget = held - Treasury.dailyWageBill(colony) * ARMY_DAYS - owed;
        colony.runtime.put(KEY, new Cached(now, budget));
        return budget;
    }

    /** True while the treasury can pay for at least one more emerald of public works. */
    public static boolean canPay(ServerLevel level, Colony colony) {
        return budget(level, colony) >= 1;
    }
}
