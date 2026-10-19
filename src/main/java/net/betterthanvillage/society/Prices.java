package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;

/** Price helper formulas (06 §1.2). All results are integers; rounding is Java Math.round. */
public final class Prices {
    public static final int SMALL_HOUSE_BASE = 32;
    public static final int MEDIUM_HOUSE_BASE = 64;
    public static final int LAND_BASE = 24;

    private Prices() {
    }

    static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static int sellBatch(int base, int stock, int reserve) {
        return clamp(Math.min(base, Math.max(1, Math.max(0, stock - reserve) / 4)), 1, 64);
    }

    /**
     * Price of a sell batch. The base price is for the full base batch, so a smaller batch is scaled by
     * batch / baseBatch; returns 0 (no offer) when that rounds below one emerald.
     */
    public static int sellPrice(int base, int baseBatch, int stock, int reserve, int batch) {
        double surplus = clamp(Math.max(0, stock - reserve) / (double) Math.max(1, batch * 8), 0, 1);
        long price = Math.round(base * (1.6 - surplus * 0.8) * batch / (double) Math.max(1, baseBatch));
        return price < 1 ? 0 : clamp(price, 1, 64);
    }

    public static int buyPrice(int base, int have, int target) {
        double shortage = clamp((target - have) / (double) Math.max(1, target), 0, 1);
        return clamp(Math.round(base * (0.75 + shortage * 0.75)), 1, 64);
    }

    public static int deedMaxUses(Colony colony) {
        return clamp(1 + (colony.cachedLogs + colony.cachedStone / 4) / 192, 1, 4);
    }

    public static int realEstatePrice(Colony colony, int base, boolean isLand) {
        int population = colony.population();
        int freeBeds = Math.max(0, colony.beds() - population);
        double crowd = clamp((population - 2.0 * freeBeds) / 40.0, 0, 1);
        double material = clamp((colony.cachedLogs + colony.cachedStone / 4.0) / 384.0, 0, 1);
        double foodDeficit = clamp((population * 12.0 - colony.cachedFood) / Math.max(24, population * 12), 0, 1);
        if (isLand) {
            double mult = 0.8 + 0.4 * crowd + 0.2 * material;
            return clamp(Math.round(base * mult), 16, 40);
        }
        double mult = 1.2 - 0.4 * material + 0.2 * crowd + 0.1 * foodDeficit;
        long price = Math.round(base * mult);
        return base == SMALL_HOUSE_BASE ? clamp(price, 20, 48) : clamp(price, 40, 64);
    }

    public static int smallHousePrice(Colony colony) {
        return realEstatePrice(colony, SMALL_HOUSE_BASE, false);
    }

    public static int mediumHousePrice(Colony colony) {
        return realEstatePrice(colony, MEDIUM_HOUSE_BASE, false);
    }

    public static int landPrice(Colony colony) {
        return realEstatePrice(colony, LAND_BASE, true);
    }
}
