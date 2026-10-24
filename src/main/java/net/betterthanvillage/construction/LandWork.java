package net.betterthanvillage.construction;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.LandWarning;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.defense.RaidShelter;
import net.betterthanvillage.society.Deeds;
import net.betterthanvillage.society.LandNotices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Land work of the miner (docs_v2/04-land.md §Terraforming and demolition), planned here and done by
 * {@link LandWorkTask}:
 * <ul>
 *   <li><b>Demolition.</b> Player blocks in the territory outside the player's own property are illegal. Once the
 *   elder's notice was delivered and its grace period is over, they are torn down to the ground; what they drop goes
 *   to the treasury as confiscated goods. Nothing is condemned while the village fights (players then build
 *   defences).</li>
 *   <li><b>Pits.</b> Dry holes at least two blocks deep within four blocks of a house, road or field are filled up
 *   to the ground around them, at most six blocks per column, with dirt or cobblestone of the treasury.</li>
 *   <li><b>Depressions.</b> Wider and deeper dry holes among the houses (a crater, a sinkhole, an old open trench)
 *   are closed up to the level where they would spill over when at most {@link #MAX_DEPRESSION_FILL} blocks deep.
 *   Pits and depressions alike get a lid of the top {@link #LID} layers, laid from the edge inward. A mine
 *   whose entrance lies in the settlement is given up and its trench filled; the miner opens a new one outside.</li>
 * </ul>
 */
public final class LandWork {
    /** Pits are filled this far from houses, roads and fields. */
    static final int PIT_REACH = 4;
    /** A hole counts as a pit from this depth. */
    private static final int PIT_DEPTH = 2;
    /** At most this many blocks of fill per column. */
    static final int MAX_FILL = 6;
    /** Anchors (house edges, road and field blocks) checked per planning step. */
    private static final int ANCHORS_PER_STEP = 48;
    private static final int MAX_PITS = 64;
    /** Columns of depressions planned at most (they come on top of the pits). */
    private static final int MAX_DEPRESSION_COLUMNS = 1024;
    /** Depressions deeper than this are left as they are (a ravine, a quarry). */
    static final int MAX_DEPRESSION_FILL = 16;
    /** A hole is filled this many layers deep below its rim: a lid, not down to its floor. */
    static final int LID = 3;
    /** A depression counts from this depth below its spill level. */
    private static final int DEPRESSION_DEPTH = 2;
    /** Depressions touch the settlement: they come within this many blocks of a house (storage, pen, plot). */
    private static final int SETTLEMENT_REACH = Fortifications.HOUSE_MARGIN + 3;
    /** The area searched for depressions reaches at most this far from the bell. */
    private static final int DEPRESSION_RANGE = 112;
    private static final long DEPRESSION_INTERVAL = 2400;
    private static final long PLAN_INTERVAL = 200;
    private static final String STATE_KEY = "btv_land_work";

    /** Runtime state: what to tear down and which columns to fill (column -> ground level to fill up to). */
    static final class State {
        final Set<BlockPos> demolition = new LinkedHashSet<>();
        final java.util.Map<BlockPos, Integer> pits = new java.util.LinkedHashMap<>();
        /** Doorway fixes for unreachable houses (Access): true places fill, false takes the block down. */
        final java.util.Map<BlockPos, Boolean> access = new java.util.LinkedHashMap<>();
        /** Columns of depressions (column -> level to fill up to), kept apart from the pits' limit. */
        final java.util.Map<BlockPos, Integer> depressions = new java.util.LinkedHashMap<>();
        long nextPlan;
        long nextAccess;
        long nextDepressions;
        int anchorCursor;
    }

    private LandWork() {
    }

    static State state(Colony colony) {
        return colony.runtime(STATE_KEY, State::new);
    }

    public static int demolitionCount(Colony colony) {
        return state(colony).demolition.size();
    }

    public static int pitCount(Colony colony) {
        return state(colony).pits.size() + state(colony).depressions.size();
    }

    /** The column is planned to be filled (the miner opens no mine there). */
    public static boolean plannedFill(Colony colony, int x, int z) {
        BlockPos column = new BlockPos(x, 0, z);
        return state(colony).pits.containsKey(column) || state(colony).depressions.containsKey(column);
    }

    /** Planning step (colony tick). */
    public static void tick(ServerLevel level, Colony colony) {
        State s = state(colony);
        long now = level.getGameTime();
        if (now < s.nextPlan || !ColonyManager.terrainChangesAllowed(level)) return;
        s.nextPlan = now + PLAN_INTERVAL;
        planDemolition(level, colony, s, now);
        planPits(level, colony, s);
        s.depressions.keySet().removeIf(p -> !level.isLoaded(p) || ground(level, p.getX(), p.getZ()) >= s.depressions.get(p));
        if (now >= s.nextDepressions) {
            s.nextDepressions = now + DEPRESSION_INTERVAL;
            planDepressions(level, colony, s);
        }
        if (now >= s.nextAccess) {
            s.nextAccess = now + Access.CHECK_INTERVAL;
            Access.plan(level, colony, s, now);
        }
    }

    public static int accessCount(Colony colony) {
        return state(colony).access.size();
    }

    /** The nearest doorway fix for a miner at from (blocks to take down first), or null. */
    static java.util.Map.Entry<BlockPos, Boolean> nextAccess(ServerLevel level, Colony colony, BlockPos from) {
        State s = state(colony);
        s.access.entrySet().removeIf(e -> e.getValue() ? !level.getBlockState(e.getKey()).canBeReplaced() : level.getBlockState(e.getKey()).isAir());
        return s.access.entrySet().stream().filter(e -> level.isLoaded(e.getKey()))
                .min(Comparator.<java.util.Map.Entry<BlockPos, Boolean>>comparingInt(e -> e.getValue() ? 1 : 0)
                        .thenComparingDouble(e -> e.getKey().distSqr(from)))
                .orElse(null);
    }

    static void accessDone(Colony colony, BlockPos pos) {
        state(colony).access.remove(pos);
    }

    // ---------------------------------------------------------------- demolition

    private static void planDemolition(ServerLevel level, Colony colony, State s, long now) {
        ColonySavedData data = ColonyManager.data(level);
        s.demolition.removeIf(p -> !data.isPlayerBlock(p) || !level.isLoaded(p) || level.getBlockState(p).isAir());
        if (colony.recentThreat(now, 200) || RaidShelter.isActive(level, colony)) {
            s.demolition.clear();
            return;
        }
        BlockPos core = colony.core();
        int r = colony.radius();
        for (int cx = (core.getX() - r) >> 4; cx <= (core.getX() + r) >> 4; cx++) {
            for (int cz = (core.getZ() - r) >> 4; cz <= (core.getZ() + r) >> 4; cz++) {
                LongSet set = data.playerBlocksInChunk(cx, cz);
                if (set == null) continue;
                for (long key : set) {
                    BlockPos p = BlockPos.of(key);
                    if (s.demolition.contains(p) || !level.isLoaded(p) || level.getBlockState(p).isAir() || !colony.containsXZ(p)) continue;
                    UUID owner = data.blockOwner(p);
                    if (owner == null || Deeds.ownsPosition(level, owner, p) || !warned(data, colony, owner, now)) continue;
                    s.demolition.add(p);
                }
            }
        }
    }

    /** The owner was given the elder's notice in this village and its grace period is over. */
    private static boolean warned(ColonySavedData data, Colony colony, UUID owner, long now) {
        for (LandWarning w : data.landWarnings) {
            if (owner.equals(w.player) && colony.id().equals(w.colony) && w.delivered >= 0 && now - w.delivered >= LandNotices.GRACE) return true;
        }
        return false;
    }

    /** The next block to tear down for a miner at from: the top of the nearest condemned column. */
    static BlockPos nextDemolition(ServerLevel level, Colony colony, BlockPos from) {
        State s = state(colony);
        return s.demolition.stream()
                .filter(level::isLoaded)
                .min(Comparator.<BlockPos>comparingDouble(p -> Math.hypot(p.getX() - from.getX(), p.getZ() - from.getZ()) - p.getY() * 0.01))
                .map(p -> topOf(s, p))
                .orElse(null);
    }

    private static BlockPos topOf(State s, BlockPos p) {
        BlockPos top = p;
        while (s.demolition.contains(top.above())) top = top.above();
        return top;
    }

    static void demolished(Colony colony, BlockPos pos) {
        state(colony).demolition.remove(pos);
    }

    // ---------------------------------------------------------------- pits

    private static void planPits(ServerLevel level, Colony colony, State s) {
        s.pits.keySet().removeIf(p -> !level.isLoaded(p) || ground(level, p.getX(), p.getZ()) >= s.pits.get(p));
        if (s.pits.size() >= MAX_PITS) return;
        List<BlockPos> anchors = anchors(level, colony);
        if (anchors.isEmpty()) return;
        for (int i = 0; i < ANCHORS_PER_STEP && s.pits.size() < MAX_PITS; i++) {
            s.anchorCursor = (s.anchorCursor + 1) % anchors.size();
            BlockPos a = anchors.get(s.anchorCursor);
            if (!level.isLoaded(a)) continue;
            for (int dx = -PIT_REACH; dx <= PIT_REACH; dx++) {
                for (int dz = -PIT_REACH; dz <= PIT_REACH; dz++) {
                    int x = a.getX() + dx, z = a.getZ() + dz;
                    BlockPos column = new BlockPos(x, 0, z);
                    if (s.pits.containsKey(column) || !level.isLoaded(column)) continue;
                    int fillTo = pitRim(level, colony, x, z);
                    if (fillTo != Integer.MIN_VALUE) s.pits.put(column, fillTo);
                }
            }
        }
    }

    // ---------------------------------------------------------------- depressions

    /**
     * Finds the depressions of the settlement: on the ground level of every column (houses, walls and plants looked
     * through, water as it stands), a priority flood from the edges of the area gives the level each column would
     * hold water up to; columns at least {@link #DEPRESSION_DEPTH} below it, in a hole that comes near a house, are
     * filled up to it.
     */
    private static void planDepressions(ServerLevel level, Colony colony, State s) {
        BlockPos core = colony.core();
        int range = Math.min(colony.radius(), DEPRESSION_RANGE);
        int x0 = core.getX() - range, z0 = core.getZ() - range, w = 2 * range + 1;
        int n = w * w;
        int[] h = new int[n];
        boolean[] outlet = new boolean[n];
        for (int i = 0; i < n; i++) {
            int x = x0 + i % w, z = z0 + i / w;
            if (!level.isLoaded(new BlockPos(x, 0, z))) {
                outlet[i] = true;
                continue;
            }
            int y = ground(level, x, z);
            for (int k = 0; k < 24; k++) {
                BlockState st = level.getBlockState(new BlockPos(x, y, z));
                if (!st.getFluidState().isEmpty()) {
                    outlet[i] = true;
                    break;
                }
                if (Terrain.natural(st) || Terrain.isRoad(st) || st.is(net.minecraft.world.level.block.Blocks.FARMLAND)) break;
                y--;
            }
            h[i] = y;
        }
        // Priority flood: the lowest open cell spills into its neighbours.
        int[] fill = h.clone();
        boolean[] seen = new boolean[n];
        java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        for (int i = 0; i < n; i++) {
            int x = i % w, z = i / w;
            if (outlet[i] || x == 0 || z == 0 || x == w - 1 || z == w - 1) {
                seen[i] = true;
                queue.add(new long[]{fill[i], i});
            }
        }
        int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
        while (!queue.isEmpty()) {
            int i = (int) queue.poll()[1];
            for (int d = 0; d < 4; d++) {
                int x = i % w + dx[d], z = i / w + dz[d];
                if (x < 0 || z < 0 || x >= w || z >= w) continue;
                int j = z * w + x;
                if (seen[j]) continue;
                seen[j] = true;
                fill[j] = Math.max(h[j], fill[i]);
                queue.add(new long[]{fill[j], j});
            }
        }
        // The mine stays where its entrance lies outside the settlement; inside, it is given up and filled.
        java.util.Set<Long> mine = new java.util.HashSet<>();
        if (!colony.mineRoute.isEmpty()) {
            BlockPos entrance = colony.mineRoute.get(0);
            if (Fortifications.insideWallLine(level, colony, entrance.getX(), entrance.getZ())) {
                colony.mineRoute.clear();
                ColonyManager.markDirty(level);
            } else {
                for (BlockPos p : colony.mineRoute.subList(0, Math.min(24, colony.mineRoute.size()))) {
                    for (int ox = -2; ox <= 2; ox++) {
                        for (int oz = -2; oz <= 2; oz++) mine.add(BlockPos.asLong(p.getX() + ox, 0, p.getZ() + oz));
                    }
                }
            }
        }
        // Holes: connected columns below their spill level; the ones near the houses and not too deep are filled.
        boolean[] done = new boolean[n];
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        for (int start = 0; start < n && s.depressions.size() < MAX_DEPRESSION_COLUMNS; start++) {
            if (done[start] || fill[start] - h[start] < DEPRESSION_DEPTH) continue;
            List<Integer> hole = new ArrayList<>();
            boolean near = false;
            int deepest = 0;
            stack.push(start);
            done[start] = true;
            while (!stack.isEmpty()) {
                int i = stack.pop();
                hole.add(i);
                int x = x0 + i % w, z = z0 + i / w;
                deepest = Math.max(deepest, fill[i] - h[i]);
                if (!near && (Fortifications.insideSettlement(level, colony, x, z, SETTLEMENT_REACH)
                        || Fortifications.insideWallLine(level, colony, x, z))) {
                    near = true;
                }
                for (int d = 0; d < 4; d++) {
                    int cx = i % w + dx[d], cz = i / w + dz[d];
                    if (cx < 0 || cz < 0 || cx >= w || cz >= w) continue;
                    int j = cz * w + cx;
                    if (!done[j] && fill[j] - h[j] >= 1) {
                        done[j] = true;
                        stack.push(j);
                    }
                }
            }
            if (!near || deepest > MAX_DEPRESSION_FILL) continue;
            for (int i : hole) {
                int x = x0 + i % w, z = z0 + i / w;
                if (fill[i] <= h[i] || mine.contains(BlockPos.asLong(x, 0, z))) continue;
                if (fillable(level, colony, x, z, fill[i])) s.depressions.put(new BlockPos(x, 0, z), fill[i]);
            }
        }
    }

    /**
     * A column can be filled up to the level: inside the territory, natural dry ground (or a path) at the top, and the
     * cells up to the level are free, the village's to change and nobody's property.
     */
    private static boolean fillable(ServerLevel level, Colony colony, int x, int z, int to) {
        if (!colony.containsXZ(new BlockPos(x, 0, z))) return false;
        int g = ground(level, x, z);
        BlockPos floor = new BlockPos(x, g, z);
        BlockState below = level.getBlockState(floor);
        // A path that runs down into the hole is buried with it.
        if (!level.getFluidState(floor).isEmpty() || !level.getFluidState(floor.above()).isEmpty()
                || !(Terrain.natural(below) || Terrain.isRoad(below))) {
            return false;
        }
        for (int y = g + 1; y <= to; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getBlockState(p).canBeReplaced() || Protection.playerProperty(level, p) || !Protection.terrainModifiable(level, p)) {
                return false;
            }
        }
        return true;
    }

    /** Houses (their edges), road blocks and fields of the village: what pits are dangerous next to. */
    private static List<BlockPos> anchors(ServerLevel level, Colony colony) {
        List<BlockPos> out = new ArrayList<>();
        for (ConstructionJob job : colony.jobs) {
            if (!job.isComplete()) continue;
            BoundingBox b = job.box();
            for (int x = b.minX(); x <= b.maxX(); x += 3) {
                out.add(new BlockPos(x, b.minY(), b.minZ()));
                out.add(new BlockPos(x, b.minY(), b.maxZ()));
            }
            for (int z = b.minZ(); z <= b.maxZ(); z += 3) {
                out.add(new BlockPos(b.minX(), b.minY(), z));
                out.add(new BlockPos(b.maxX(), b.minY(), z));
            }
        }
        int i = 0;
        for (BlockPos p : colony.farmland) {
            if (i++ % 5 == 0) out.add(p);
        }
        for (BlockPos p : colony.roadQueue) out.add(p);
        if (colony.hallBed != null) out.add(colony.hallBed);
        for (var h : colony.households.values()) {
            if (h.bed != null) out.add(h.bed);
        }
        out.add(colony.core());
        return out;
    }

    /**
     * The level to fill a dry pit at the column up to, or MIN_VALUE: the column's ground lies at least two blocks
     * below the ground two blocks away on both sides (along x or along z), it is natural ground, inside the
     * territory and nobody's property, and the fill is at most six blocks.
     */
    private static int pitRim(ServerLevel level, Colony colony, int x, int z) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!colony.containsXZ(column)) return Integer.MIN_VALUE;
        int g = ground(level, x, z);
        int rimX = Math.min(ground(level, x - 2, z), ground(level, x + 2, z));
        int rimZ = Math.min(ground(level, x, z - 2), ground(level, x, z + 2));
        int rim = Math.max(rimX, rimZ);
        if (rim - g < PIT_DEPTH || rim - g > MAX_FILL) return Integer.MIN_VALUE;
        BlockPos floor = new BlockPos(x, g, z);
        BlockState below = level.getBlockState(floor);
        if (!level.getFluidState(floor.above()).isEmpty() || !level.getFluidState(floor).isEmpty()) return Integer.MIN_VALUE;
        if (!(below.is(BlockTags.DIRT) || below.is(BlockTags.BASE_STONE_OVERWORLD) || below.is(BlockTags.SAND) || below.is(net.minecraft.world.level.block.Blocks.GRAVEL))) {
            return Integer.MIN_VALUE;
        }
        for (int y = g + 1; y <= rim; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getBlockState(p).canBeReplaced() || Protection.playerProperty(level, p) || !Protection.terrainModifiable(level, p)) {
                return Integer.MIN_VALUE;
            }
        }
        return rim;
    }

    /** Top solid block of the column (leaves ignored). */
    static int ground(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /**
     * The next cell to fill for a miner at from, or null: the lowest open cell of the nearest column that a block can
     * be set against (the pit's wall, the ground or fill already in place). A hole is not filled to its floor: only
     * its top {@link #LID} layers are, so a deep hole is closed by a lid that grows from its edge inward, each block
     * resting against the last, the filled ground carrying the miner farther in.
     */
    static BlockPos nextFill(ServerLevel level, Colony colony, BlockPos from) {
        State s = state(colony);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        List<BlockPos> loose = new ArrayList<>();
        for (var map : List.of(s.pits, s.depressions)) {
            for (var it = map.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                BlockPos c = e.getKey();
                if (!level.isLoaded(c)) continue;
                int to = e.getValue();
                int g = ground(level, c.getX(), c.getZ());
                if (g >= to) {
                    it.remove();
                    continue;
                }
                BlockPos cell = null;
                for (int y = Math.max(g + 1, to - LID + 1); y <= to && cell == null; y++) {
                    BlockPos p = new BlockPos(c.getX(), y, c.getZ());
                    if (level.getBlockState(p).canBeReplaced() && attached(level, p)) cell = p;
                }
                if (cell == null) {
                    loose.add(c);
                    continue;
                }
                double score = Math.hypot(c.getX() - from.getX(), c.getZ() - from.getZ());
                if (score < bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
        }
        // Nothing left to set a block against: the rest of the lid would hang in the air.
        if (best == null) {
            for (BlockPos c : loose) {
                s.pits.remove(c);
                s.depressions.remove(c);
            }
        }
        return best;
    }

    /** A block set here rests against another: a solid neighbour below or at a side. */
    private static boolean attached(ServerLevel level, BlockPos p) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = p.relative(d);
            BlockState st = level.getBlockState(n);
            if (!st.canBeReplaced() && st.getFluidState().isEmpty() && st.isFaceSturdy(level, n, d.getOpposite())) return true;
        }
        return false;
    }

    /** The level the column of the cell is filled up to, or MAX_VALUE when it is not planned. */
    static int fillLevel(Colony colony, BlockPos cell) {
        BlockPos column = new BlockPos(cell.getX(), 0, cell.getZ());
        Integer to = state(colony).pits.get(column);
        if (to == null) to = state(colony).depressions.get(column);
        return to == null ? Integer.MAX_VALUE : to;
    }

    static void dropPit(Colony colony, BlockPos cell) {
        BlockPos column = new BlockPos(cell.getX(), 0, cell.getZ());
        state(colony).pits.remove(column);
        state(colony).depressions.remove(column);
    }
}
