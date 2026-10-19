package net.betterthanvillage.colony;

import net.betterthanvillage.mixin.construction.SinglePoolElementAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Colony detection: bells found in loaded chunks are queued and checked against
 * generated village structures on the next world tick.
 */
public final class BellScanner {
    private static final Map<ServerLevel, Deque<BlockPos>> QUEUE = new WeakHashMap<>();

    private BellScanner() {
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.BELL))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(Blocks.BELL)) {
                            BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                            synchronized (QUEUE) {
                                QUEUE.computeIfAbsent(level, l -> new ArrayDeque<>()).add(pos);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Processes queued bells (world tick, server thread). */
    public static void processQueue(ServerLevel level) {
        Deque<BlockPos> queue;
        synchronized (QUEUE) {
            queue = QUEUE.remove(level);
        }
        if (queue == null) return;
        for (BlockPos bell : queue) {
            if (!level.isLoaded(bell) || !level.getBlockState(bell).is(Blocks.BELL)) continue;
            StructureStart start = level.structureManager().getStructureWithPieceAt(bell, StructureTags.VILLAGE);
            if (!start.isValid() || start.getPieces().isEmpty()) continue;
            ColonyManager.registerOrMerge(level, bell, start.getBoundingBox(), isAbandoned(start));
        }
    }

    /** Abandoned (zombie) village variant: some piece uses a template from a ".../zombie/..." pool. */
    static boolean isAbandoned(StructureStart start) {
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement single) {
                Optional<ResourceLocation> id = ((SinglePoolElementAccessor) single).btv$template().left();
                if (id.isPresent() && id.get().getPath().contains("/zombie/")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void clear() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
    }
}
