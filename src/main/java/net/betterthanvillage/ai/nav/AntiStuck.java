package net.betterthanvillage.ai.nav;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Anti-stuck monitor and emergency teleport (03 §5.3) (stub, not implemented yet). */
public final class AntiStuck {
    private AntiStuck() {
    }

    public static void monitor(ServerLevel level, Colony colony, Villager v) {
    }

    /** True while the villager is suppressed (forced idle). */
    public static boolean isForcedIdle(Villager v, long now) {
        return false;
    }

    public static void suppress(Villager v, long until) {
    }

    /** Teleports the villager to a safe cell near the core; returns true on success. */
    public static boolean emergencyTeleport(ServerLevel level, Colony colony, Villager v) {
        return false;
    }
}
