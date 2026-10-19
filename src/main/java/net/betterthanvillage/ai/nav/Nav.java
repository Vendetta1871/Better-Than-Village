package net.betterthanvillage.ai.nav;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.PathBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** The mod's standard villager walk helper with blacklisting and stuck bookkeeping. */
public final class Nav {
    public static final double R2_DEFAULT = 6.0;
    public static final double R2_BED = 2.0;
    public static final double R2_EXACT = 0.25;
    public static final double R2_NEAR = 1.0;
    public static final String STATUS_BLOCKED = "подход перекрыт: выбирается другая точка";
    public static final String STATUS_NO_PATH = "путь не найден: ";

    private static final Map<Villager, State> STATES = new WeakHashMap<>();

    public static final class State {
        public BlockPos target;
        public long nextRepath;
        public long lastCall = Long.MIN_VALUE;
        public int failures;
        public BlockPos stuckAnchor;
        public long stuckAnchorTime;
        public int stuckCount;
        public Vec3 stationaryPos;
        public long stationaryTime;
        public Path lastPath;
        public final Map<BlockPos, Long> blacklist = new HashMap<>();
    }

    private Nav() {
    }

    public static State state(Villager v) {
        return STATES.computeIfAbsent(v, k -> new State());
    }

    public static boolean approach(ServerLevel level, Villager v, BlockPos target, double speed) {
        return approach(level, v, target, speed, R2_DEFAULT);
    }

    /** Returns true while the villager is (re)routed toward the target. */
    public static boolean approach(ServerLevel level, Villager v, BlockPos target, double speed, double r2) {
        long now = level.getGameTime();
        State s = state(v);
        v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        v.getBrain().eraseMemory(MemoryModuleType.PATH);
        if (s.stuckAnchor != null && v.blockPosition().distSqr(s.stuckAnchor) >= 16) {
            s.stuckAnchor = null;
            s.stuckCount = 0;
        }
        s.blacklist.values().removeIf(exp -> exp <= now);
        boolean sameTarget = target.equals(s.target);
        boolean recent = s.lastCall != Long.MIN_VALUE && now - s.lastCall < 60;
        s.lastCall = now;
        PathNavigation nav = v.getNavigation();
        if (!(sameTarget && recent && s.stationaryPos != null && v.position().distanceToSqr(s.stationaryPos) < 0.0625)) {
            s.stationaryPos = v.position();
            s.stationaryTime = now;
        } else if (now - s.stationaryTime >= 100 && nav.isInProgress() && v.position().distanceToSqr(Vec3.atBottomCenterOf(target)) > 2.25) {
            s.blacklist.put(target.immutable(), now + 200);
            nav.stop();
            s.lastPath = null;
            s.nextRepath = now + 20;
            s.stationaryTime = now;
            WorkTracker.status(v, STATUS_BLOCKED);
            return false;
        }
        if (!sameTarget) {
            if (s.target == null || s.target.distSqr(target) > 9) {
                s.failures = 0;
                s.nextRepath = now;
            }
            s.target = target.immutable();
        }
        if (isBlacklisted(v, target, now)) {
            return false;
        }
        if (sameTarget && s.lastPath != null && nav.getPath() == s.lastPath && nav.isInProgress()) {
            return true;
        }
        if (now < s.nextRepath) {
            return false;
        }
        s.nextRepath = now + 10 + Math.floorMod(v.getId(), 10);
        if (!PathBudget.tryAcquire(level)) {
            return false;
        }
        if (nav instanceof GroundPathNavigation ground) {
            ground.setCanOpenDoors(true);
        }
        nav.setCanFloat(true);
        Set<BlockPos> goals = new LinkedHashSet<>();
        if (standable(level, target)) {
            goals.add(target);
        } else {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos c = target.offset(dx, dy, dz);
                        if (c.distSqr(target) <= r2 && standable(level, c)) goals.add(c);
                    }
                }
            }
            if (goals.isEmpty()) goals.add(target);
        }
        double distSq = v.position().distanceToSqr(Vec3.atBottomCenterOf(target));
        nav.setMaxVisitedNodesMultiplier(distSq > 24 * 24 ? 8f : 4f);
        Path path = nav.createPath(goals, 0);
        nav.resetMaxVisitedNodesMultiplier();
        if (path != null) {
            boolean reaches = path.canReach();
            boolean partial = path.getEndNode() != null && path.getNodeCount() > 1
                    && path.getEndNode().asBlockPos().distSqr(target) + 4 < v.blockPosition().distSqr(target);
            if ((reaches || partial) && nav.moveTo(path, speed)) {
                s.lastPath = path;
                s.failures = 0;
                if (reaches) {
                    s.stuckCount = 0;
                    s.stuckAnchor = null;
                }
                return true;
            }
        }
        if (s.stuckAnchor == null || v.blockPosition().distSqr(s.stuckAnchor) >= 16) {
            s.stuckAnchor = v.blockPosition();
            s.stuckAnchorTime = now;
            s.stuckCount = 0;
        }
        s.stuckCount++;
        s.failures++;
        if (s.failures >= 4) {
            s.blacklist.put(target.immutable(), now + 200);
            s.failures = 0;
            if (level.getBlockState(target).getBlock() instanceof ChestBlock) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = target.relative(d);
                    if (level.getBlockState(n).getBlock() instanceof ChestBlock) {
                        s.blacklist.put(n.immutable(), now + 200);
                    }
                }
            }
        }
        WorkTracker.status(v, STATUS_NO_PATH + target.toShortString());
        return false;
    }

    /** Chunk loaded, no fluid in cell or below, cell passable (or thin snow), head clear, full solid top below. */
    public static boolean standable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) return false;
        BlockState s = level.getBlockState(pos);
        boolean thinSnow = s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) < 8;
        if (!thinSnow && !s.getCollisionShape(level, pos).isEmpty()) return false;
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;
        BlockPos b = pos.below();
        BlockState floor = level.getBlockState(b);
        if (floor.isFaceSturdy(level, b, Direction.UP)) return true;
        // Dirt paths and farmland are 15/16 tall: not a sturdy face, but perfectly walkable.
        VoxelShape shape = floor.getCollisionShape(level, b);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.8 && top <= 1.0;
    }

    /** Stored target if the last approach call was less than 60 ticks ago. */
    public static BlockPos currentTarget(Villager v, long now) {
        State s = STATES.get(v);
        if (s == null || s.target == null || s.lastCall == Long.MIN_VALUE || now - s.lastCall >= 60) return null;
        return s.target;
    }

    public static boolean isBlacklisted(Villager v, BlockPos pos, long now) {
        State s = STATES.get(v);
        if (s == null) return false;
        Long exp = s.blacklist.get(pos);
        return exp != null && exp > now;
    }

    public static void blacklist(Villager v, BlockPos pos, long now, int ticks) {
        State s = state(v);
        s.blacklist.put(pos.immutable(), now + ticks);
        if (pos.equals(s.target)) {
            v.getNavigation().stop();
        }
    }

    public static void blacklistCurrentAndStop(Villager v, long now, int ticks) {
        State s = STATES.get(v);
        if (s != null && s.target != null) {
            s.blacklist.put(s.target, now + ticks);
        }
        v.getNavigation().stop();
    }

    /** Navigator stuck predicate (anti-stuck monitor, miner storage approach). */
    public static boolean stuck(Villager v, long now) {
        State s = STATES.get(v);
        if (s == null || s.lastCall == Long.MIN_VALUE || now - s.lastCall >= 60) return false;
        if (s.stuckCount < 12 || s.stuckAnchor == null || now - s.stuckAnchorTime < 600) return false;
        if (v.blockPosition().distSqr(s.stuckAnchor) > 16) return false;
        if (!v.getNavigation().isDone()) return false;
        return s.target != null && v.blockPosition().distSqr(s.target) > 9;
    }

    /** Full reset: drop state, stop navigation, forget WALK_TARGET and PATH. */
    public static void reset(Mob mob) {
        if (mob instanceof Villager v) {
            STATES.remove(v);
        }
        mob.getNavigation().stop();
        if (mob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET) || mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        if (mob.getBrain().checkMemory(MemoryModuleType.PATH, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
            mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        }
    }

    /** Squared distance from the villager's block to the target. */
    public static double distSq(Villager v, BlockPos pos) {
        return v.blockPosition().distSqr(pos);
    }
}
