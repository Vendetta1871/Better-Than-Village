package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.mixin.construction.SinglePoolElementAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** Colony ownership of blocks, restore snapshots, repair bookkeeping and the repair audit (05 §6, 02 §2.5). */
public final class Damage {
    public static final int REPAIR_COOLDOWN = 600;
    private static final Map<ServerLevel, Map<Long, HouseBoxes>> HOUSE_CACHE = new WeakHashMap<>();

    private record HouseBoxes(long time, List<BoundingBox> boxes) {
    }

    private record Actionable(long time, boolean any) {
    }

    private Damage() {
    }

    // ---------------------------------------------------------------- ownership

    /** Colony-owned test (land plot no; managed storage/core yes; owned completed job yes; player block no; wall yes; job box yes; protected house piece). */
    public static boolean isColonyOwned(ServerLevel level, Colony colony, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos)) return false;
        if (colony.managedStorages.containsKey(pos) || colony.core().equals(pos)) return true;
        for (ConstructionJob job : colony.jobs) {
            if (job.owner != null && job.countsAsCompleted() && job.contains(pos)) return true;
        }
        if (data.isPlayerBlock(pos)) return false;
        if (Fortifications.isWallBlock(level, pos)) return true;
        for (ConstructionJob job : colony.jobs) {
            if (job.contains(pos)) return true;
        }
        return isProtectedHousePiece(level, pos);
    }

    /** Position lies in a protected vanilla village house piece (template path contains /houses/, not farm/pen/animal). */
    public static boolean isProtectedHousePiece(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        for (BoundingBox box : houseBoxes(level, new ChunkPos(pos))) {
            if (box.isInside(pos)) return true;
        }
        return false;
    }

    /** House piece boxes of the village structures referenced by a chunk (cached for 1200 ticks). */
    private static List<BoundingBox> houseBoxes(ServerLevel level, ChunkPos chunk) {
        long now = level.getGameTime();
        Map<Long, HouseBoxes> cache;
        synchronized (HOUSE_CACHE) {
            cache = HOUSE_CACHE.computeIfAbsent(level, l -> new HashMap<>());
        }
        HouseBoxes hit = cache.get(chunk.toLong());
        if (hit != null && now - hit.time() >= 0 && now - hit.time() < 1200) return hit.boxes();
        List<BoundingBox> boxes = new ArrayList<>();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (StructureStart start : level.structureManager().startsForStructure(chunk,
                s -> registry.getHolder(registry.getId(s)).map(h -> h.is(StructureTags.VILLAGE)).orElse(false))) {
            for (StructurePiece piece : start.getPieces()) {
                if (isHousePiece(piece)) boxes.add(piece.getBoundingBox());
            }
        }
        if (cache.size() > 4096) cache.clear();
        List<BoundingBox> result = List.copyOf(boxes);
        cache.put(chunk.toLong(), new HouseBoxes(now, result));
        return result;
    }

    /** Template id of a single-pool-element piece, if resolvable. */
    public static Optional<ResourceLocation> pieceTemplate(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece pe && pe.getElement() instanceof SinglePoolElement sp) {
            return ((SinglePoolElementAccessor) sp).btv$template().left();
        }
        return Optional.empty();
    }

    static boolean isHousePiece(StructurePiece piece) {
        Optional<ResourceLocation> id = pieceTemplate(piece);
        if (id.isEmpty()) return false;
        String path = id.get().getPath();
        return path.contains("/houses/") && !path.contains("farm") && !path.contains("pen") && !path.contains("animal");
    }

    // ---------------------------------------------------------------- snapshots

    /** Restore snapshot of a state at pos: empty for air/replaceable/crops/path/farmland, both halves for doors and beds. */
    public static Map<BlockPos, BlockState> restoreSnapshot(ServerLevel level, BlockPos pos, BlockState state) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        if (state == null || state.isAir() || state.canBeReplaced() || state.getBlock() instanceof CropBlock
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)) {
            return out;
        }
        // natural foliage decays by itself; restoring it would loop forever
        if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)) {
            return out;
        }
        if (state.getBlock() instanceof DoorBlock) {
            BlockState closed = state.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.POWERED, false);
            BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            out.put(lower.immutable(), closed.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            out.put(lower.above().immutable(), closed.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            return out;
        }
        if (state.getBlock() instanceof BedBlock) {
            BlockState free = state.setValue(BedBlock.OCCUPIED, false);
            Direction facing = state.getValue(BedBlock.FACING);
            BlockPos foot = state.getValue(BedBlock.PART) == BedPart.FOOT ? pos : pos.relative(facing.getOpposite());
            out.put(foot.immutable(), free.setValue(BedBlock.PART, BedPart.FOOT));
            out.put(foot.relative(facing).immutable(), free.setValue(BedBlock.PART, BedPart.HEAD));
            return out;
        }
        out.put(pos.immutable(), state);
        return out;
    }

    /** Records snapshot entries whose current world state differs into the colony repair map (put-if-absent). */
    public static void recordRepairs(ServerLevel level, Colony colony, Map<BlockPos, BlockState> snapshot) {
        boolean changed = false;
        for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
            if (!level.getBlockState(e.getKey()).equals(e.getValue()) && !colony.repairs.containsKey(e.getKey())) {
                colony.repairs.put(e.getKey().immutable(), e.getValue());
                changed = true;
            }
        }
        if (changed) {
            ColonyManager.markDirty(level);
        }
    }

    /**
     * Plot damaged by a player (05 §2.1 onPlayerDamage): an owned completed plot stays COMPLETE
     * (builder cleared); otherwise it is reset to QUEUED for a full re-run of its plan.
     */
    public static void onPlayerDamage(ConstructionJob job, long now) {
        if (job.owner != null && job.countsAsCompleted()) {
            job.status = JobStatus.COMPLETE;
            job.builder = null;
            return;
        }
        job.status = JobStatus.QUEUED;
        job.builder = null;
        job.nextBlock = 0;
        job.retryAfter = 0;
        job.lastProgressTick = now;
    }

    // ---------------------------------------------------------------- griefing listener

    /** Generic block change hook (02 §2.5): records colony-owned blocks destroyed outside mod edits. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (old == null || SafeEditScope.active() || old.getBlock() == now.getBlock()) return;
        if (old.isAir() || old.canBeReplaced() || !(now.isAir() || now.canBeReplaced())) return;
        if (!level.getServer().isSameThread()) return;
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null) return;
        for (ConstructionJob job : colony.jobs) {
            if (HousePlanner.activePlot(job) && !job.countsAsCompleted() && job.contains(pos)) return;
        }
        if (!isColonyOwned(level, colony, pos)) return;
        recordRepairs(level, colony, restoreSnapshot(level, pos, old));
    }

    // ---------------------------------------------------------------- repair bookkeeping

    /** Runtime-only repair cooldowns of a colony: position -> until time. */
    static Map<BlockPos, Long> cooldowns(Colony colony) {
        return colony.runtime("btv_repair_cooldowns", HashMap::new);
    }

    /**
     * Repair materials sampled once per scan: items in usable storage and carried by builders.
     * Any #logs / #stone_crafting_materials item satisfies every material (see BuildPlan.accepts).
     */
    static final class Supply {
        private final Set<Item> items = new HashSet<>();
        private boolean substitute;
        private boolean sand;

        static Supply sample(ServerLevel level, Colony colony) {
            Supply s = new Supply();
            for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
                Container c = slot.container();
                for (int i = 0; i < c.getContainerSize(); i++) s.add(c.getItem(i));
            }
            for (Villager v : ColonyManager.adultMembers(level, colony)) {
                if (colony.roleOf(v.getUUID()) != ColonyRole.BUILDER) continue;
                s.add(v.getMainHandItem());
                for (int i = 0; i < v.getInventory().getContainerSize(); i++) s.add(v.getInventory().getItem(i));
            }
            return s;
        }

        private void add(ItemStack stack) {
            if (stack.isEmpty()) return;
            items.add(stack.getItem());
            if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.STONE_CRAFTING_MATERIALS)) substitute = true;
            if (stack.is(Items.SAND) || stack.is(Items.RED_SAND)) sand = true;
        }

        /** Same rule as BuildPlan.accepts over the sampled items. */
        boolean has(BlockState state) {
            if (substitute) return true;
            Item needed = BuildPlan.materialItem(state);
            if (needed != Items.AIR && items.contains(needed)) return true;
            return sand && BuildPlan.path(state.getBlock()).contains("sandstone");
        }
    }

    /** Repair entry actionable now (05 §6.3). */
    public static boolean actionable(ServerLevel level, Colony colony, BlockPos pos, BlockState intended) {
        return actionable(level, colony, pos, intended, null);
    }

    /** Variant with a lazily sampled supply holder (one element) shared across a scan; cheap checks run first. */
    static boolean actionable(ServerLevel level, Colony colony, BlockPos pos, BlockState intended, Supply[] supply) {
        if (intended == null || !level.isLoaded(pos) || ColonyManager.data(level).inLand(pos)) return false;
        BlockState world = level.getBlockState(pos);
        if (world.getBlock() == intended.getBlock()) return true;
        Long until = cooldowns(colony).get(pos);
        if (until != null && level.getGameTime() < until) return false;
        if (!(world.isAir() || world.canBeReplaced())) return false;
        if (BuildPlan.materialItem(intended) == Items.AIR) return false;
        Supply s;
        if (supply == null) {
            s = Supply.sample(level, colony);
        } else {
            if (supply[0] == null) supply[0] = Supply.sample(level, colony);
            s = supply[0];
        }
        if (!s.has(intended)) return false;
        return Protection.terrainModifiable(level, pos);
    }

    /** Any actionable repair entry (cached per game tick). */
    public static boolean hasActionableRepair(ServerLevel level, Colony colony) {
        if (colony.repairs.isEmpty()) return false;
        long now = level.getGameTime();
        Actionable cached = (Actionable) colony.runtime.get("btv_repair_actionable");
        if (cached != null && cached.time() == now) return cached.any();
        cooldowns(colony).values().removeIf(t -> t <= now);
        boolean any = false;
        Supply[] supply = new Supply[1];
        for (Map.Entry<BlockPos, BlockState> e : new ArrayList<>(colony.repairs.entrySet())) {
            if (actionable(level, colony, e.getKey(), e.getValue(), supply)) {
                any = true;
                break;
            }
        }
        colony.runtime.put("btv_repair_actionable", new Actionable(now, any));
        return any;
    }

    static boolean carried(Villager v, BlockState state) {
        if (BuildPlan.accepts(state, v.getMainHandItem())) return true;
        for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
            ItemStack s = v.getInventory().getItem(i);
            if (BuildPlan.accepts(state, s)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- audit

    /** Repair audit of worldgen houses and completed plots (05 §6.2); returns the number of new entries. */
    public static int audit(ServerLevel level, Colony colony) {
        int before = colony.repairs.size();
        StructureStart start = level.structureManager().getStructureWithPieceAt(colony.core(), StructureTags.VILLAGE);
        if (start.isValid()) {
            for (StructurePiece piece : start.getPieces()) {
                if (!isHousePiece(piece) || !(piece instanceof PoolElementStructurePiece pe)) continue;
                Optional<StructureTemplate> t = HouseCatalog.template(level, pieceTemplate(piece).get());
                if (t.isEmpty()) continue;
                StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pe.getRotation());
                for (StructureTemplate.StructureBlockInfo info : HouseCatalog.blocks(t.get())) {
                    BlockState state = info.state().is(Blocks.JIGSAW) ? BuildPlan.jigsawFinalState(info) : info.state();
                    if (state == null) continue;
                    BlockPos p = pe.getPosition().offset(StructureTemplate.calculateRelativePosition(settings, info.pos()));
                    auditBlock(level, colony, p, state.rotate(pe.getRotation()), null);
                }
            }
        }
        for (ConstructionJob job : colony.jobs) {
            if (!job.countsAsCompleted() || job.owner != null) continue;
            Optional<StructureTemplate> t = HouseCatalog.template(level, job.template);
            if (t.isEmpty()) continue;
            var size = t.get().getSize();
            if (size.getX() != job.sizeX || size.getY() != job.sizeY || size.getZ() != job.sizeZ) continue;
            for (StructureTemplate.StructureBlockInfo info : HouseCatalog.blocks(t.get())) {
                BlockState state = info.state().is(Blocks.JIGSAW) ? BuildPlan.jigsawFinalState(info) : info.state();
                if (state != null) auditBlock(level, colony, job.origin.offset(info.pos()), state, job.box());
            }
        }
        if (colony.repairs.size() != before) ColonyManager.markDirty(level);
        return colony.repairs.size() - before;
    }

    private static void auditBlock(ServerLevel level, Colony colony, BlockPos p, BlockState s, BoundingBox within) {
        if (within != null && !within.isInside(p)) return;
        if (!level.isLoaded(p)) return;
        if (s.isAir() || s.canBeReplaced() || s.is(Blocks.JIGSAW) || s.is(Blocks.STRUCTURE_BLOCK) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND)) return;
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(p) || Protection.inPlayerOwnedJob(level, p) || data.isPlayerBlock(p)) return;
        BlockState w = level.getBlockState(p);
        if (w.getBlock() == s.getBlock() || !(w.isAir() || w.canBeReplaced())) return;
        for (ConstructionJob job : colony.jobs) {
            if (HousePlanner.activePlot(job) && !job.countsAsCompleted() && job.contains(p)) return;
        }
        recordRepairs(level, colony, restoreSnapshot(level, p, s));
    }

    public static void clearCaches() {
        synchronized (HOUSE_CACHE) {
            HOUSE_CACHE.clear();
        }
    }
}
