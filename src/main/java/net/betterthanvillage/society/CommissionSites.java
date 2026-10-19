package net.betterthanvillage.society;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.LandPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Fallback commissioned-house site search used until the construction subsystem installs its own
 * ({@link Deeds#siteFinder}). Square spiral around the core, at most 32 x 48 candidates, unrotated footprint.
 */
final class CommissionSites {
    private static final int MAX_CANDIDATES = 32 * 48;
    private static final int MARGIN = 2;

    private CommissionSites() {
    }

    static ConstructionJob find(ServerLevel level, Colony colony, ResourceLocation templateId, StructureTemplate template) {
        Vec3i size = template.getSize();
        int sx = Math.max(1, size.getX()), sy = Math.max(1, size.getY()), sz = Math.max(1, size.getZ());
        BlockPos core = colony.core();
        int maxRing = Math.max(0, colony.radius() - Math.max(sx, sz) - MARGIN);
        int checked = 0;
        for (int d = 10; d <= maxRing && checked < MAX_CANDIDATES; d += 2) {
            for (int i = -d; i <= d && checked < MAX_CANDIDATES; i += 2) {
                int[][] candidates = {{i, -d}, {d, i}, {-i, d}, {-d, -i}};
                for (int[] off : candidates) {
                    checked++;
                    BlockPos origin = check(level, colony, core.getX() + off[0], core.getZ() + off[1], sx, sy, sz);
                    if (origin != null) {
                        ConstructionJob job = new ConstructionJob();
                        job.template = templateId;
                        job.origin = origin;
                        job.sizeX = sx;
                        job.sizeY = sy;
                        job.sizeZ = sz;
                        job.rotation = 0;
                        return job;
                    }
                }
            }
        }
        return null;
    }

    /** Returns the origin when the footprint (plus margin) is flat, natural, free and inside the territory. */
    private static BlockPos check(ServerLevel level, Colony colony, int x0, int z0, int sx, int sy, int sz) {
        int minX = x0 - MARGIN, minZ = z0 - MARGIN, maxX = x0 + sx - 1 + MARGIN, maxZ = z0 + sz - 1 + MARGIN;
        BlockPos core = colony.core();
        for (int[] corner : new int[][]{{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}}) {
            if (Math.hypot(corner[0] - core.getX(), corner[1] - core.getZ()) > colony.radius()) return null;
        }
        ColonySavedData data = ColonyManager.data(level);
        BoundingBox area = new BoundingBox(minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(), maxZ);
        if (core.getX() >= minX - 4 && core.getX() <= maxX + 4 && core.getZ() >= minZ - 4 && core.getZ() <= maxZ + 4) return null;
        for (Colony other : data.colonies()) {
            for (ConstructionJob job : other.jobs) {
                BoundingBox b = job.box();
                if (b.maxX() >= minX && b.minX() <= maxX && b.maxZ() >= minZ && b.minZ() <= maxZ) return null;
            }
        }
        for (LandPlot plot : data.land) {
            if (plot.intersects(area)) return null;
        }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return null;
                LongSet set = data.playerBlocksInChunk(cx, cz);
                if (set == null) continue;
                for (long key : set) {
                    int x = BlockPos.getX(key), z = BlockPos.getZ(key);
                    if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) return null;
                }
            }
        }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                lo = Math.min(lo, top);
                hi = Math.max(hi, top);
                if (hi - lo > 2) return null;
                BlockPos ground = new BlockPos(x, top - 1, z);
                BlockState s = level.getBlockState(ground);
                if (!level.getFluidState(ground).isEmpty() || !Deeds.naturalGround(s) || Deeds.isRoadBlock(s)) return null;
                if (level.getBlockEntity(ground) != null || level.getBlockEntity(ground.above()) != null) return null;
                if (VillagePieces.pieceAt(level, ground) != null) return null;
            }
        }
        if (lo + sy >= level.getMaxBuildHeight()) return null;
        // Vanilla house templates have their floor layer at y = 0, which replaces the ground block.
        return new BlockPos(x0, lo - 1, z0);
    }
}
