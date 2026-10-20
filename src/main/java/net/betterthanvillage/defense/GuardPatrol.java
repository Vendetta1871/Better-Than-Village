package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.PathBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Guard patrol around the core, led by the guard with the smallest UUID (02 §3.3). */
public final class GuardPatrol {
    /** Eight compass directions, visited in turn at a distance that scales with the colony radius. */
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final long LEG_TICKS = 400;
    /** Colony id -> tick of the last threat position the leader has already checked. */
    private static final Map<UUID, Long> CHECKED = new HashMap<>();

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
            BlockPos column = recentThreat(level, colony, v);
            if (column == null) {
                int k = (int) Math.floorMod(level.getGameTime() / LEG_TICKS, (long) DIRS.length);
                int dist = Math.max(16, Math.min(64, colony.radius() / 2));
                // diagonal legs keep the same distance from the core
                int step = DIRS[k][0] != 0 && DIRS[k][1] != 0 ? (int) Math.round(dist / Math.sqrt(2)) : dist;
                column = colony.core().offset(DIRS[k][0] * step, 0, DIRS[k][1] * step);
            }
            if (!level.isLoaded(column)) return;
            dest = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
        }
        if (PathBudget.tryAcquire(level)) {
            v.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 0.6);
        }
    }

    /** The last threat position if it is recent and the leader has not checked it yet, else null. */
    private static BlockPos recentThreat(ServerLevel level, Colony colony, Villager leader) {
        ThreatManager.LastThreat t = ThreatManager.lastThreat(colony);
        long now = level.getGameTime();
        if (t == null || now - t.tick() > 1200 || CHECKED.getOrDefault(colony.id(), Long.MIN_VALUE) >= t.tick()) return null;
        if (leader.blockPosition().distSqr(t.pos()) <= 36) {
            CHECKED.put(colony.id(), t.tick());
            return null;
        }
        return t.pos();
    }

    public static void clearCaches() {
        CHECKED.clear();
    }

    /** The living GUARD in the colony box with the smallest UUID, or null. */
    public static Villager leader(ServerLevel level, Colony colony) {
        Villager best = null;
        for (Villager g : level.getEntitiesOfClass(Villager.class, ThreatManager.colonyBox(colony), LivingEntity::isAlive)) {
            if (colony.roleOf(g.getUUID()) != ColonyRole.WARRIOR) continue;
            if (best == null || g.getUUID().compareTo(best.getUUID()) < 0) best = g;
        }
        return best;
    }
}
