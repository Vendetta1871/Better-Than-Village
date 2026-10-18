package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Unauthorized building notices (06 §3) (stub, not implemented yet). */
public final class LandNotices {
    private LandNotices() {
    }

    public static void tick(ServerLevel level) {
    }

    /** Elder walks to an undelivered notice recipient; returns true when it took over the villager this tick. */
    public static boolean elderWalk(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
