package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.Territory;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.ai.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Template footprint (unrotated) and entrance (local position and outward face, null if unknown). */
    public record Spec(int w, int d, int h, BlockPos entrance, Direction facing) {
    }

    /** Accepted site: origin (min corner of the rotated footprint), rotation ordinal and rotated footprint. */
    public record Site(BlockPos origin, int rotation, int w, int d) {
    }

    /** Per-call cache of Terrain.treeLog results (the leaf scan is expensive in forests). */
    private static Map<Long, Boolean> treeCache;

    /** One search call; returns the site or null. Uses and advances colony.houseSiteCursor. */
    public static Site step(ServerLevel level, Colony colony, Spec spec, boolean offline) {
        if (colony.houseSiteCursor < 0) return null;
        int r = colony.radius();
        long maxIndex = (2L * r + 1) * (2L * r + 1);
        int big = Math.max(spec.w(), spec.d());
        BlockPos core = colony.core();
        if (colony.houseSiteCursor == 0) colony.runtime.put(EDGE_KEY, Boolean.FALSE);
        treeCache = new HashMap<>();
        try {
            for (int i = 0; i < CANDIDATES_PER_CALL && colony.houseSiteCursor < maxIndex; i++) {
                int index = colony.houseSiteCursor++;
                int[] o = spiral(index);
                int dx = o[0], dz = o[1];
                int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                if (cheb < 8 || cheb > r - big) continue;
                int rotation = rotationFacing(spec, dx, dz);
                boolean quarter = rotation % 2 == 1;
                int w = quarter ? spec.d() : spec.w(), d = quarter ? spec.w() : spec.d();
                int x0 = core.getX() + dx, z0 = core.getZ() + dz;
                // The footprint (plus 4) must lie inside the territory.
                if (!Territory.containsBox(colony, x0, z0, x0 + w - 1, z0 + d - 1, 4)) continue;
                if (!Terrain.loaded(level, x0 - 4, z0 - 4, x0 + w + 3, z0 + d + 3)) continue;
                if (!Territory.containsBox(colony, x0, z0, x0 + w - 1, z0 + d - 1, 12)) colony.runtime.put(EDGE_KEY, Boolean.TRUE);
                BlockPos site = fit(level, colony, x0, z0, w, d, spec.h());
                if (site == null) continue;
                if (Fortifications.wallBlocksPlot(colony, x0, z0, w, d)) continue;
                List<BlockPos> road = Roads.route(level, colony, x0, z0, w, d, entranceDoor(spec, site, rotation, w, d));
                if (road == null) continue;
                if (!offline && !reachable(level, colony, x0, z0, w, d)) continue;
                Fortifications.onPlotAccepted(level, colony, x0, z0, w, d);
                Roads.store(colony, road, offline);
                colony.houseSiteCursor = -1;
                return new Site(site, rotation, w, d);
            }
        } finally {
            treeCache = null;
        }
        if (colony.houseSiteCursor >= maxIndex) colony.houseSiteCursor = -1;
        return null;
    }

    private static final String EDGE_KEY = "btv_site_edge";

    /** The last (or running) search pass evaluated loaded candidates at the edge of the search radius. */
    public static boolean passReachedEdge(Colony colony) {
        return Boolean.TRUE.equals(colony.runtime.get(EDGE_KEY));
    }

    /** Rotation that turns the entrance toward the core (0 without an entrance). */
    static int rotationFacing(Spec spec, int dx, int dz) {
        if (spec.facing() == null) return 0;
        Direction toCore = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.WEST : Direction.EAST) : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
        for (Rotation r : Rotation.values()) {
            if (r.rotate(spec.facing()) == toCore) return r.ordinal();
        }
        return 0;
    }

    /** Road start in front of the rotated entrance, as an offset from the origin (null without an entrance). */
    static int[] entranceDoor(Spec spec, BlockPos origin, int rotation, int w, int d) {
        if (spec.entrance() == null || spec.facing() == null) return null;
        Rotation r = Rotation.values()[rotation];
        BlockPos e = BuildPlan.toWorld(origin, r, w, d, spec.entrance());
        int ex = e.getX() - origin.getX(), ez = e.getZ() - origin.getZ();
        return switch (r.rotate(spec.facing())) {
            case NORTH -> new int[]{ex, -3};
            case SOUTH -> new int[]{ex, d + 2};
            case WEST -> new int[]{-3, ez};
            default -> new int[]{w + 2, ez};
        };
    }

    private static boolean treeLog(ServerLevel level, BlockPos p, BlockState s) {
        if (!s.is(BlockTags.LOGS)) return false;
        Map<Long, Boolean> cache = treeCache;
        if (cache == null) return Terrain.treeLog(level, p, s);
        return cache.computeIfAbsent(p.asLong(), k -> Terrain.treeLog(level, BlockPos.of(k), s));
    }

    /** Ground height for a footprint column (05 §2.3 step 3), or Integer.MIN_VALUE if rejected. */
    private static int groundY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 16 && p.getY() > level.getMinBuildHeight(); i++) {
            BlockState s = level.getBlockState(p);
            if (Fortifications.isWallBlock(level, p) || s.canBeReplaced() || s.is(BlockTags.LEAVES) || treeLog(level, p, s)) {
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
        // finished houses keep a 3-block gap
        BoundingBox near = new BoundingBox(x0 - 3, y - 4, z0 - 3, x0 + w + 2, y + h + 2, z0 + d + 2);
        ColonySavedData data = ColonyManager.data(level);
        for (LandPlot plot : data.land) {
            if (plot.intersects(b)) return null;
        }
        for (Colony c : data.colonies()) {
            for (ConstructionJob job : c.jobs) {
                if (HousePlanner.activePlot(job) ? job.box().intersects(b) : job.countsAsCompleted() && job.box().intersects(near)) return null;
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
                            || s.is(BlockTags.LEAVES) || treeLog(level, p, s) || Terrain.smallPlant(s);
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

    /**
     * Reachability test: a villager standing by the bell (else the builder nearest to it) can path to one of the
     * door points, so everyone who gathers at the bell can walk home. Unreachable sites are skipped; making them
     * reachable is the miner's terraforming (docs_v2/04-land.md).
     */
    static boolean reachable(ServerLevel level, Colony colony, int x0, int z0, int w, int d) {
        BlockPos c = colony.core();
        List<Villager> builders = level.getEntitiesOfClass(Villager.class, new AABB(c).inflate(16, 8, 16),
                v -> v.isAlive() && !v.isBaby() && ColonyManager.colonyOf(level, v) == colony && v.onGround());
        if (builders.isEmpty()) builders = builders(level, colony);
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
        return level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive() && !v.isBaby() && colony.roleOf(v.getUUID()) == ColonyRole.MASON);
    }
}
