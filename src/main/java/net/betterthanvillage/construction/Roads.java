package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Road routing and the colony road queue (05 §3.1, §3.2). */
public final class Roads {
    public static final int MAX_DEQUEUES = 1800;

    private Roads() {
    }

    public static boolean isRoad(ServerLevel level, BlockPos pos) {
        return Terrain.isRoad(level.getBlockState(pos));
    }

    /** Valid road node at a ground position (05 §3.1). */
    public static boolean validNode(ServerLevel level, Colony colony, BlockPos pos) {
        if (!ColonyManager.terrainChangesAllowed(level) || !level.isLoaded(pos)) return false;
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos) || Damage.isProtectedHousePiece(level, pos)) return false;
        BlockState g = level.getBlockState(pos);
        boolean road = Terrain.isRoad(g);
        if (!road && (data.isPlayerBlock(pos) || data.isPlayerBlock(pos.above()))) return false;
        BlockState up = level.getBlockState(pos.above());
        if (g.is(Blocks.FARMLAND) || up.getBlock() instanceof CropBlock) return false;
        if (!(Terrain.natural(g) || road) || level.getBlockEntity(pos) != null) return false;
        if (!g.getFluidState().isEmpty() || !up.getFluidState().isEmpty()) return false;
        if (!(up.isAir() || up.canBeReplaced() || Fortifications.isWallBlock(level, pos.above()))) return false;
        BlockPos up2 = pos.above(2);
        if (!(level.getBlockState(up2).isAir() || Fortifications.isWallBlock(level, up2))) return false;
        for (ConstructionJob job : colony.jobs) {
            BoundingBox b = job.box();
            if (pos.getX() >= b.minX() - 2 && pos.getX() <= b.maxX() + 2 && pos.getZ() >= b.minZ() - 2 && pos.getZ() <= b.maxZ() + 2) {
                return false;
            }
        }
        return true;
    }

    /** The 4 door points (x, z) of a footprint: (w/2,-3), (w/2,d+2), (-3,d/2), (w+2,d/2). */
    public static int[][] doorOffsets(int w, int d) {
        return new int[][]{{w / 2, -3}, {w / 2, d + 2}, {-3, d / 2}, {w + 2, d / 2}};
    }

    /**
     * BFS from the new footprint's door points to the road network (05 §3.1). Returns the missing
     * road nodes listed from the network outward, or null when no path exists. A preferred door
     * offset (in front of the entrance) is tried alone first.
     */
    public static List<BlockPos> route(ServerLevel level, Colony colony, int x0, int z0, int w, int d, int[] preferred) {
        if (preferred != null) {
            List<BlockPos> path = bfs(level, colony, x0, z0, w, d, new int[][]{preferred});
            if (path != null) return path;
        }
        return bfs(level, colony, x0, z0, w, d, doorOffsets(w, d));
    }

    private static List<BlockPos> bfs(ServerLevel level, Colony colony, int x0, int z0, int w, int d, int[][] doors) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        for (int[] o : doors) {
            int x = x0 + o[0], z = z0 + o[1];
            if (!Terrain.loaded(level, x, z)) continue;
            BlockPos p = Terrain.roadGround(level, x, z);
            if (!parent.containsKey(p) && validNode(level, colony, p)) {
                parent.put(p, p);
                queue.add(p);
            }
        }
        boolean noPlots = colony.jobs.isEmpty();
        BlockPos core = colony.core();
        int dequeues = 0;
        while (!queue.isEmpty() && dequeues < MAX_DEQUEUES) {
            BlockPos cur = queue.poll();
            dequeues++;
            boolean goal = isRoad(level, cur) || (noPlots && Math.abs(cur.getX() - core.getX()) <= 2
                    && Math.abs(cur.getZ() - core.getZ()) <= 2 && Math.abs(cur.getY() + 1 - core.getY()) <= 1);
            if (goal) {
                List<BlockPos> out = new ArrayList<>();
                BlockPos p = cur;
                while (true) {
                    if (!isRoad(level, p)) out.add(p);
                    BlockPos up = parent.get(p);
                    if (up == null || up.equals(p)) break;
                    p = up;
                }
                return out;
            }
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cur.getX() + dir[0], nz = cur.getZ() + dir[1];
                if (nx >= x0 - 2 && nx <= x0 + w + 1 && nz >= z0 - 2 && nz <= z0 + d + 1) continue;
                if (Math.abs(nx - x0) > 32 || Math.abs(nz - z0) > 32 || !Terrain.loaded(level, nx, nz)) continue;
                BlockPos n = Terrain.roadGround(level, nx, nz);
                if (Math.abs(n.getY() - cur.getY()) > 1 || parent.containsKey(n)) continue;
                if (!validNode(level, colony, n)) continue;
                parent.put(n, cur);
                queue.add(n);
            }
        }
        return null;
    }

    /** Stores a road path: offline appends missing nodes, normal replaces the queue and resets counters. */
    public static void store(Colony colony, List<BlockPos> path, boolean offline) {
        if (offline) {
            for (BlockPos p : path) {
                if (!colony.roadQueue.contains(p)) colony.roadQueue.add(p);
            }
        } else {
            colony.roadQueue.clear();
            colony.roadQueue.addAll(path);
            colony.roadStallTicks = 0;
            colony.skippedRoadSegments = 0;
        }
        colony.roadAttemptHead = colony.roadQueue.isEmpty() ? null : colony.roadQueue.get(0);
    }

    /** Removes the head; counts a skipped segment when failed. */
    public static void pop(Colony colony, boolean failed) {
        if (!colony.roadQueue.isEmpty()) colony.roadQueue.remove(0);
        colony.roadStallTicks = 0;
        if (failed) colony.skippedRoadSegments++;
        colony.roadAttemptHead = colony.roadQueue.isEmpty() ? null : colony.roadQueue.get(0);
    }

    /** Stall bookkeeping: +10 per distinct tick (max 1200), reset when the head changes. */
    public static void addStall(Colony colony, long now) {
        BlockPos head = colony.roadQueue.isEmpty() ? null : colony.roadQueue.get(0);
        if (head == null) return;
        if (!head.equals(colony.roadAttemptHead)) {
            colony.roadAttemptHead = head;
            colony.roadStallTicks = 0;
        }
        Long last = (Long) colony.runtime.get("btv_road_stall_tick");
        if (last == null || last != now) {
            colony.runtime.put("btv_road_stall_tick", now);
            colony.roadStallTicks = Math.min(1200, colony.roadStallTicks + 10);
        }
    }
}
