package net.betterthanvillage.food;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.Damage;
import net.betterthanvillage.mixin.construction.SinglePoolElementAccessor;
import net.betterthanvillage.mixin.society.StructureTemplateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Location predicates for farming (04 §2.11), village hay (§2.8) and vanilla farm-template farmland (§2.5). */
public final class FarmTerritory {
    public static final int FARM_SET_MAX = 4096;

    private static final Map<ResourceLocation, List<BlockPos>> TEMPLATE_FARMLAND = new ConcurrentHashMap<>();

    private FarmTerritory() {
    }

    // ---------------------------------------------------------------- block classes

    public static boolean isSoil(BlockState s) {
        return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK);
    }

    public static boolean isGrassPlant(BlockState s) {
        return s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS);
    }

    public static boolean isAirOrGrass(BlockState s) {
        return s.isAir() || isGrassPlant(s);
    }

    // ---------------------------------------------------------------- predicates

    /** Chunk loaded, territory check, no road block or block entity in the 3x3x2 box around p. */
    public static boolean soilOk(ServerLevel level, Colony colony, BlockPos p) {
        if (!level.isLoaded(p) || !territoryOk(level, colony, p)) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (!level.isLoaded(m)) return false;
                    if (FoodUtil.isRoadBlock(level.getBlockState(m)) || level.getBlockEntity(m) != null) return false;
                }
            }
        }
        return true;
    }

    /** Any water block in the box (-4..4, 0..+1, -4..4). */
    public static boolean waterWithin4(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (level.isLoaded(m) && level.getBlockState(m).is(Blocks.WATER)) return true;
                }
            }
        }
        return false;
    }

    /** Pen footprint with a margin: |dx| <= 5 + margin and |dz| <= 5 + margin around the pen centre. */
    public static boolean nearPen(Colony colony, BlockPos pos, int margin) {
        BlockPos p = colony.livestockPen;
        return p != null && Math.abs(pos.getX() - p.getX()) <= 5 + margin && Math.abs(pos.getZ() - p.getZ()) <= 5 + margin;
    }

    /** Within the horizontal margin of any construction job box of this colony, or inside the pen footprint. */
    public static boolean nearPlot(Colony colony, BlockPos pos, int margin) {
        for (ConstructionJob job : colony.jobs) {
            if (nearBoxXZ(job.box(), pos, margin)) return true;
        }
        return nearPen(colony, pos, 0);
    }

    private static boolean nearBoxXZ(BoundingBox b, BlockPos pos, int margin) {
        return pos.getX() >= b.minX() - margin && pos.getX() <= b.maxX() + margin
                && pos.getZ() >= b.minZ() - margin && pos.getZ() <= b.maxZ() + margin;
    }

    /** Colony territory check for farming (04 §2.11). */
    public static boolean territoryOk(ServerLevel level, Colony colony, BlockPos pos) {
        if (nearPen(colony, pos, 1)) return false;
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos) || Protection.inPlayerOwnedJob(level, pos)) return false;
        for (Colony c : data.colonies()) {
            if (nearStructure(c, pos, 3) || nearRoad(c, pos)) return false;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.isLoaded(m) || FoodUtil.isRoadBlock(level.getBlockState(m))) return false;
                }
            }
        }
        return !Damage.isProtectedHousePiece(level, pos) && !Damage.isProtectedHousePiece(level, pos.above());
    }

    /** Near the planned wall ring (|dist - W| <= margin + 2) or within margin of any construction job box. */
    private static boolean nearStructure(Colony colony, BlockPos pos, int margin) {
        if (net.betterthanvillage.construction.Fortifications.isNearWall(colony, pos, margin)) return true;
        for (ConstructionJob job : colony.jobs) {
            if (nearBoxXZ(job.box(), pos, margin)) return true;
        }
        return false;
    }

    private static boolean nearRoad(Colony colony, BlockPos pos) {
        for (BlockPos r : colony.roadQueue) {
            if (Math.abs(r.getX() - pos.getX()) <= 1 && Math.abs(r.getZ() - pos.getZ()) <= 1) return true;
        }
        return false;
    }

    /** Adds to the persistent farm set (capped); marks the world state dirty when new. */
    public static void registerFarmland(ServerLevel level, Colony colony, BlockPos pos) {
        if (colony.farmland.size() >= FARM_SET_MAX || colony.farmland.contains(pos)) return;
        colony.farmland.add(pos.immutable());
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- structures

    private static StructureStart village(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        StructureStart start = level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE);
        return start.isValid() ? start : null;
    }

    /** Village hay: inside a generated village, or within 48 of the core with a dirt path nearby (04 §2.8). */
    public static boolean isVillageHay(ServerLevel level, Colony colony, BlockPos pos) {
        if (village(level, pos) != null) return true;
        if (pos.distSqr(colony.core()) > 2304) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.isLoaded(m) && level.getBlockState(m).is(Blocks.DIRT_PATH)) return true;
                }
            }
        }
        return false;
    }

    /** The position was farmland in a generated vanilla village farm piece. */
    public static boolean wasTemplateFarmland(ServerLevel level, BlockPos pos) {
        StructureStart start = village(level, pos);
        return start != null && templateFarmlandIn(level, start, pos);
    }

    /**
     * Same as {@link #wasTemplateFarmland(ServerLevel, BlockPos)}, with the village starts of each chunk
     * looked up once and kept in the caller's cache (cleared by the caller, e.g. once per sweep).
     */
    public static boolean wasTemplateFarmland(ServerLevel level, BlockPos pos, Map<Long, List<StructureStart>> cache) {
        if (!level.isLoaded(pos)) return false;
        ChunkPos chunk = new ChunkPos(pos);
        List<StructureStart> starts = cache.computeIfAbsent(chunk.toLong(), k -> {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            return level.structureManager().startsForStructure(chunk,
                    s -> registry.getHolder(registry.getId(s)).map(h -> h.is(StructureTags.VILLAGE)).orElse(false));
        });
        for (StructureStart start : starts) {
            if (start.isValid() && start.getBoundingBox().isInside(pos) && templateFarmlandIn(level, start, pos)) return true;
        }
        return false;
    }

    private static boolean templateFarmlandIn(ServerLevel level, StructureStart start, BlockPos pos) {
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece pep) || !pep.getBoundingBox().isInside(pos)) continue;
            if (!(pep.getElement() instanceof SinglePoolElement element)) continue;
            Optional<ResourceLocation> id = ((SinglePoolElementAccessor) element).btv$template().left();
            if (id.isEmpty() || !id.get().getPath().contains("farm")) continue;
            for (BlockPos rel : templateFarmland(level, id.get())) {
                if (StructureTemplate.transform(rel, Mirror.NONE, pep.getRotation(), BlockPos.ZERO).offset(pep.getPosition()).equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<BlockPos> templateFarmland(ServerLevel level, ResourceLocation id) {
        return TEMPLATE_FARMLAND.computeIfAbsent(id, key -> {
            List<BlockPos> out = new ArrayList<>();
            level.getStructureManager().get(key).ifPresent(t -> {
                List<StructureTemplate.Palette> palettes = ((StructureTemplateAccess) t).btv$palettes();
                if (palettes.isEmpty()) return;
                for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                    if (info.state().is(Blocks.FARMLAND)) out.add(info.pos());
                }
            });
            return out;
        });
    }

    /** Vanilla HarvestFarmland suitability S(pos) (02 §2.2). */
    public static boolean vanillaFarmSuitable(ServerLevel level, BlockPos pos) {
        if (Damage.isProtectedHousePiece(level, pos) || Damage.isProtectedHousePiece(level, pos.below())) return false;
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null) return true;
        return !ColonyManager.data(level).isPlayerBlock(pos.below()) && territoryOk(level, colony, pos.below());
    }

    public static void clearCaches() {
        TEMPLATE_FARMLAND.clear();
    }
}
