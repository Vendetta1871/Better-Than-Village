package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Offline catch-up simulation (06 §7) (stub, not implemented yet). */
public final class OfflineSim {
    private OfflineSim() {
    }

    public static void bookkeeping(ServerLevel level, Colony colony) {
    }

    /** Applies part of the catch-up; returns the number of blocks used from the budget. */
    public static int catchupStep(ServerLevel level, Colony colony, int budget) {
        return 0;
    }

    public static void clearCaches() {
    }
}
