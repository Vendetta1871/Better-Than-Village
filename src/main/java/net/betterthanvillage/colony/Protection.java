package net.betterthanvillage.colony;

import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.registry.ModTags;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Protected positions/containers, storage sanitation and the "terrain changes allowed" predicate. */
public final class Protection {
    private Protection() {
    }

    // ---------------------------------------------------------------- containers

    /** Per-level memo of isProtectedContainer for the current game tick. */
    private static final Map<ServerLevel, TickMemo> CONTAINER_MEMO = new WeakHashMap<>();

    private static final class TickMemo {
        long tick = Long.MIN_VALUE;
        final Long2BooleanOpenHashMap values = new Long2BooleanOpenHashMap();
    }

    /** Player storage: player block, land plot, player-owned job box; managed storages are never protected. Memoized per tick. */
    public static boolean isProtectedContainer(ServerLevel level, BlockPos pos) {
        TickMemo memo = CONTAINER_MEMO.computeIfAbsent(level, l -> new TickMemo());
        long now = level.getGameTime();
        if (memo.tick != now) {
            memo.tick = now;
            memo.values.clear();
        }
        long key = pos.asLong();
        if (memo.values.containsKey(key)) {
            return memo.values.get(key);
        }
        boolean result = computeProtectedContainer(level, pos);
        memo.values.put(key, result);
        return result;
    }

    private static boolean computeProtectedContainer(ServerLevel level, BlockPos pos) {
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
                if (job.owner != null && inJobBox(job, pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Allocation-free ConstructionJob#contains (same box as ConstructionJob#box). */
    public static boolean inJobBox(ConstructionJob job, BlockPos pos) {
        BlockPos o = job.origin;
        return pos.getX() >= o.getX() && pos.getX() < o.getX() + job.sizeX
                && pos.getY() >= o.getY() && pos.getY() < o.getY() + job.sizeY
                && pos.getZ() >= o.getZ() && pos.getZ() < o.getZ() + job.sizeZ;
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

    /** A beacon block entity within 32 blocks horizontally (Chebyshev), loaded chunks only (per-chunk cache). */
    public static boolean beaconNear(ServerLevel level, BlockPos pos) {
        int minCx = (pos.getX() - 32) >> 4, maxCx = (pos.getX() + 32) >> 4;
        int minCz = (pos.getZ() - 32) >> 4, maxCz = (pos.getZ() + 32) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (long b : beaconsIn(level, cx, cz)) {
                    if (Math.abs(BlockPos.getX(b) - pos.getX()) <= 32 && Math.abs(BlockPos.getZ(b) - pos.getZ()) <= 32) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- beacon cache

    private static final long BEACON_TTL = 200;
    private static final long[] NO_BEACONS = new long[0];
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<BeaconEntry>> BEACONS = new WeakHashMap<>();

    private record BeaconEntry(long time, long[] positions) {
    }

    /** Beacon positions of a loaded chunk (empty for unloaded chunks), rescanned at most every 200 ticks. */
    private static long[] beaconsIn(ServerLevel level, int cx, int cz) {
        Long2ObjectOpenHashMap<BeaconEntry> cache = BEACONS.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        long key = ChunkPos.asLong(cx, cz);
        long now = level.getGameTime();
        BeaconEntry entry = cache.get(key);
        if (entry != null && now - entry.time() >= 0 && now - entry.time() < BEACON_TTL) {
            return entry.positions();
        }
        if (!level.hasChunk(cx, cz)) {
            cache.remove(key);
            return NO_BEACONS;
        }
        LevelChunk chunk = level.getChunk(cx, cz);
        long[] found = NO_BEACONS;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof BeaconBlockEntity) {
                found = java.util.Arrays.copyOf(found, found.length + 1);
                found[found.length - 1] = be.getBlockPos().asLong();
            }
        }
        cache.put(key, new BeaconEntry(now, found));
        return found;
    }

    /** Drops the cached beacon list of the chunk containing pos (beacon placed/broken, chunk loaded/unloaded). */
    public static void invalidateBeacons(ServerLevel level, int cx, int cz) {
        Long2ObjectOpenHashMap<BeaconEntry> cache = BEACONS.get(level);
        if (cache != null) {
            cache.remove(ChunkPos.asLong(cx, cz));
        }
    }

    public static void clearCaches() {
        BEACONS.clear();
        CONTAINER_MEMO.clear();
    }

    /** Block is in the builder-protected tag. */
    public static boolean builderProtected(Block block) {
        return block.defaultBlockState().is(ModTags.BUILDER_PROTECTED);
    }
}
