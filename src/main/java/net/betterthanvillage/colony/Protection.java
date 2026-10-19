package net.betterthanvillage.colony;

import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Protected positions/containers, storage sanitation and the "terrain changes allowed" predicate. */
public final class Protection {
    private Protection() {
    }

    // ---------------------------------------------------------------- containers

    /** Player storage: player block, land plot, player-owned job box; managed storages are never protected. */
    public static boolean isProtectedContainer(ServerLevel level, BlockPos pos) {
        if (directlyProtected(level, pos)) {
            return true;
        }
        if (isManagedAnywhere(level, pos)) {
            return false;
        }
        BlockPos other = otherChestHalf(level, pos);
        return other != null && directlyProtected(level, other);
    }

    private static boolean directlyProtected(ServerLevel level, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        return data.isPlayerBlock(pos) || data.inLand(pos) || inPlayerOwnedJob(level, pos);
    }

    public static boolean inPlayerOwnedJob(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            for (ConstructionJob job : colony.jobs) {
                if (job.owner != null && job.contains(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isManagedAnywhere(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (colony.managedStorages.containsKey(pos)) {
                return true;
            }
        }
        return false;
    }

    /** The other half of a double chest, or null. */
    public static BlockPos otherChestHalf(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    /** Sets both halves of a double chest to SINGLE (flags 18). */
    public static void splitChest(ServerLevel level, BlockPos pos) {
        BlockPos other = otherChestHalf(level, pos);
        if (other == null || !level.isLoaded(other)) {
            return;
        }
        SafeEditScope.run(() -> {
            BlockState a = level.getBlockState(pos);
            BlockState b = level.getBlockState(other);
            level.setBlock(pos, a.setValue(ChestBlock.TYPE, ChestType.SINGLE), 18);
            if (b.getBlock() instanceof ChestBlock) {
                level.setBlock(other, b.setValue(ChestBlock.TYPE, ChestType.SINGLE), 18);
            }
        });
    }

    /** Storage sanitation (every 20 ticks): release storages that became player storage. */
    public static void sanitizeStorages(ServerLevel level) {
        ColonySavedData data = ColonyManager.data(level);
        for (Colony colony : data.colonies()) {
            colony.repairs.keySet().removeIf(data::inLand);
            Set<BlockPos> positions = new LinkedHashSet<>(colony.storages);
            positions.addAll(colony.managedStorages.keySet());
            boolean removed = false;
            List<BlockPos> toSplit = new ArrayList<>();
            for (BlockPos pos : positions) {
                if (!level.isLoaded(pos)) continue;
                if (isProtectedContainer(level, pos)) {
                    colony.removeManagedStorage(pos);
                    colony.storages.remove(pos);
                    removed = true;
                    BlockPos other = otherChestHalf(level, pos);
                    if (other != null && !isProtectedContainer(level, other)
                            && (colony.storages.contains(other) || colony.managedStorages.containsKey(other))) {
                        toSplit.add(pos);
                    }
                }
            }
            for (BlockPos pos : toSplit) {
                splitChest(level, pos);
            }
            if (removed) {
                ColonyStorage.recount(level, colony);
            }
        }
    }

    // ---------------------------------------------------------------- terrain

    /** Protected for terrain edits: wall block, land plot, player block, block entity, builder_protected tag, beacon nearby. */
    public static boolean isProtectedBlock(ServerLevel level, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        if (Fortifications.isWallBlock(level, pos)) {
            return true;
        }
        if (data.inLand(pos)) {
            return true;
        }
        if (data.isPlayerBlock(pos)) {
            if (level.getBlockState(pos).isAir()) {
                data.forgetPlacement(pos);
            } else {
                return true;
            }
        }
        if (level.getBlockEntity(pos) != null) {
            return true;
        }
        if (level.getBlockState(pos).is(ModTags.BUILDER_PROTECTED)) {
            return true;
        }
        return beaconNear(level, pos);
    }

    /** Terrain changes globally allowed, chunk loaded and position not protected. */
    public static boolean terrainModifiable(ServerLevel level, BlockPos pos) {
        return ColonyManager.terrainChangesAllowed(level) && level.isLoaded(pos) && !isProtectedBlock(level, pos);
    }

    /** A beacon block entity within 32 blocks horizontally (Chebyshev), loaded chunks only. */
    public static boolean beaconNear(ServerLevel level, BlockPos pos) {
        int minCx = (pos.getX() - 32) >> 4, maxCx = (pos.getX() + 32) >> 4;
        int minCz = (pos.getZ() - 32) >> 4, maxCz = (pos.getZ() + 32) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof BeaconBlockEntity) {
                        BlockPos b = be.getBlockPos();
                        if (Math.abs(b.getX() - pos.getX()) <= 32 && Math.abs(b.getZ() - pos.getZ()) <= 32) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Block is in the builder-protected tag. */
    public static boolean builderProtected(Block block) {
        return block.defaultBlockState().is(ModTags.BUILDER_PROTECTED);
    }
}
