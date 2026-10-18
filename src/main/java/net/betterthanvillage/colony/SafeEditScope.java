package net.betterthanvillage.colony;

import java.util.function.Supplier;

/**
 * Re-entrant "mod is editing" scope. Block changes made inside it are ignored by the
 * griefing listener (damage detection for repairs).
 */
public final class SafeEditScope {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private SafeEditScope() {
    }

    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }

    public static <T> T call(Supplier<T> action) {
        int[] d = DEPTH.get();
        d[0]++;
        try {
            return action.get();
        } finally {
            d[0]--;
        }
    }

    public static void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }
}
