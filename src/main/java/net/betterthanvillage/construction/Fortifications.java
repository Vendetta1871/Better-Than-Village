package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Walls and gates (05 §4) (stub, not implemented yet). */
public final class Fortifications {
    private Fortifications() {
    }

    /** Called every tick per active colony. */
    public static void tick(ServerLevel level, Colony colony) {
    }

    public static boolean hasPendingWall(Colony colony) {
        return !colony.fortifications.pending.isEmpty();
    }

    /** Registered (built) wall block of any colony. */
    public static boolean isWallBlock(ServerLevel level, BlockPos pos) {
        return false;
    }

    /** Active wall builder test (05 §4.4) (TODO: implement). */
    public static boolean isWallBuilder(ServerLevel level, Colony colony, Villager v) {
        return false;
    }

    /** Wall relocation check when a plot site is accepted (05 §4.3) (TODO: implement). */
    public static void onPlotAccepted(ServerLevel level, Colony colony, int x0, int z0, int w, int d) {
    }

    /** Pathfinding: wall gates / colony fence gates walkable (05 §4.6) (TODO: implement). */
    public static boolean gateWalkable(Mob mob, net.minecraft.world.level.pathfinder.PathfindingContext context, int x, int y, int z) {
        return false;
    }

    /** A fence gate that is a built wall gate of any colony (05 §4.6). */
    public static boolean isWallGate(ServerLevel level, BlockPos pos) {
        return false;
    }
}
