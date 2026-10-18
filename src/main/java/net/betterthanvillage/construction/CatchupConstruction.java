package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;

/** Offline catch-up building: places pending catch-up job blocks within a block budget (05 §2.10) (stub). */
public final class CatchupConstruction {
    private CatchupConstruction() {
    }

    /** Returns the number of blocks placed (consumed from the budget). */
    public static int step(ServerLevel level, Colony colony, int budget) {
        return 0;
    }

    /** Plans a catch-up house job if possible; returns true when one was queued. */
    public static boolean planCatchupHouse(ServerLevel level, Colony colony) {
        return false;
    }
}
