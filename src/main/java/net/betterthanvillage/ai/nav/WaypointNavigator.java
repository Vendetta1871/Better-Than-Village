package net.betterthanvillage.ai.nav;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Long-distance waypoint navigator for any mob (03 §5.5) (stub, not implemented yet). */
public final class WaypointNavigator {
    private WaypointNavigator() {
    }

    /** Returns true while travelling (or arrived). */
    public static boolean travel(ServerLevel level, Mob mob, BlockPos goal, double speed) {
        return mob.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, speed);
    }

    public static void reset(Mob mob) {
    }

    public static void clearCaches() {
    }
}
