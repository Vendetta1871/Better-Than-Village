package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.minecraft.world.entity.npc.Villager;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Whose stock the colony storage operations ({@code ColonyStorage}) work on (docs_v2/03-economy.md §Households).
 * While a villager's AI step runs, the stock is that villager's household (its home chests); public work,
 * warriors' gear and every village-level step use the treasury (the village stock). Code outside a villager's AI
 * step always uses the treasury.
 */
public final class StockScope {
    /** Explicit treasury marker (a villager step that switched to public work). */
    private static final Object TREASURY = new Object();
    /** null (treasury), TREASURY or the UUID of the household owner. */
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private StockScope() {
    }

    /** Start of a villager's AI step: its own household's stock. */
    public static void enterVillager(Villager v) {
        CURRENT.set(v.getUUID());
    }

    /** End of a villager's AI step. */
    public static void exit() {
        CURRENT.remove();
    }

    /** Switches to the given scope and returns the previous one (for {@link #restore}). */
    public static Object push(boolean treasury, UUID owner) {
        Object previous = CURRENT.get();
        CURRENT.set(treasury ? TREASURY : owner);
        return previous;
    }

    public static void restore(Object previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /** The household whose home chests the storage operations use for this colony, or null for the treasury. */
    public static Household household(Colony colony) {
        return CURRENT.get() instanceof UUID owner ? colony.households.get(owner) : null;
    }

    /** True while a villager step works for the treasury (public work). */
    public static boolean inPublicWork() {
        return CURRENT.get() == TREASURY;
    }

    /** True while the operations use a household's stock (of any colony). */
    public static boolean inHousehold() {
        return CURRENT.get() instanceof UUID;
    }

    public static void treasury(Runnable action) {
        Object previous = push(true, null);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T treasury(Supplier<T> action) {
        Object previous = push(true, null);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static boolean treasuryTest(BooleanSupplier action) {
        Object previous = push(true, null);
        try {
            return action.getAsBoolean();
        } finally {
            restore(previous);
        }
    }

    /** Runs the action on one household's stock. */
    public static void household(UUID owner, Runnable action) {
        Object previous = push(false, owner);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T household(UUID owner, Supplier<T> action) {
        Object previous = push(false, owner);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }
}
