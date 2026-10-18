package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Guard shield against projectiles (02 §3.5) (stub, not implemented yet). */
public final class GuardShield {
    private GuardShield() {
    }

    /** Returns true when the guard is raising its shield this tick (the controller then stops). */
    public static boolean tick(ServerLevel level, Villager v, Colony colony) {
        return false;
    }

    public static void clearCaches() {
    }
}
