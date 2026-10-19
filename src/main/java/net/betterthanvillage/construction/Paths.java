package net.betterthanvillage.construction;

import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.mixin.construction.PathNavigationInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/** Path searches and entity relocation helpers used by the construction tasks. */
public final class Paths {
    static final int MAX_GOALS = 16;

    private Paths() {
    }

    /**
     * Path search to a goal set: can open doors, can swim, follow range 128, range multiplier 4,
     * accuracy 0. Only the first {@link #MAX_GOALS} goals (in the caller's preference order) are
     * searched: the multi-target search costs O(nodes * targets). Callers page through larger sets.
     */
    public static Path find(Mob mob, Set<BlockPos> goals) {
        if (goals.isEmpty()) return null;
        if (goals.size() > MAX_GOALS) {
            goals = goals.stream().limit(MAX_GOALS).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        PathNavigation nav = mob.getNavigation();
        if (nav instanceof GroundPathNavigation ground) {
            ground.setCanOpenDoors(true);
        }
        nav.setCanFloat(true);
        nav.setMaxVisitedNodesMultiplier(4f);
        PathNavigationInvoker access = (PathNavigationInvoker) nav;
        BlockPos targetPos = access.btv$targetPos();
        int reachRange = access.btv$reachRange();
        try {
            return access.btv$createPath(goals, 8, false, 0, 128f);
        } finally {
            nav.resetMaxVisitedNodesMultiplier();
            // a probe must not redirect the mob's current navigation on its next recomputePath()
            access.btv$setTargetPos(targetPos);
            access.btv$setReachRange(reachRange);
        }
    }

    /** Surface standable points on the rectangular ring at Chebyshev distance k around a box. */
    public static void ring(ServerLevel level, BoundingBox box, int k, java.util.function.Consumer<BlockPos> out) {
        for (int x = box.minX() - k; x <= box.maxX() + k; x++) {
            for (int z = box.minZ() - k; z <= box.maxZ() + k; z++) {
                boolean edge = x == box.minX() - k || x == box.maxX() + k || z == box.minZ() - k || z == box.maxZ() + k;
                if (!edge || !Terrain.loaded(level, x, z)) continue;
                out.accept(Terrain.surface(level, x, z));
            }
        }
    }

    /**
     * Goal window number {@code page} (MAX_GOALS each, wrapping) of an ordered candidate list;
     * empty when the list is empty.
     */
    public static java.util.LinkedHashSet<BlockPos> window(List<BlockPos> ordered, int page) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        if (ordered.isEmpty()) return out;
        int pages = (ordered.size() + MAX_GOALS - 1) / MAX_GOALS;
        int from = Math.floorMod(page, pages) * MAX_GOALS;
        out.addAll(ordered.subList(from, Math.min(ordered.size(), from + MAX_GOALS)));
        return out;
    }

    public static int pages(List<BlockPos> ordered) {
        return (ordered.size() + MAX_GOALS - 1) / MAX_GOALS;
    }

    /** Living entities (alive, not spectator) whose box intersects the block. */
    public static java.util.List<LivingEntity> occupants(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e.isAlive() && !e.isSpectator());
    }

    /** Sends a villager/golem towards a point (villagers via the standard approach). */
    public static void send(ServerLevel level, Mob mob, BlockPos target, double speed, double r2) {
        if (mob instanceof Villager v) {
            Nav.approach(level, v, target, speed, r2);
        } else if (mob instanceof IronGolem) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
    }
}
