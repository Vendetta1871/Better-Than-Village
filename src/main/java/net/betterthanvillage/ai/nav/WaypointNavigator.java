package net.betterthanvillage.ai.nav;

import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.gather.GatherUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;

/** Long-distance waypoint navigator for any mob (03 §5.5): direct paths, bounded custom A*, sub-path following. */
public final class WaypointNavigator {
    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LAST_ASTAR = new WeakHashMap<>();

    private static final class State {
        BlockPos goal;
        List<BlockPos> waypoints = new ArrayList<>();
        int index;
        Path path;
        long replanAfter;
        long directTry;
        long subpathAfter;
        long globalReplan = Long.MIN_VALUE / 2;
        BlockPos anchor;
        long anchorTime;
        int failures;
        long lastTravel;
    }

    private record Node(BlockPos pos, int g, int f) {
    }

    private WaypointNavigator() {
    }

    /** Returns true while travelling (or arrived). */
    public static boolean travel(ServerLevel level, Mob mob, BlockPos goal, double speed) {
        long now = level.getGameTime();
        PathNavigation nav = mob.getNavigation();
        if (mob instanceof Villager) {
            if (mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (mob.getBrain().checkMemory(MemoryModuleType.PATH, MemoryStatus.REGISTERED)) mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        }
        if (nav instanceof GroundPathNavigation ground) ground.setCanOpenDoors(true);
        nav.setCanFloat(true);
        State s = STATES.computeIfAbsent(mob, m -> new State());
        s.lastTravel = now;
        // s.goal is the goal the current plan was made for; a moving target drifts away from it
        // gradually, so it is only updated on a replan.
        if (s.goal == null || s.goal.distSqr(goal) > 16) {
            s.waypoints = new ArrayList<>();
            s.index = 0;
            s.path = null;
            s.directTry = now;
            s.goal = goal.immutable();
        }
        BlockPos here = mob.blockPosition();
        if (s.anchor == null || here.distSqr(s.anchor) >= 4) {
            s.anchor = here;
            s.anchorTime = now;
            s.failures = 0;
        }
        double goalD2 = mob.position().distanceToSqr(Vec3.atBottomCenterOf(goal));
        if (goalD2 <= 2.25) {
            nav.stop();
            return true;
        }
        if (s.waypoints.isEmpty() && goalD2 < 2304 && now >= s.directTry && PathBudget.tryAcquire(level)) {
            s.directTry = now + 60;
            Path path = createPath(nav, goal, 1);
            if (path != null && path.canReach()) {
                s.waypoints = new ArrayList<>(List.of(goal.immutable()));
                s.index = 0;
                s.path = path;
                nav.moveTo(path, speed);
                return true;
            }
        }
        if (now - s.anchorTime > 120 && now - s.globalReplan >= 120) {
            s.globalReplan = now;
            s.waypoints = new ArrayList<>();
            s.index = 0;
            s.path = null;
        }
        while (s.index < s.waypoints.size() && mob.position().distanceToSqr(Vec3.atBottomCenterOf(s.waypoints.get(s.index))) <= 2.25) {
            s.index++;
        }
        if (s.index >= s.waypoints.size() && now >= s.replanAfter) {
            Long last = LAST_ASTAR.get(level);
            if ((last != null && now - last < 10) || !PathBudget.tryAcquire(level)) return false;
            LAST_ASTAR.put(level, now);
            s.replanAfter = now + 100;
            s.waypoints = astar(level, mob, here, goal);
            s.index = 0;
            s.path = null;
            if (s.waypoints.isEmpty()) s.failures++;
        }
        if (s.path != null && !s.path.isDone()) {
            if (nav.getPath() != s.path) nav.moveTo(s.path, speed);
            return true;
        }
        if (s.index < s.waypoints.size() && now >= s.subpathAfter && PathBudget.tryAcquire(level)) {
            s.subpathAfter = now + 10;
            BlockPos ahead = s.waypoints.get(Math.min(s.index + 7, s.waypoints.size() - 1));
            Path path = createPath(nav, ahead, 0);
            if (path == null) path = createPath(nav, s.waypoints.get(s.index), 0);
            if (path == null) {
                s.failures++;
                s.waypoints = new ArrayList<>();
                s.index = 0;
                s.replanAfter = now + 40;
                return false;
            }
            s.path = path;
            nav.moveTo(path, speed);
            return true;
        }
        return false;
    }

    private static Path createPath(PathNavigation nav, BlockPos target, int accuracy) {
        nav.setMaxVisitedNodesMultiplier(4f);
        Path path = nav.createPath(target, accuracy);
        nav.resetMaxVisitedNodesMultiplier();
        return path;
    }

    // ---------------------------------------------------------------- custom A*

    /** Bounded A* (6000 expansions, box = start/goal bounds ±48 on X/Z); best-effort path without the start. */
    public static List<BlockPos> astar(ServerLevel level, Mob mob, BlockPos start, BlockPos goal) {
        int minX = Math.min(start.getX(), goal.getX()) - 48, maxX = Math.max(start.getX(), goal.getX()) + 48;
        int minZ = Math.min(start.getZ(), goal.getZ()) - 48, maxZ = Math.max(start.getZ(), goal.getZ()) + 48;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> a.f() != b.f() ? Integer.compare(a.f(), b.f()) : Integer.compare(b.g(), a.g()));
        Map<BlockPos, Integer> gScore = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        gScore.put(start, 0);
        open.add(new Node(start, 0, h(start, goal)));
        BlockPos best = start;
        int bestH = h(start, goal);
        BlockPos reached = null;
        int expansions = 0;
        while (!open.isEmpty() && expansions < 6000) {
            Node cur = open.poll();
            Integer known = gScore.get(cur.pos());
            if (known != null && known < cur.g()) continue;
            expansions++;
            BlockPos p = cur.pos();
            if (p.distSqr(goal) <= 2.25) {
                reached = p;
                break;
            }
            int hp = h(p, goal);
            if (hp < bestH) {
                bestH = hp;
                best = p;
            }
            boolean headroom = clear(level, mob, p.above(Mth.ceil(mob.getBbHeight())));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = p.relative(d);
                if (side.getX() < minX || side.getX() > maxX || side.getZ() < minZ || side.getZ() > maxZ) continue;
                for (int dy : new int[]{0, 1, -1}) {
                    if (dy == 1 && !headroom) continue;
                    BlockPos n = side.above(dy);
                    if (!walkable(level, mob, n)) continue;
                    int g = cur.g() + 10 + 4 * Math.abs(dy);
                    Integer old = gScore.get(n);
                    if (old == null || g < old) {
                        gScore.put(n, g);
                        parent.put(n, p);
                        open.add(new Node(n, g, g + h(n, goal)));
                    }
                    break;
                }
            }
        }
        BlockPos end = reached != null ? reached : best;
        if (end.equals(start)) return new ArrayList<>();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p = end; p != null && !p.equals(start); p = parent.get(p)) out.add(p);
        Collections.reverse(out);
        return out;
    }

    private static int h(BlockPos a, BlockPos b) {
        return (Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ())) * 10;
    }

    /** A* walkability: valid support (or swimming), no hazard floor, and a clear mob box. */
    public static boolean walkable(ServerLevel level, Mob mob, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.isLoaded(pos)) return false;
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE)) return false;
        boolean support = level.getFluidState(below).isEmpty()
                && (floor.isFaceSturdy(level, below, Direction.UP) || floor.getBlock() instanceof StairBlock || floor.getBlock() instanceof SlabBlock);
        boolean swimming = level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos.above()).isEmpty();
        if (!support && !swimming) return false;
        double half = mob.getBbWidth() / 2.0;
        int x0 = Mth.floor(pos.getX() + 0.5 - half), x1 = Mth.floor(pos.getX() + 0.5 + half - 1.0E-4);
        int z0 = Mth.floor(pos.getZ() + 0.5 - half), z1 = Mth.floor(pos.getZ() + 0.5 + half - 1.0E-4);
        int h = Math.max(1, Mth.ceil(mob.getBbHeight()));
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = 0; y < h; y++) {
                    BlockPos c = new BlockPos(x, pos.getY() + y, z);
                    if (!cellClear(level, mob, c, y == 0)) return false;
                }
            }
        }
        return true;
    }

    private static boolean clear(ServerLevel level, Mob mob, BlockPos c) {
        return cellClear(level, mob, c, false);
    }

    private static boolean cellClear(ServerLevel level, Mob mob, BlockPos c, boolean foot) {
        FluidState fluid = level.getFluidState(c);
        if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) return false;
        BlockState s = level.getBlockState(c);
        if (s.is(Blocks.FIRE) || s.is(Blocks.LAVA) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)) return false;
        if (s.getCollisionShape(level, c).isEmpty()) return true;
        if (foot && s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) < 8) return true;
        boolean villager = mob instanceof Villager;
        if (s.getBlock() instanceof FenceGateBlock && (villager || Fortifications.isWallGate(level, c))) return !Fortifications.enemyGate(level, mob, c);
        return villager && s.getBlock() instanceof DoorBlock door && door.type().canOpenByHand();
    }

    // ---------------------------------------------------------------- unstick

    /**
     * Unstick-teleport for travel callers: after 8 failures with a 1200-tick old anchor, an idle grounded mob
     * without waypoints hops to a nearby walkable, unclaimed, unoccupied cell. Returns true if it teleported.
     */
    public static boolean unstickTeleport(ServerLevel level, Mob mob) {
        State s = STATES.get(mob);
        long now = level.getGameTime();
        if (s == null || s.failures < 8 || now - s.anchorTime < 1200 || !mob.onGround() || !mob.getNavigation().isDone()) return false;
        if (mob instanceof Villager v && (v.isSleeping() || v.getTradingPlayer() != null)) return false;
        if (s.index < s.waypoints.size() || mob.getDeltaMovement().length() > 0.1) return false;
        BlockPos p = mob.blockPosition();
        for (int r = 1; r <= 6; r++) {
            for (int dy : new int[]{1, 2, 0, -1}) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos c = p.offset(dx, dy, dz);
                        if (!walkable(level, mob, c) || ColonyManager.data(level).inLand(c)) continue;
                        if (Protection.isProtectedBlock(level, c.below()) || !GatherUtil.freeOfLiving(level, c, mob)) continue;
                        GatherUtil.teleport(mob, c);
                        STATES.remove(mob);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The mob travelled by waypoints within the last second. */
    public static boolean active(Mob mob, long now) {
        State s = STATES.get(mob);
        return s != null && now - s.lastTravel <= 20;
    }

    public static void reset(Mob mob) {
        STATES.remove(mob);
    }

    public static void clearCaches() {
        STATES.clear();
        LAST_ASTAR.clear();
    }
}
