package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/** Mod-placed double chests ("managed storages") and their site search. */
public final class StoragePlacer {
    private StoragePlacer() {
    }

    /** Places a double chest at pos and pos.east(), registers both halves as managed storages of the kind. */
    public static boolean placeDoubleChest(ServerLevel level, Colony colony, BlockPos pos, StorageKind kind) {
        BlockPos east = pos.east();
        if (!canPlace(level, pos)) {
            return false;
        }
        Direction facing = chooseFacing(level, colony, pos);
        if (facing == null) {
            return false;
        }
        writeChestPair(level, pos, facing);
        colony.managedStorages.put(pos.immutable(), kind);
        colony.managedStorages.put(east.immutable(), kind);
        colony.managedChestPairs.put(pos.immutable(), east.immutable());
        if (!colony.storages.contains(pos)) colony.storages.add(pos.immutable());
        if (!colony.storages.contains(east)) colony.storages.add(east.immutable());
        ColonyManager.markDirty(level);
        return true;
    }

    /** Placement checks shared with the site search. */
    public static boolean canPlace(ServerLevel level, BlockPos pos) {
        BlockPos east = pos.east();
        ColonySavedData data = ColonyManager.data(level);
        for (Colony c : data.colonies()) {
            if (c.repairs.containsKey(pos) || c.repairs.containsKey(east) || c.repairs.containsKey(pos.above()) || c.repairs.containsKey(east.above())) {
                return false;
            }
        }
        if (data.inLand(pos) || data.inLand(east) || Protection.inPlayerOwnedJob(level, pos) || Protection.inPlayerOwnedJob(level, east)) {
            return false;
        }
        if (!Protection.terrainModifiable(level, pos) || !Protection.terrainModifiable(level, east)) {
            return false;
        }
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(east).isAir()) {
            return false;
        }
        return sideFree(level, pos, Direction.NORTH) || sideFree(level, pos, Direction.SOUTH);
    }

    private static boolean sideFree(ServerLevel level, BlockPos pos, Direction side) {
        for (BlockPos cell : new BlockPos[]{pos, pos.east()}) {
            BlockPos n = cell.relative(side);
            if (!level.isLoaded(n) || !level.isLoaded(n.above())) {
                return false;
            }
            if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static Direction chooseFacing(ServerLevel level, Colony colony, BlockPos pos) {
        boolean north = sideFree(level, pos, Direction.NORTH);
        boolean south = sideFree(level, pos, Direction.SOUTH);
        if (north && !south) return Direction.NORTH;
        if (south && !north) return Direction.SOUTH;
        if (!north) return null;
        return colony.core().getZ() > pos.getZ() ? Direction.SOUTH : Direction.NORTH;
    }

    /** Writes both halves with the facing; NORTH: pos LEFT / east RIGHT, SOUTH: pos RIGHT / east LEFT. */
    public static void writeChestPair(ServerLevel level, BlockPos pos, Direction facing) {
        BlockPos east = pos.east();
        ChestType posType = facing == Direction.NORTH ? ChestType.LEFT : ChestType.RIGHT;
        ChestType eastType = facing == Direction.NORTH ? ChestType.RIGHT : ChestType.LEFT;
        SafeEditScope.run(() -> {
            level.setBlock(pos, chestState(level.getBlockState(pos), facing, posType), 18);
            level.setBlock(east, chestState(level.getBlockState(east), facing, eastType), 18);
        });
    }

    private static BlockState chestState(BlockState existing, Direction facing, ChestType type) {
        BlockState base = existing.getBlock() instanceof ChestBlock ? existing : Blocks.CHEST.defaultBlockState();
        return base.setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type);
    }

    /** Square rings d = 3..20 around the core; first fully valid site. */
    public static BlockPos findSite(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        for (int d = 3; d <= 20; d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != d) continue;
                    int px = core.getX() + x;
                    int pz = core.getZ() + z;
                    if (!level.hasChunk(px >> 4, pz >> 4) || !level.hasChunk((px + 1) >> 4, pz >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                    if (Math.abs(y - core.getY()) > 3) continue;
                    BlockPos pos = new BlockPos(px, y, pz);
                    if (validSite(level, colony, pos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    public static boolean validSite(ServerLevel level, Colony colony, BlockPos pos) {
        BlockPos east = pos.east();
        if (!canPlace(level, pos)) return false;
        if (!level.getBlockState(pos.above()).isAir() || !level.getBlockState(east.above()).isAir()) return false;
        for (BlockPos cell : new BlockPos[]{pos, east}) {
            BlockPos below = cell.below();
            BlockState ground = level.getBlockState(below);
            if (!ground.isFaceSturdy(level, below, Direction.UP)) return false;
            if (ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND) || ground.getBlock() instanceof ChestBlock) return false;
        }
        // The neighbourhood scan below must not load chunks (the box spans at most 2x2 chunks).
        int minX = pos.getX() - 2, maxX = east.getX() + 2, minZ = pos.getZ() - 2, maxZ = pos.getZ() + 2;
        if (!level.hasChunk(minX >> 4, minZ >> 4) || !level.hasChunk(maxX >> 4, minZ >> 4)
                || !level.hasChunk(minX >> 4, maxZ >> 4) || !level.hasChunk(maxX >> 4, maxZ >> 4)) return false;
        AABB cells = new AABB(pos.getX(), pos.getY(), pos.getZ(), east.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        if (!level.getEntitiesOfClass(LivingEntity.class, cells).isEmpty()) return false;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), east.offset(2, 2, 2))) {
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.DOORS) || s.is(BlockTags.BEDS) || s.getBlock() instanceof BellBlock) return false;
        }
        for (ConstructionJob job : colony.jobs) {
            BoundingBox box = job.box();
            if (box.isInside(pos) || box.isInside(east)) return false;
        }
        return true;
    }
}
