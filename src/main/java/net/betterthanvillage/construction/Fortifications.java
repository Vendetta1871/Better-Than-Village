package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.FortBlock;
import net.betterthanvillage.colony.FortificationState;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.defense.RaidShelter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Walls and gates: planning tick, ring layout, wall builder selection, job queue and gate control (05 §4). */
public final class Fortifications {
    public static final int FAILURE_RETRY = 1200;

    private Fortifications() {
    }

    /** Runtime-only wall state of a colony (retry map, failure counts, caches). */
    static final class Runtime {
        final Map<BlockPos, Long> retry = new HashMap<>();
        final Map<BlockPos, Integer> failures = new HashMap<>();
        final Map<BlockState, Integer> stockCache = new HashMap<>();
        long stockTime = Long.MIN_VALUE;
        long builderTime = Long.MIN_VALUE;
        UUID builder;
        boolean builderHasJob;
        /** Wall gates the gate controller opened (only those are closed again). */
        final java.util.Set<BlockPos> opened = new java.util.HashSet<>();
    }

    static Runtime runtime(Colony colony) {
        return colony.runtime("btv_walls", Runtime::new);
    }

    // ---------------------------------------------------------------- queries

    public static boolean hasPendingWall(Colony colony) {
        return !colony.fortifications.pending.isEmpty();
    }

    /** Registered (built) wall block of any colony. */
    public static boolean isWallBlock(ServerLevel level, BlockPos pos) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (!c.fortifications.managed.isEmpty() && c.fortifications.managed.containsKey(pos)) return true;
        }
        return false;
    }

    /** A fence gate that is a built wall gate of any colony (05 §4.6). */
    public static boolean isWallGate(ServerLevel level, BlockPos pos) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            FortBlock b = c.fortifications.managed.get(pos);
            if (b != null && b.state().getBlock() instanceof FenceGateBlock) return true;
        }
        return false;
    }

    /** |dist(core, pos) - wallRadius| <= margin + 2. */
    public static boolean isNearWall(Colony colony, BlockPos pos, int margin) {
        int r = colony.fortifications.radius;
        if (r == 0) return false;
        double dx = pos.getX() - colony.core().getX(), dz = pos.getZ() - colony.core().getZ();
        return Math.abs(Math.sqrt(dx * dx + dz * dz) - r) <= margin + 2;
    }

    /** Pathfinding: wall gates for villagers/golems; any non-pen fence gate for colony villagers (05 §4.6). */
    public static boolean gateWalkable(Mob mob, PathfindingContext context, int x, int y, int z) {
        if (!(mob.level() instanceof ServerLevel level) || !(mob instanceof Villager || mob instanceof IronGolem)) return false;
        BlockPos p = new BlockPos(x, y, z);
        if (!(context.getBlockState(p).getBlock() instanceof FenceGateBlock)) return false;
        if (isWallGate(level, p)) return true;
        if (!(mob instanceof Villager v)) return false;
        if (ColonyManager.colonyAt(level, v.blockPosition()) == null && ColonyManager.foundingPartyColony(level, v.getUUID()) == null) return false;
        Colony at = ColonyManager.colonyAt(level, p);
        return at == null || at.livestockPen == null || !at.livestockPen.north(5).equals(p);
    }

    /** Raid active at the colony (raid shelter test, or an unfinished active raid near the core). */
    public static boolean raidActive(ServerLevel level, Colony colony) {
        if (RaidShelter.isActive(level, colony)) return true;
        Raid raid = level.getRaidAt(colony.core());
        if (raid == null || raid.isOver() || raid.isStopped() || !raid.isActive()) return false;
        double max = Math.min(1024, colony.radius() + 32);
        return raid.getCenter().distSqr(colony.core()) <= max * max;
    }

    // ---------------------------------------------------------------- planning tick

    /** Called every tick per active colony. */
    public static void tick(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now % 5 == 0) {
            gateControl(level, colony);
        }
        FortificationState f = colony.fortifications;
        ColonyConfig cfg = ColonyConfig.get();
        if (!cfg.roles.enable_village_walls || now < f.nextPlan || !HousePlanner.established(level, colony) || !colony.hasStorage() || raidActive(level, colony)) return;
        f.nextPlan = now + 1200;
        int desired = neededRadius(level, colony);
        if (f.radius != 0 && desired <= f.radius) {
            planRing(level, colony);
            for (FortBlock b : f.managed.values()) {
                if (level.isLoaded(b.pos()) && level.getBlockState(b.pos()).isAir()) {
                    f.pending.putIfAbsent(b.pos(), new FortBlock(b.pos(), b.state(), false));
                }
            }
        } else {
            if (desired > colony.radius() - 4) {
                if (desired + 8 > cfg.village.expansion_radius_max) return;
                colony.growRadius(desired + 8);
            }
            relocate(level, colony, desired);
        }
        ColonyManager.markDirty(level);
    }

    /** Wall radius needed: 24 enlarged to every village piece / plot / farmland / storage / pen corner, rounded up (05 §4.2). */
    public static int neededRadius(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        double r = 24;
        StructureStart start = level.structureManager().getStructureWithPieceAt(core, StructureTags.VILLAGE);
        if (start.isValid()) {
            for (StructurePiece piece : start.getPieces()) {
                r = Math.max(r, cornerDist(core, piece.getBoundingBox()));
            }
        }
        for (ConstructionJob job : colony.jobs) {
            if (job.status == net.betterthanvillage.colony.JobStatus.BLOCKED && job.retryAfter == 0) continue;
            r = Math.max(r, cornerDist(core, job.box()));
        }
        for (BlockPos p : colony.farmland) r = Math.max(r, dist(core, p.getX(), p.getZ()));
        for (BlockPos p : colony.managedStorages.keySet()) r = Math.max(r, dist(core, p.getX(), p.getZ()));
        BlockPos pen = colony.livestockPen;
        if (pen != null) {
            r = Math.max(r, cornerDist(core, new BoundingBox(pen.getX() - 5, 0, pen.getZ() - 5, pen.getX() + 5, 0, pen.getZ() + 5)));
        }
        return (int) Math.ceil((r + 8) / 8) * 8;
    }

    private static double dist(BlockPos core, int x, int z) {
        double dx = x - core.getX(), dz = z - core.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double cornerDist(BlockPos core, BoundingBox b) {
        return Math.max(Math.max(dist(core, b.minX(), b.minZ()), dist(core, b.maxX(), b.minZ())),
                Math.max(dist(core, b.minX(), b.maxZ()), dist(core, b.maxX(), b.maxZ())));
    }

    /** Relocation: clear the plan, queue removal of every built block, set the radius and plan the new ring. */
    public static void relocate(ServerLevel level, Colony colony, int radius) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        f.pending.clear();
        rt.retry.clear();
        rt.failures.clear();
        f.abandoned.clear();
        f.surveyed.clear();
        for (FortBlock b : f.managed.values()) {
            f.pending.put(b.pos(), new FortBlock(b.pos(), b.state(), true));
        }
        f.radius = radius;
        planRing(level, colony);
        ColonyManager.markDirty(level);
    }

    /** Expansion check when a plot site is accepted (05 §4.3). */
    public static void onPlotAccepted(ServerLevel level, Colony colony, int x0, int z0, int w, int d) {
        FortificationState f = colony.fortifications;
        if (f.radius == 0) return;
        int need = neededFor(colony, x0, z0, w, d);
        if (need <= f.radius) return;
        if (need > colony.radius() - 4) {
            if (need + 8 > ColonyConfig.get().village.expansion_radius_max) return;
            colony.growRadius(need + 8);
        }
        relocate(level, colony, need);
    }

    /** Wall radius needed to enclose a footprint (plus 4) with the usual 8-block margin. */
    private static int neededFor(Colony colony, int x0, int z0, int w, int d) {
        double max = cornerDist(colony.core(), new BoundingBox(x0 - 4, 0, z0 - 4, x0 + w - 1 + 4, 0, z0 + d - 1 + 4));
        return (int) Math.ceil((max + 8) / 8) * 8;
    }

    /** The wall ring crosses the footprint (within 1 block) and cannot be moved out (radius cap). */
    public static boolean wallBlocksPlot(Colony colony, int x0, int z0, int w, int d) {
        int r = colony.fortifications.radius;
        if (r == 0) return false;
        BlockPos core = colony.core();
        int minX = x0 - 1, minZ = z0 - 1, maxX = x0 + w, maxZ = z0 + d;
        double nx = Math.max(minX, Math.min(core.getX(), maxX)) - core.getX();
        double nz = Math.max(minZ, Math.min(core.getZ(), maxZ)) - core.getZ();
        double near = Math.sqrt(nx * nx + nz * nz);
        double far = cornerDist(core, new BoundingBox(minX, 0, minZ, maxX, 0, maxZ));
        if (near > r + 1 || far < r - 1) return false;
        return neededFor(colony, x0, z0, w, d) + 8 > ColonyConfig.get().village.expansion_radius_max;
    }

    /** Postpones a wall job without counting a failure. */
    static void postpone(Colony colony, FortBlock job, long now) {
        runtime(colony).retry.put(job.pos(), now + FAILURE_RETRY);
    }

    // ---------------------------------------------------------------- ring layout

    /** Wall materials by villager type at the core: base, infill, pillar, gate. */
    record Materials(BlockState base, BlockState infill, BlockState pillar, BlockState gate) {
    }

    static Materials materials(ServerLevel level, Colony colony) {
        VillagerType type = VillagerType.byBiome(level.getBiome(colony.core()));
        String key = BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath();
        return switch (key) {
            case "desert" -> new Materials(Blocks.SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(),
                    Blocks.CHISELED_SANDSTONE.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState());
            case "savanna" -> new Materials(Blocks.COBBLESTONE.defaultBlockState(), Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
                    Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_FENCE_GATE.defaultBlockState());
            case "snow" -> new Materials(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
            case "taiga" -> new Materials(Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
            default -> new Materials(Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState());
        };
    }

    /** Ring columns (x, 0, z) of radius r around the core, insertion ordered. */
    static LinkedHashSet<BlockPos> ringColumns(BlockPos core, int r) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        int cx = core.getX(), cz = core.getZ();
        for (int dx = -r; dx <= r; dx++) {
            int dz = (int) Math.round(Math.sqrt((double) r * r - (double) dx * dx));
            out.add(new BlockPos(cx + dx, 0, cz + dz));
            out.add(new BlockPos(cx + dx, 0, cz - dz));
            out.add(new BlockPos(cx + dz, 0, cz + dx));
            out.add(new BlockPos(cx - dz, 0, cz + dx));
        }
        return out;
    }

    /** Adds build jobs for every loaded ring column not planned before (05 §4.3). */
    static void planRing(ServerLevel level, Colony colony) {
        FortificationState f = colony.fortifications;
        if (f.radius <= 0) return;
        Materials m = materials(level, colony);
        BlockPos core = colony.core();
        for (BlockPos col : ringColumns(core, f.radius)) {
            if (f.surveyed.contains(col) || !Terrain.loaded(level, col.getX(), col.getZ())) continue;
            f.surveyed.add(col);
            int x = col.getX(), z = col.getZ();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int i = 0; i < 20 && y > level.getMinBuildHeight(); i++) {
                BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
                if (below.canBeReplaced() || below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS)) {
                    y--;
                } else {
                    break;
                }
            }
            BlockState ground = level.getBlockState(new BlockPos(x, y - 1, z));
            if (!Terrain.natural(ground) && !Terrain.isRoad(ground)) continue;
            boolean xCross = Math.abs(x - core.getX()) <= 2;
            List<FortBlock> blocks = new ArrayList<>();
            if (xCross || Math.abs(z - core.getZ()) <= 1 || ground.is(Blocks.DIRT_PATH)) {
                Direction facing = xCross ? Direction.NORTH : Direction.EAST;
                blocks.add(new FortBlock(new BlockPos(x, y, z), m.gate().setValue(FenceGateBlock.FACING, facing), false));
                if (xCross && z > core.getZ()) {
                    blocks.add(new FortBlock(new BlockPos(x, y + 4, z), m.base(), false));
                }
            } else {
                boolean pillar = Math.floorMod(x + z, 6) == 0;
                blocks.add(new FortBlock(new BlockPos(x, y, z), m.base(), false));
                blocks.add(new FortBlock(new BlockPos(x, y + 1, z), pillar ? m.pillar() : m.infill(), false));
                blocks.add(new FortBlock(new BlockPos(x, y + 2, z), pillar ? m.pillar() : m.infill(), false));
                if (pillar) blocks.add(new FortBlock(new BlockPos(x, y + 3, z), m.base(), false));
            }
            for (FortBlock b : blocks) {
                if (f.abandoned.contains(b.pos()) || f.pending.size() >= 32768) continue;
                FortBlock existing = f.pending.get(b.pos());
                if (existing == null || !existing.remove()) {
                    f.pending.put(b.pos(), b);
                } else if (f.managed.containsKey(b.pos()) && f.managed.get(b.pos()).state() == b.state()) {
                    // The old wall already has this exact block here: keep it instead of removing it.
                    f.pending.remove(b.pos());
                }
            }
        }
    }

    // ---------------------------------------------------------------- jobs

    /** Accepted item for a wall block: logs/gates take any #logs; others any #stone_crafting_materials or sandstone. */
    public static boolean accepts(BlockState state, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(state.getBlock().asItem())) return true;
        if (state.is(BlockTags.LOGS) || state.getBlock() instanceof FenceGateBlock) return stack.is(ItemTags.LOGS);
        return stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || stack.is(Items.SANDSTONE) || stack.is(Items.RED_SANDSTONE);
    }

    /** Storage count of accepted material for a state (cached for 20 ticks). */
    static int stock(ServerLevel level, Colony colony, BlockState state) {
        Runtime rt = runtime(colony);
        long now = level.getGameTime();
        if (now - rt.stockTime >= 20 || now < rt.stockTime) {
            rt.stockCache.clear();
            rt.stockTime = now;
        }
        return rt.stockCache.computeIfAbsent(state, s -> ColonyStorage.count(level, colony, st -> accepts(s, st)));
    }

    /** Job lies within 2 (x/z) of an active plot box or within distance^2 4 of the cell above a road node. */
    static boolean nearWork(Colony colony, BlockPos pos) {
        for (ConstructionJob job : colony.jobs) {
            if (!HousePlanner.activePlot(job)) continue;
            BoundingBox b = job.box();
            if (pos.getX() >= b.minX() - 2 && pos.getX() <= b.maxX() + 2 && pos.getZ() >= b.minZ() - 2 && pos.getZ() <= b.maxZ() + 2) return true;
        }
        for (BlockPos r : colony.roadQueue) {
            if (r.above().distSqr(pos) <= 4) return true;
        }
        return false;
    }

    /** Selects the next wall job for a villager (05 §4.4); null if none. */
    static FortBlock selectJob(ServerLevel level, Colony colony, BlockPos from, boolean requireNear) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        long now = level.getGameTime();
        rt.retry.values().removeIf(t -> t <= now);
        boolean removals = false;
        for (FortBlock b : f.pending.values()) {
            if (b.remove() && !rt.retry.containsKey(b.pos()) && level.isLoaded(b.pos())) {
                removals = true;
                break;
            }
        }
        FortBlock best = null;
        boolean bestNear = false;
        double bestD = Double.MAX_VALUE;
        for (FortBlock b : f.pending.values()) {
            if (removals && !b.remove()) continue;
            if (rt.retry.containsKey(b.pos()) || !level.isLoaded(b.pos())) continue;
            if (!b.remove() && stock(level, colony, b.state()) <= stockReserve(colony)) continue;
            boolean near = nearWork(colony, b.pos());
            if (requireNear && !b.remove() && !near) continue;
            boolean priority = b.remove() && near;
            double d = from == null ? 0 : b.pos().distSqr(from);
            if (best == null || (priority && !bestNear) || (priority == bestNear && d < bestD)) {
                best = b;
                bestNear = priority;
                bestD = d;
            }
        }
        return best;
    }

    static boolean hasRemoval(Colony colony) {
        for (FortBlock b : colony.fortifications.pending.values()) {
            if (b.remove()) return true;
        }
        return false;
    }

    /** Alive adult builders in the colony area box sorted by UUID. */
    static List<Villager> sortedBuilders(ServerLevel level, Colony colony) {
        List<Villager> list = new ArrayList<>(SiteSearch.builders(level, colony));
        list.sort(Comparator.comparing(Villager::getUUID));
        return list;
    }

    /** Active wall builder test (05 §4.4), cached per tick. */
    public static boolean isWallBuilder(ServerLevel level, Colony colony, Villager v) {
        FortificationState f = colony.fortifications;
        if (f.pending.isEmpty() || !ColonyConfig.get().roles.enable_village_walls) return false;
        Runtime rt = runtime(colony);
        long now = level.getGameTime();
        if (rt.builderTime != now) {
            rt.builderTime = now;
            rt.builder = null;
            rt.builderHasJob = false;
            boolean removal = hasRemoval(colony);
            if ((removal || colony.roadQueue.isEmpty()) && !Damage.hasActionableRepair(level, colony)
                    && HousePlanner.established(level, colony) && !raidActive(level, colony)) {
                List<Villager> builders = sortedBuilders(level, colony);
                if (!builders.isEmpty()) {
                    Villager last = builders.get(builders.size() - 1);
                    boolean single = builders.size() == 1 && colony.hasActiveConstruction();
                    if (!(single && !removal)) {
                        rt.builder = last.getUUID();
                        rt.builderHasJob = selectJob(level, colony, last.blockPosition(), single) != null;
                    }
                }
            }
        }
        return rt.builderHasJob && v.getUUID().equals(rt.builder);
    }

    /** Material the wall leaves in storage: houses come first while one is under construction. */
    static int stockReserve(Colony colony) {
        return colony.hasActiveConstruction() ? 128 : 32;
    }

    /** Requires a single builder with an active project to stay near the current works. */
    static boolean requireNear(ServerLevel level, Colony colony) {
        return sortedBuilders(level, colony).size() == 1 && colony.hasActiveConstruction();
    }

    /** Failure: retry after 1200 ticks, abandoned after 3 failures. */
    static void fail(Colony colony, FortBlock job, long now) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        int n = rt.failures.merge(job.pos(), 1, Integer::sum);
        if (n >= 3) {
            f.pending.remove(job.pos());
            rt.retry.remove(job.pos());
            rt.failures.remove(job.pos());
            if (f.abandoned.size() < 32768) f.abandoned.add(job.pos());
            f.skipped++;
        } else {
            rt.retry.put(job.pos(), now + FAILURE_RETRY);
        }
    }

    /** Completion of a wall job (05 §4.4). */
    static void complete(ServerLevel level, Colony colony, FortBlock job, boolean placed) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        f.pending.remove(job.pos());
        rt.retry.remove(job.pos());
        rt.failures.remove(job.pos());
        if (job.remove()) {
            f.managed.remove(job.pos());
        } else if (placed) {
            if (f.managed.size() < 16384) f.managed.put(job.pos(), job);
        } else {
            f.skipped++;
        }
        colony.repairs.remove(job.pos());
        ColonyManager.markDirty(level);
    }

    /** Abandons every build job in the x/z column of a position. */
    static void abandonColumn(Colony colony, BlockPos pos) {
        FortificationState f = colony.fortifications;
        List<BlockPos> drop = new ArrayList<>();
        for (FortBlock b : f.pending.values()) {
            if (!b.remove() && b.pos().getX() == pos.getX() && b.pos().getZ() == pos.getZ()) drop.add(b.pos());
        }
        for (BlockPos p : drop) {
            f.pending.remove(p);
            if (f.abandoned.size() < 32768) f.abandoned.add(p);
            f.skipped++;
        }
    }

    // ---------------------------------------------------------------- gates

    /** Opens wall gates for nearby villagers/golems and closes them otherwise (05 §4.6). */
    static void gateControl(ServerLevel level, Colony colony) {
        java.util.Set<BlockPos> opened = runtime(colony).opened;
        for (FortBlock b : colony.fortifications.managed.values()) {
            if (!(b.state().getBlock() instanceof FenceGateBlock) || !level.isLoaded(b.pos())) continue;
            BlockState s = level.getBlockState(b.pos());
            if (!(s.getBlock() instanceof FenceGateBlock) || s.getValue(FenceGateBlock.POWERED)) continue;
            boolean isOpen = s.getValue(FenceGateBlock.OPEN);
            boolean colonist = wanted(level, b.pos());
            boolean want = colonist || playerAt(level, b.pos());
            if (want && !isOpen) {
                SafeEditScope.run(() -> level.setBlock(b.pos(), s.setValue(FenceGateBlock.OPEN, true), 3));
                opened.add(b.pos());
            } else if (colonist && isOpen && !playerAt(level, b.pos())) {
                // an open gate in colony use is closed after it (also after a restart lost the tracking)
                opened.add(b.pos());
            } else if (!want && isOpen && opened.remove(b.pos())) {
                // only gates opened here are closed again; a gate a player opened stays open
                SafeEditScope.run(() -> level.setBlock(b.pos(), s.setValue(FenceGateBlock.OPEN, false), 3));
            } else if (!isOpen) {
                opened.remove(b.pos());
            }
        }
    }

    private static boolean wanted(ServerLevel level, BlockPos gate) {
        AABB box = new AABB(gate);
        AABB near = box.inflate(1);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box.inflate(6, 2, 6), e -> e.isAlive() && (e instanceof Villager || e instanceof IronGolem))) {
            if (mob instanceof Villager v && (v.isSleeping() || v.isTrading())) continue;
            if (mob.getBoundingBox().intersects(near)) return true;
            Path path = mob.getNavigation().getPath();
            if (path != null && !path.isDone()) {
                for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 8); i++) {
                    Node n = path.getNode(i);
                    if (n.asBlockPos().distSqr(gate) <= 9) return true;
                }
            }
        }
        return false;
    }

    /** A player stands in or next to the gate. */
    private static boolean playerAt(ServerLevel level, BlockPos gate) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, new AABB(gate).inflate(1, 1, 1), p -> !p.isSpectator()).isEmpty();
    }

    /** Living entity occupying a block. */
    static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e.isAlive() && !e.isSpectator()).isEmpty();
    }

    static boolean blockedPosition(ServerLevel level, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos)) return true;
        if (data.isPlayerBlock(pos) && !level.getBlockState(pos).isAir()) return true;
        if (level.getBlockEntity(pos) != null) return true;
        if (level.getBlockState(pos).is(net.betterthanvillage.registry.ModTags.BUILDER_PROTECTED)) return true;
        return net.betterthanvillage.colony.Protection.beaconNear(level, pos);
    }
}
