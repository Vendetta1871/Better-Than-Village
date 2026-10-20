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
    private static final long PLAN_INTERVAL = 200;
    private static final String STATE_KEY = "btv_land_work";

    /** Runtime state: what to tear down and which columns to fill (column -> ground level to fill up to). */
    static final class State {
        final Set<BlockPos> demolition = new LinkedHashSet<>();
        final java.util.Map<BlockPos, Integer> pits = new java.util.LinkedHashMap<>();
        long nextPlan;
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
        return state(colony).pits.size();
    }

    /** Planning step (colony tick). */
    public static void tick(ServerLevel level, Colony colony) {
        State s = state(colony);
        long now = level.getGameTime();
        if (now < s.nextPlan || !ColonyManager.terrainChangesAllowed(level)) return;
        s.nextPlan = now + PLAN_INTERVAL;
        planDemolition(level, colony, s, now);
        planPits(level, colony, s);
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

    /** The next cell to fill for a miner at from: the lowest air cell of the nearest pit, or null. */
    static BlockPos nextFill(ServerLevel level, Colony colony, BlockPos from) {
        State s = state(colony);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : s.pits.entrySet()) {
            BlockPos c = e.getKey();
            if (!level.isLoaded(c)) continue;
            double d = Math.hypot(c.getX() - from.getX(), c.getZ() - from.getZ());
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null) return null;
        int g = ground(level, best.getX(), best.getZ());
        if (g >= s.pits.get(best)) {
            s.pits.remove(best);
            return null;
        }
        return new BlockPos(best.getX(), g + 1, best.getZ());
    }

    static void dropPit(Colony colony, BlockPos cell) {
        state(colony).pits.remove(new BlockPos(cell.getX(), 0, cell.getZ()));
    }
}
