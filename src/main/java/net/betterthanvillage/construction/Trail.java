package net.betterthanvillage.construction;

import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A short way on foot that may change the ground (docs_v2/04-land.md §Terraforming and demolition): an A* search
 * over the places a villager can stand, from where its walk ended to a goal (a doorway, a bed's side). A step may
 * dig the two cells a villager passes through (natural ground is cheap, a house's own blocks dear), and fill a
 * missing floor with one block. Doors and gates count as open. The result is the set of blocks to take down
 * (false) and to fill (true); empty if the way needs no change.
 */
final class Trail {
    private static final int MAX_NODES = 6000;
    private static final int MARGIN = 8;
    /** Cost of a step with no change; changes add their own cost. */
    private static final int STEP = 1;
    private static final int DIG_NATURAL = 2;
    private static final int DIG_OTHER = 4;
    private static final int DIG_HOUSE = 8;
    private static final int FILL = 3;

    private record Node(BlockPos pos, int cost, int estimate) {
    }

    private Trail() {
    }

    static Map<BlockPos, Boolean> plan(ServerLevel level, net.minecraft.world.entity.Mob walker, BlockPos start, List<BlockPos> goals) {
        if (goals.isEmpty()) return null;
        int minX = start.getX(), maxX = start.getX(), minY = start.getY(), maxY = start.getY(), minZ = start.getZ(), maxZ = start.getZ();
        for (BlockPos g : goals) {
            minX = Math.min(minX, g.getX());
            maxX = Math.max(maxX, g.getX());
            minY = Math.min(minY, g.getY());
            maxY = Math.max(maxY, g.getY());
            minZ = Math.min(minZ, g.getZ());
            maxZ = Math.max(maxZ, g.getZ());
        }
        if (maxX - minX > 48 || maxZ - minZ > 48) return null;
        minX -= MARGIN;
        maxX += MARGIN;
        minZ -= MARGIN;
        maxZ += MARGIN;
        minY -= 4;
        maxY += 4;
        Map<BlockPos, Integer> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Map<BlockPos, Boolean>> stepEdits = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Integer.compare(a.cost() + a.estimate(), b.cost() + b.estimate()));
        best.put(start, 0);
        open.add(new Node(start, 0, estimate(start, goals)));
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll();
            if (cur.cost() > best.getOrDefault(cur.pos(), Integer.MAX_VALUE)) continue;
            expanded++;
            if (goals.contains(cur.pos())) return collect(cur.pos(), parent, stepEdits);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = cur.pos().relative(dir).above(dy);
                    if (n.getX() < minX || n.getX() > maxX || n.getZ() < minZ || n.getZ() > maxZ || n.getY() < minY || n.getY() > maxY) continue;
                    if (!level.isLoaded(n)) continue;
                    Map<BlockPos, Boolean> edits = new LinkedHashMap<>();
                    int extra = step(level, walker, cur.pos(), n, dy, edits);
                    if (extra < 0) continue;
                    int cost = cur.cost() + STEP + extra;
                    if (cost >= best.getOrDefault(n, Integer.MAX_VALUE)) continue;
                    best.put(n, cost);
                    parent.put(n, cur.pos());
                    stepEdits.put(n, edits);
                    open.add(new Node(n, cost, estimate(n, goals)));
                }
            }
        }
        return null;
    }

    private static int estimate(BlockPos p, List<BlockPos> goals) {
        int e = Integer.MAX_VALUE;
        for (BlockPos g : goals) {
            e = Math.min(e, Math.abs(g.getX() - p.getX()) + Math.abs(g.getZ() - p.getZ()) + Math.abs(g.getY() - p.getY()));
        }
        return e;
    }

    /** The changes a step from cur to n needs, and their cost; -1 if the step is impossible. */
    private static int step(ServerLevel level, net.minecraft.world.entity.Mob walker, BlockPos cur, BlockPos n, int dy, Map<BlockPos, Boolean> edits) {
        int cost = 0;
        // The floor: a block under the feet, or one block of fill on solid ground.
        if (!floor(level, n.below())) {
            if (!floor(level, n.below(2)) || !fillable(level, n.below())) return -1;
            edits.put(n.below().immutable(), Boolean.TRUE);
            cost += FILL;
        }
        // The body: feet and head; going up, the head room above the start too; going down, the cell above the head.
        List<BlockPos> body = new ArrayList<>(List.of(n, n.above()));
        if (dy > 0) body.add(cur.above(2));
        if (dy < 0) body.add(n.above(2));
        for (BlockPos p : body) {
            int dig = dig(level, p);
            if (dig < 0) return -1;
            if (dig > 0) {
                edits.put(p.immutable(), Boolean.FALSE);
                cost += dig;
            }
        }
        // Where the cells look free, the vanilla path type decides (leaves, fences, berry bushes, ...): what makes
        // it impassable is dug as well.
        if (edits.isEmpty()) {
            var type = net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(walker, n);
            if (walker.getPathfindingMalus(type) < 0) {
                boolean any = false;
                for (BlockPos p : new BlockPos[]{n, n.above(), n.below()}) {
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || st.getBlock() instanceof DoorBlock || (p.equals(n.below()) && !st.is(net.minecraft.tags.BlockTags.LEAVES)
                            && !(st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock))) continue;
                    int dig = digAny(level, p);
                    if (dig < 0) return -1;
                    edits.put(p.immutable(), Boolean.FALSE);
                    cost += dig;
                    any = true;
                }
                if (!any) return -1;
                if (edits.containsKey(n.below()) && !floor(level, n.below(2))) return -1;
            }
        }
        return cost;
    }

    private static boolean floor(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return !st.getCollisionShape(level, p).isEmpty() && st.getFluidState().isEmpty();
    }

    private static boolean fillable(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        ColonySavedData data = ColonyManager.data(level);
        return st.canBeReplaced() && st.getFluidState().isEmpty() && !data.inLand(p) && !data.isPlayerBlock(p);
    }

    /** 0 if a villager passes through the cell, the cost of digging it, or -1 if it must stay. */
    private static int dig(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (!st.getFluidState().isEmpty()) return -1;
        if (st.getBlock() instanceof DoorBlock || st.getBlock() instanceof FenceGateBlock) return 0;
        if (st.getCollisionShape(level, p).isEmpty()) return 0;
        ColonySavedData data = ColonyManager.data(level);
        if (st.getBlock() instanceof BedBlock || level.getBlockEntity(p) != null || st.getDestroySpeed(level, p) < 0) return -1;
        if (data.inLand(p) || data.isPlayerBlock(p) || Fortifications.isWallBlock(level, p)) return -1;
        if (Terrain.natural(st) || st.is(net.minecraft.tags.BlockTags.LEAVES)) return DIG_NATURAL;
        return Damage.isProtectedHousePiece(level, p) ? DIG_HOUSE : DIG_OTHER;
    }

    /** As dig, for a block that may be passable by collision (a bush, a carpet): its cost, or -1 if it must stay. */
    private static int digAny(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (!st.getFluidState().isEmpty() || st.getBlock() instanceof BedBlock || level.getBlockEntity(p) != null || st.getDestroySpeed(level, p) < 0) return -1;
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(p) || data.isPlayerBlock(p) || Fortifications.isWallBlock(level, p)) return -1;
        if (Terrain.natural(st) || st.is(net.minecraft.tags.BlockTags.LEAVES)) return DIG_NATURAL;
        return Damage.isProtectedHousePiece(level, p) ? DIG_HOUSE : DIG_OTHER;
    }

    private static Map<BlockPos, Boolean> collect(BlockPos end, Map<BlockPos, BlockPos> parent, Map<BlockPos, Map<BlockPos, Boolean>> stepEdits) {
        Map<BlockPos, Boolean> out = new LinkedHashMap<>();
        BlockPos p = end;
        while (parent.containsKey(p)) {
            Map<BlockPos, Boolean> e = stepEdits.get(p);
            if (e != null) out.putAll(e);
            p = parent.get(p);
        }
        return out;
    }
}
