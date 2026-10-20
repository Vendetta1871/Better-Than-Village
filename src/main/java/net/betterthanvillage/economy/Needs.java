package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.economy.Values.Family;
import net.minecraft.tags.BlockTags;

import java.util.EnumMap;
import java.util.Map;

/**
 * What a household or the treasury keeps for itself (docs_v2/03-economy.md §Gatherings): a few days of food, the
 * tools of its trade and its inputs. Holdings below the reserve become bids at a gathering, holdings above it asks.
 */
public final class Needs {
    /** Nutrition a household eats per day. */
    public static final int DAILY_FOOD = 4;
    /** Days of food a household keeps. */
    public static final int FOOD_DAYS = 3;

    /** How urgently a family is wanted; scales the highest price the buyer accepts. */
    public static final double URGENT = 1.0;
    public static final double TOOL = 0.6;
    public static final double NORMAL = 0.3;
    public static final double INPUT = 0.15;

    private Needs() {
    }

    /** Reserve of a household in family units. */
    public static EnumMap<Family, Integer> household(ColonyRole role) {
        EnumMap<Family, Integer> r = new EnumMap<>(Family.class);
        r.put(Family.FOOD, DAILY_FOOD * FOOD_DAYS);
        if (role == ColonyRole.FARMER || role == ColonyRole.CARPENTER || role == ColonyRole.MINER) {
            // Material for a stone tool when the old one breaks and no smith sells one (ToolSupply crafts it).
            r.put(Family.COBBLE, 3);
            r.put(Family.LOGS, 1);
        }
        switch (role) {
            case FARMER -> {
                r.put(Family.HOE, 1);
                r.put(Family.SEEDS, 16);
            }
            case HERDER -> {
                r.put(Family.SHEARS, 1);
                r.put(Family.BUCKET, 2);
                r.put(Family.FISHING_ROD, 1);
                r.put(Family.WHEAT, 16);
                r.put(Family.SEEDS, 8);
            }
            case CARPENTER -> {
                r.put(Family.AXE, 1);
                r.put(Family.SAPLINGS, 8);
                r.put(Family.STRING, 3);
                r.put(Family.FEATHER, 4);
                r.put(Family.FLINT, 4);
                r.put(Family.WOOL, 3);
            }
            case MINER -> {
                r.put(Family.PICKAXE, 1);
                r.put(Family.TORCH, 16);
                r.put(Family.COAL, 8);
            }
            case SMITH -> {
                r.put(Family.COAL, 8);
                r.put(Family.RAW_IRON, 6);
                r.put(Family.STICKS, 6);
                r.put(Family.COBBLE, 6);
                r.put(Family.SAND, 4);
            }
            case LIBRARIAN -> {
                r.put(Family.SUGAR_CANE, 9);
                r.put(Family.LEATHER, 2);
            }
            case HEALER -> {
                r.put(Family.GLASS, 3);
                r.put(Family.GOLD, 1);
                r.put(Family.FOOD, DAILY_FOOD * FOOD_DAYS + 4);
            }
            default -> {
            }
        }
        return r;
    }

    /**
     * Reserve of the treasury: materials for public work (houses, repairs, roads, walls), gear and golem iron for
     * the village's defence, torches, and food for the children it feeds.
     */
    public static EnumMap<Family, Integer> treasury(Colony colony, int children) {
        EnumMap<Family, Integer> r = new EnumMap<>(Family.class);
        r.put(Family.FOOD, DAILY_FOOD * FOOD_DAYS * Math.max(1, children));
        r.put(Family.LOGS, 48);
        r.put(Family.COBBLE, 96);
        r.put(Family.IRON, 24);
        r.put(Family.ARROW, 32);
        r.put(Family.COAL, 16);
        r.put(Family.TORCH, 16);
        r.put(Family.FENCE, 6);
        r.put(Family.PLANKS, 16);
        if (colony.repairs.values().stream().anyMatch(st -> st.is(BlockTags.BEDS))) {
            r.put(Family.WOOL, 6);
        }
        return r;
    }

    /** Urgency of a missing family for a household: hunger first, then tools, then inputs. */
    public static double urgency(Family family, int held) {
        if (family == Family.FOOD) return held <= 0 ? URGENT : NORMAL;
        return switch (family) {
            case HOE, PICKAXE, AXE, SHEARS, BUCKET, FISHING_ROD -> TOOL;
            default -> INPUT;
        };
    }

    /** Merges a reserve map into a holdings-indexed view (absent families keep nothing). */
    public static int reserveOf(Map<Family, Integer> reserve, Family family) {
        return reserve.getOrDefault(family, 0);
    }
}
