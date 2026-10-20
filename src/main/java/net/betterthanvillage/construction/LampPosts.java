package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.FortBlock;
import net.betterthanvillage.colony.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lamp posts (docs_v2/04-land.md §Lamp posts): the carpenter builds a two-fence column with a torch (a lantern when
 * the treasury has one) along roads at regular spacing, on the square by the bell and by the wall gates. Dark spots
 * elsewhere are still lit on the way (gather.NightLighting). Planned here, built by {@link LampPostTask}; paid by the
 * treasury as infrastructure.
 */
public final class LampPosts {
    /** No new post within this many blocks (horizontal) of a light. */
    static final int SPACING = 10;
    /** Fence blocks under the light. */
    static final int FENCES = 2;
    private static final int MAX_SITES = 16;
    /** Road columns examined per planning step. */
    private static final int COLUMNS_PER_STEP = 3072;
    private static final long PLAN_INTERVAL = 100;
    private static final String KEY = "btv_lamp_posts";

    static final class State {
        final Set<BlockPos> sites = new LinkedHashSet<>();
        long nextPlan;
        long cursor;
    }

    private LampPosts() {
    }

    static State state(Colony colony) {
        return colony.runtime(KEY, State::new);
    }

    public static int siteCount(Colony colony) {
        return state(colony).sites.size();
    }

    /** Planning step (colony tick): the square, the gates, then a slice of the territory's roads. */
    public static void tick(ServerLevel level, Colony colony) {
        State s = state(colony);
        long now = level.getGameTime();
        if (now < s.nextPlan || !ColonyManager.terrainChangesAllowed(level)) return;
        s.nextPlan = now + PLAN_INTERVAL;
        s.sites.removeIf(p -> !level.isLoaded(p) || !valid(level, colony, p));
        if (s.sites.size() >= MAX_SITES) return;
        BlockPos core = colony.core();
        for (Direction d : Direction.Plane.HORIZONTAL) consider(level, colony, s, core.relative(d, 4).relative(d.getClockWise(), 2));
        for (FortBlock b : colony.fortifications.managed.values()) {
            if (!(b.state().getBlock() instanceof FenceGateBlock)) continue;
            double dx = core.getX() - b.pos().getX(), dz = core.getZ() - b.pos().getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            // Inside the ring, beside the gate.
            consider(level, colony, s, b.pos().offset((int) Math.round(dx / len * 2) + 1, 0, (int) Math.round(dz / len * 2)));
        }
        int r = colony.radius();
        long side = 2L * r + 1;
        for (int i = 0; i < COLUMNS_PER_STEP && s.sites.size() < MAX_SITES; i++) {
            long idx = s.cursor++ % (side * side);
            int x = core.getX() - r + (int) (idx % side), z = core.getZ() - r + (int) (idx / side);
            BlockPos column = new BlockPos(x, 0, z);
            if (!level.isLoaded(column) || !colony.containsXZ(column)) continue;
            BlockPos ground = surface(level, x, z).below();
            if (!level.getBlockState(ground).is(Blocks.DIRT_PATH)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side1 = ground.relative(d);
                if (!level.getBlockState(side1).is(Blocks.DIRT_PATH)) consider(level, colony, s, side1);
            }
        }
    }

    /** Free cell on top of the column (above its ground). */
    private static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    private static void consider(ServerLevel level, Colony colony, State s, BlockPos near) {
        if (!level.isLoaded(near)) return;
        BlockPos at = surface(level, near.getX(), near.getZ());
        if (s.sites.contains(at)) return;
        for (BlockPos other : s.sites) {
            if (Math.abs(other.getX() - at.getX()) <= SPACING && Math.abs(other.getZ() - at.getZ()) <= SPACING) return;
        }
        if (valid(level, colony, at)) s.sites.add(at);
    }

    /** A post fits at the cell: free cells above sturdy natural ground in the territory, nobody's property, no light near. */
    static boolean valid(ServerLevel level, Colony colony, BlockPos at) {
        if (!colony.containsXZ(at)) return false;
        for (int i = 0; i <= FENCES; i++) {
            if (!level.getBlockState(at.above(i)).isAir()) return false;
        }
        BlockPos below = at.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.isFaceSturdy(level, below, Direction.UP) || ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND)) return false;
        if (!level.canSeeSky(at) || Protection.playerProperty(level, at) || !Protection.terrainModifiable(level, at)) return false;
        if (Fortifications.isWallBlock(level, below) || Damage.isProtectedHousePiece(level, at)) return false;
        for (var job : colony.jobs) {
            if (job.isActive() && job.box().inflatedBy(2).isInside(at)) return false;
        }
        return !lightNear(level, at);
    }

    /** Block light at the cell: a torch or lantern lights it from up to about SPACING blocks away. */
    private static boolean lightNear(ServerLevel level, BlockPos at) {
        return level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at.above()) >= 15 - SPACING + 1;
    }

    /** The nearest planned post to a carpenter, or null. */
    static BlockPos next(ServerLevel level, Colony colony, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : state(colony).sites) {
            if (!level.isLoaded(p)) continue;
            double d = p.distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    static void done(Colony colony, BlockPos site) {
        state(colony).sites.remove(site);
    }
}
