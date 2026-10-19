package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.ai.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Plot site selection: square spiral, fit test and reachability (05 §2.3). */
public final class SiteSearch {
    public static final int CANDIDATES_PER_CALL = 48;

    private SiteSearch() {
    }

    /** Spiral index to (dx, dz). */
    public static int[] spiral(int index) {
        if (index == 0) return new int[]{0, 0};
        int k = (int) Math.ceil((Math.sqrt(index + 1) - 1) / 2);
        int side = 2 * k;
        int last = (2 * k + 1) * (2 * k + 1) - 1;
        int m = last - index;
        if (m < side) return new int[]{k - m, -k};
        if (m < 2 * side) return new int[]{-k, -k + (m - side)};
        if (m < 3 * side) return new int[]{-k + (m - 2 * side), k};
        return new int[]{k, k - (m - 3 * side)};
    }

    /** One search call; returns the plot origin or null. Uses and advances colony.houseSiteCursor. */
    public static BlockPos step(ServerLevel level, Colony colony, int w, int d, int h, boolean offline) {
        if (colony.houseSiteCursor < 0) return null;
        int r = Math.min(colony.radius(), ColonyConfig.get().village.expansion_radius_max);
        long maxIndex = (2L * r + 1) * (2L * r + 1);
        int big = Math.max(w, d);
        BlockPos core = colony.core();
        for (int i = 0; i < CANDIDATES_PER_CALL && colony.houseSiteCursor < maxIndex; i++) {
            int index = colony.houseSiteCursor++;
            int[] o = spiral(index);
            int dx = o[0], dz = o[1];
            int cheb = Math.max(Math.abs(dx), Math.abs(dz));
            if (cheb < 8 || cheb > r - big) continue;
            long lim = r - big - 4;
            if ((long) dx * dx + (long) dz * dz > lim * lim || lim < 0) continue;
            int x0 = core.getX() + dx, z0 = core.getZ() + dz;
            if (!Terrain.loaded(level, x0 - 4, z0 - 4, x0 + w + 3, z0 + d + 3)) continue;
            BlockPos site = fit(level, colony, x0, z0, w, d, h);
            if (site == null) continue;
            List<BlockPos> road = Roads.route(level, colony, x0, z0, w, d);
            if (road == null) continue;
            if (!offline && !reachable(level, colony, x0, z0, w, d)) continue;
            Fortifications.onPlotAccepted(level, colony, x0, z0, w, d);
            Roads.store(colony, road, offline);
            colony.houseSiteCursor = -1;
            return site;
        }
        if (colony.houseSiteCursor >= maxIndex) colony.houseSiteCursor = -1;
        return null;
    }

    /** Ground height for a footprint column (05 §2.3 step 3), or Integer.MIN_VALUE if rejected. */
    private static int groundY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 16 && p.getY() > level.getMinBuildHeight(); i++) {
            BlockState s = level.getBlockState(p);
            if (Fortifications.isWallBlock(level, p) || s.canBeReplaced() || s.is(BlockTags.LEAVES) || Terrain.treeLog(level, p, s)) {
                p.move(0, -1, 0);
            } else {
                break;
            }
        }
        BlockState g = level.getBlockState(p);
        if (Damage.isProtectedHousePiece(level, p)) return Integer.MIN_VALUE;
        if (g.is(Blocks.DIRT_PATH) || g.is(Blocks.FARMLAND) || !Terrain.natural(g)) return Integer.MIN_VALUE;
        if (level.getBlockState(p.above()).getBlock() instanceof CropBlock) return Integer.MIN_VALUE;
        return p.getY();
    }

    /** Fit test (05 §2.3); returns (x0, Y, z0) or null. */
    public static BlockPos fit(ServerLevel level, Colony colony, int x0, int z0, int w, int d, int h) {
        BlockPos pen = colony.livestockPen;
        if (pen != null && x0 <= pen.getX() + 8 && x0 + w - 1 >= pen.getX() - 8 && z0 <= pen.getZ() + 8 && z0 + d - 1 >= pen.getZ() - 8) {
            return null;
        }
        for (int x = x0 - 2; x <= x0 + w + 1; x++) {
            for (int z = z0 - 2; z <= z0 + d + 1; z++) {
                if (Terrain.isRoad(level.getBlockState(Terrain.roadGround(level, x, z)))) return null;
            }
        }
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                int y = groundY(level, x, z);
                if (y == Integer.MIN_VALUE) return null;
                min = Math.min(min, y);
                max = Math.max(max, y);
                if (max - min > 4) return null;
            }
        }
        int y = (min + max + 1) / 2;
        if (y > colony.core().getY() + 16) return null;
        BoundingBox b = new BoundingBox(x0 - 4, y - 4, z0 - 4, x0 + w + 3, y + h + 2, z0 + d + 3);
        ColonySavedData data = ColonyManager.data(level);
        for (LandPlot plot : data.land) {
            if (plot.intersects(b)) return null;
        }
        for (Colony c : data.colonies()) {
            for (ConstructionJob job : c.jobs) {
                if (HousePlanner.activePlot(job) && job.box().intersects(b)) return null;
            }
        }
        for (BlockPos m : colony.mineRoute) {
            if (m.getY() >= y - 5 && m.getY() <= y + h + 2 && b.isInside(m)) return null;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                for (int dy = -4; dy <= h + 2; dy++) {
                    p.set(x0 + x, y + dy, z0 + z);
                    if (data.isPlayerBlock(p) || level.getBlockEntity(p) != null) return null;
                    BlockState s = level.getBlockState(p);
                    if (!s.getFluidState().isEmpty()) return null;
                    boolean ok = Fortifications.isWallBlock(level, p) || s.isAir() || s.canBeReplaced()
                            || (Terrain.natural(s) && !s.is(Blocks.DIRT_PATH) && !s.is(Blocks.FARMLAND))
                            || s.is(BlockTags.LEAVES) || Terrain.treeLog(level, p, s) || Terrain.smallPlant(s);
                    if (!ok) return null;
                }
            }
        }
        if (ColonyConfig.get().roles.builder_respects_player_builds && beaconNearBox(level, b)) return null;
        return new BlockPos(x0, y, z0);
    }

    /** A beacon within 32 blocks (x/z) of a box. */
    public static boolean beaconNearBox(ServerLevel level, BoundingBox b) {
        int minCx = (b.minX() - 32) >> 4, maxCx = (b.maxX() + 32) >> 4;
        int minCz = (b.minZ() - 32) >> 4, maxCz = (b.maxZ() + 32) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (var be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (be instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity) {
                        BlockPos q = be.getBlockPos();
                        if (q.getX() >= b.minX() - 32 && q.getX() <= b.maxX() + 32 && q.getZ() >= b.minZ() - 32 && q.getZ() <= b.maxZ() + 32) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Reachability test: the builder nearest to the core can path to one of the door points. */
    static boolean reachable(ServerLevel level, Colony colony, int x0, int z0, int w, int d) {
        List<Villager> builders = builders(level, colony);
        if (builders.isEmpty()) return false;
        Set<BlockPos> doors = new LinkedHashSet<>();
        for (int[] o : Roads.doorOffsets(w, d)) {
            int x = x0 + o[0], z = z0 + o[1];
            if (!Terrain.loaded(level, x, z)) continue;
            BlockPos p = Terrain.roadGround(level, x, z).above();
            if (Nav.standable(level, p)) doors.add(p);
        }
        if (doors.isEmpty() || !PathBudget.tryAcquire(level)) return false;
        BlockPos core = colony.core();
        Villager v = builders.stream().min(Comparator.comparingDouble(b -> b.distanceToSqr(core.getX() + 0.5, core.getY(), core.getZ() + 0.5))).get();
        Path path = Paths.find(v, doors);
        return path != null && path.canReach();
    }

    /** Alive adult BUILDER villagers inside the colony area box. */
    public static List<Villager> builders(ServerLevel level, Colony colony) {
        BlockPos c = colony.core();
        int r = colony.radius();
        AABB box = new AABB(c.getX() - r, level.getMinBuildHeight(), c.getZ() - r, c.getX() + r + 1, level.getMaxBuildHeight(), c.getZ() + r + 1);
        return level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby() && colony.roleOf(v.getUUID()) == ColonyRole.BUILDER);
    }
}
