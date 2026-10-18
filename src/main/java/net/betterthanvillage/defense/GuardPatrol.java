package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.PathBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;

/** Guard patrol around the core, led by the guard with the smallest UUID (02 §3.3). */
public final class GuardPatrol {
    private static final int[][] LEGS = {{10, 0}, {0, 10}, {-10, 0}, {0, -10}};

    private GuardPatrol() {
    }

    public static void patrol(ServerLevel level, Colony colony, Villager v) {
        Villager leader = leader(level, colony);
        if (leader == null) leader = v;
        BlockPos dest;
        if (leader != v) {
            if (v.distanceToSqr(leader) <= 16) return;
            dest = leader.blockPosition();
        } else {
            int k = (int) Math.floorMod(level.getGameTime() / 600, 4L);
            BlockPos column = colony.core().offset(LEGS[k][0], 0, LEGS[k][1]);
            if (!level.isLoaded(column)) return;
            dest = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
        }
        if (PathBudget.tryAcquire(level)) {
            v.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 0.6);
        }
    }

    /** The living GUARD in the colony box with the smallest UUID, or null. */
    public static Villager leader(ServerLevel level, Colony colony) {
        Villager best = null;
        for (Villager g : level.getEntitiesOfClass(Villager.class, ThreatManager.colonyBox(colony), LivingEntity::isAlive)) {
            if (colony.roleOf(g.getUUID()) != ColonyRole.GUARD) continue;
            if (best == null || g.getUUID().compareTo(best.getUUID()) < 0) best = g;
        }
        return best;
    }
}
