package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Raid shelter state (02 §6) (stub, not implemented yet). */
public final class RaidShelter {
    private RaidShelter() {
    }

    public static boolean isActive(ServerLevel level, Colony colony) {
        return false;
    }

    public static void clearCaches() {
    }
}
