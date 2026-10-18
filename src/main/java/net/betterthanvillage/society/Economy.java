package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Abstract market (06 §1.4) (stub, not implemented yet). */
public final class Economy {
    private Economy() {
    }

    /** Called at every census; runs every 200 ticks when the colony has storage. */
    public static void step(ServerLevel level, Colony colony) {
    }
}
