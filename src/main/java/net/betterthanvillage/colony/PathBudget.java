package net.betterthanvillage.colony;

import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.WeakHashMap;

/** Shared per-world limiter for expensive path/reachability probes: at most 6 per tick. */
public final class PathBudget {
    public static final int LIMIT = 6;
    private static final Map<ServerLevel, long[]> STATE = new WeakHashMap<>();

    private PathBudget() {
    }

    /** Consumes one unit; returns false once 6 were granted during the current world tick. */
    public static synchronized boolean tryAcquire(ServerLevel level) {
        long[] s = STATE.computeIfAbsent(level, l -> new long[]{Long.MIN_VALUE, 0});
        long now = level.getGameTime();
        if (s[0] != now) {
            s[0] = now;
            s[1] = 0;
        }
        if (s[1] < LIMIT) {
            s[1]++;
            return true;
        }
        return false;
    }

    /** True while budget remains this tick, without consuming. */
    public static synchronized boolean available(ServerLevel level) {
        long[] s = STATE.get(level);
        return s == null || s[0] != level.getGameTime() || s[1] < LIMIT;
    }

    public static synchronized void clear() {
        STATE.clear();
    }
}
