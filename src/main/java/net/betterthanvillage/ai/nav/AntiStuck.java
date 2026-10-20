package net.betterthanvillage.ai.nav;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.Terrain;
import net.betterthanvillage.gather.GatherUtil;
import net.betterthanvillage.gather.MinerTask;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Anti-stuck monitor (pit escape, nudges, detours) and emergency teleport (03 §5.3). */
public final class AntiStuck {
    public static final String STATUS_PIT = "выход из небольшой ямы";

    private static final Map<Villager, State> STATES = new WeakHashMap<>();
    private static final Map<Villager, Pit> PITS = new WeakHashMap<>();
    private static final Map<Villager, Long> SUPPRESSED = new WeakHashMap<>();

    private static final class State {
        BlockPos target;
        Vec3 lastPos;
        long start;
        long lastMoved;
        long lastCloser;
        long lastNudge = Long.MIN_VALUE / 2;
        int attempts;
        int edits;
        double best;
    }

    private record Pit(BlockPos start, BlockPos exit, long time) {
    }

    private enum Fix { NONE, MOVED, EDITED }

    private AntiStuck() {
    }

    public static void monitor(ServerLevel level, Colony colony, Villager v) {
        long now = level.getGameTime();
        if ((now + v.getId()) % 20 != 0) return;
        Long until = SUPPRESSED.get(v);
        if (until != null && now < until) {
            STATES.remove(v);
            return;
        }
        ColonyRole role = colony.roleOf(v.getUUID());
        if (role == ColonyRole.WARRIOR && v.getBrain().hasMemoryValue(ModRegistries.THREAT_TARGET.get())) {
            STATES.remove(v);
            return;
        }
        if (v.isSleeping() || v.getTradingPlayer() != null || Math.floorMod(level.getDayTime(), 24000L) >= 9000) {
            STATES.remove(v);
            PITS.remove(v);
            return;
        }
        if (BlockWorkTimer.isBusyBreaking(v, now)) {
            STATES.remove(v);
            PITS.remove(v);
            return;
        }
        if (v.onGround() && pitEscape(level, v, now)) {
            STATES.remove(v);
            return;
        }
        if (Nav.stuck(v, now)) {
            if (emergencyTeleport(level, colony, v)) {
                STATES.remove(v);
                return;
            }
        }
        BlockPos target = Nav.currentTarget(v, now);
        // A villager that stands still on purpose (fishing at the shore, working a block face) is not
        // stuck: its target memory may be a few blocks away while it has no movement request at all.
        if (target == null && v.getNavigation().isDone()) {
            STATES.remove(v);
            return;
        }
        // Only movement owned by a colony task is watched: vanilla walks (and the storage position the sensor
        // keeps for everyone) are none of our business.
        if (target == null && VillagerUtil.isRunning(v, ColonyTask.class)) {
            for (MemoryModuleType<BlockPos> mem : ModRegistries.targetMemories()) {
                if (mem == ModRegistries.STORAGE_CHEST_POS.get()) continue;
                Optional<BlockPos> value = v.getBrain().getMemory(mem);
                if (value.isPresent()) {
                    target = value.get();
                    break;
                }
            }
        }
        if (target == null) {
            STATES.remove(v);
            return;
        }
        double d2 = v.position().distanceToSqr(Vec3.atBottomCenterOf(target));
        if (d2 <= 9) {
            STATES.remove(v);
            return;
        }
        State s = STATES.get(v);
        if (s == null || s.target.distSqr(target) > 4) {
            s = new State();
            s.target = target.immutable();
            s.lastPos = v.position();
            s.start = now;
            s.lastMoved = now;
            s.lastCloser = now;
            s.best = d2;
            STATES.put(v, s);
            return;
        }
        boolean moved = v.position().distanceToSqr(s.lastPos) >= 0.16;
        boolean closer = d2 + 1 < s.best;
        if (moved || closer) {
            s.lastPos = v.position();
            if (moved) s.lastMoved = now;
            if (closer) s.lastCloser = now;
            s.attempts = 0;
            s.edits = 0;
            s.best = Math.min(s.best, d2);
            return;
        }
        long stuckFor = now - s.lastMoved;
        boolean airborne = !v.onGround() && now - s.start >= 100 && !groundBelow(level, v.blockPosition());
        if (!airborne && (stuckFor < 360 || (!v.getNavigation().isDone() && s.attempts < 3))) {
            if (stuckFor >= 100 && now - s.lastNudge >= 60) {
                nudge(level, colony, v, s, role == ColonyRole.MINER, now, d2);
            }
        } else if (emergencyTeleport(level, colony, v)) {
            STATES.remove(v);
        }
    }

    /** A fluid-free full solid top within 12 blocks below. */
    private static boolean groundBelow(ServerLevel level, BlockPos p) {
        for (int i = 1; i <= 12; i++) {
            if (GatherUtil.solidFloor(level, p.below(i))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- pit escape

    /** Returns true when the villager is in a small pit (handled here). */
    private static boolean pitEscape(ServerLevel level, Villager v, long now) {
        // Feet cell: on a dirt path (15/16 tall) blockPosition() is the path block itself.
        BlockPos p = BlockPos.containing(v.getX(), v.getY() + 0.5, v.getZ());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            // Carpet and slab floors are not "standable" cells but plain walkable ground; a step down is no pit wall either.
            if (Nav.standable(level, n) || Nav.standable(level, n.below())
                    || (level.isLoaded(n) && WalkNodeEvaluator.getPathTypeStatic(v, n) == PathType.WALKABLE)) {
                PITS.remove(v);
                return false;
            }
        }
        BlockPos exit = null;
        search:
        for (int r = 1; r <= 3; r++) {
            for (int dy = 1; dy <= 2; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos c = p.offset(dx, dy, dz);
                        if (Nav.standable(level, c)) {
                            exit = c;
                            break search;
                        }
                    }
                }
            }
        }
        if (exit == null) {
            PITS.remove(v);
            return false;
        }
        Pit pit = PITS.get(v);
        if (pit == null || pit.start().distSqr(p) > 4 || !pit.exit().equals(exit)) {
            pit = new Pit(p, exit, now);
            PITS.put(v, pit);
        }
        if (now - pit.time() >= 80) {
            GatherUtil.teleport(v, exit);
            PITS.remove(v);
            WorkTracker.status(v, STATUS_PIT);
        } else if (PathBudget.tryAcquire(level)) {
            v.getNavigation().moveTo(exit.getX() + 0.5, exit.getY(), exit.getZ() + 0.5, 0.9);
        }
        return true;
    }

    // ---------------------------------------------------------------- nudges

    private static void nudge(ServerLevel level, Colony colony, Villager v, State s, boolean miner, long now, double d2) {
        BlockPos p = v.blockPosition();
        BlockPos t = s.target;
        int dx = t.getX() - p.getX();
        int dz = t.getZ() - p.getZ();
        Direction primary;
        if (dx == 0 && dz == 0) primary = Direction.NORTH;
        else if (Math.abs(dx) >= Math.abs(dz)) primary = dx > 0 ? Direction.EAST : Direction.WEST;
        else primary = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        Fix r = Fix.NONE;
        if (dx == 0 && dz == 0) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                r = localFix(level, v, p, t, d, s.edits, miner);
                if (r != Fix.NONE) break;
            }
        } else if (s.attempts % 2 == 0) {
            r = localFix(level, v, p, t, primary, s.edits, miner);
        }
        if (r == Fix.NONE) {
            r = detour(level, colony, v, p, t, primary);
            if (r == Fix.NONE) r = localFix(level, v, p, t, primary, s.edits, miner);
        }
        if (r == Fix.EDITED) v.swing(InteractionHand.MAIN_HAND);
        s.lastNudge = now;
        s.attempts++;
        if (r == Fix.EDITED) s.edits++;
        s.best = Math.min(s.best, d2);
    }

    /** Local fix toward direction d (miners never break blocks here). */
    private static Fix localFix(ServerLevel level, Villager v, BlockPos p, BlockPos t, Direction d, int e, boolean miner) {
        BlockPos n = p.relative(d);
        if (t.getY() > p.getY()) {
            if (Nav.standable(level, n.above())) {
                walk(v, n.above());
                return Fix.MOVED;
            }
            if (!miner && e < 3 && solid(level, n.above()) && !solid(level, n.above(2))
                    && digSafe(level, v, n.above()) && SafeEdit.breakBlock(level, v, n.above())) {
                return Fix.EDITED;
            }
            if (e < 3 && level.getBlockState(n).isAir() && level.getBlockState(n.above()).isAir() && placeStep(level, v, n)) {
                return Fix.EDITED;
            }
        } else if (t.getY() < p.getY()) {
            if (Nav.standable(level, n.below())) {
                walk(v, n.below());
                return Fix.MOVED;
            }
            if (Nav.standable(level, n.below(2))) {
                walk(v, n.below(2));
                return Fix.MOVED;
            }
        }
        if (e < 3 && !solid(level, n) && !level.getBlockState(n.below()).isFaceSturdy(level, n.below(), Direction.UP)
                && (Nav.standable(level, p.relative(d, 2)) || Nav.standable(level, p.relative(d, 3)))
                && placeStep(level, v, n.below())) {
            return Fix.EDITED;
        }
        if (!miner && e < 2 && t.getY() <= p.getY()) {
            for (BlockPos b : new BlockPos[]{n.above(), n}) {
                if (solid(level, b) && digSafe(level, v, b) && SafeEdit.breakBlock(level, v, b)) return Fix.EDITED;
            }
        }
        return Fix.NONE;
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static void walk(Villager v, BlockPos c) {
        v.getNavigation().moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0.85);
    }

    private static boolean digSafe(ServerLevel level, Villager v, BlockPos b) {
        if (!level.isLoaded(b) || !Protection.terrainModifiable(level, b) || level.getBlockEntity(b) != null) return false;
        if (b.getY() < v.getBlockY() || b.equals(v.blockPosition().below())) return false;
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(b.above())).isEmpty()) return false;
        BlockState s = level.getBlockState(b);
        if (!(s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.TUFF) || s.is(Blocks.SNOW_BLOCK))) {
            return false;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = b.relative(d);
            if (!level.getFluidState(n).isEmpty()) return false;
            BlockState ns = level.getBlockState(n);
            if (ns.is(Blocks.DIRT_PATH) || ns.is(Blocks.FARMLAND) || ns.is(BlockTags.BEDS) || ns.is(BlockTags.DOORS)) return false;
            String id = BuiltInRegistries.BLOCK.getKey(ns.getBlock()).getPath();
            if (id.contains("planks") || id.contains("fence") || id.contains("brick")) return false;
        }
        return !GatherUtil.inStructure(level, b, 3, 3, 3);
    }

    /** Places COBBLESTONE or DIRT (first inventory slot holding either) at an insensitive air cell. */
    private static boolean placeStep(ServerLevel level, Villager v, BlockPos cell) {
        if (!level.getBlockState(cell).isAir() || level.getBlockEntity(cell) != null) return false;
        for (Direction d : Direction.values()) {
            BlockState ns = level.getBlockState(cell.relative(d));
            if (ns.is(Blocks.DIRT_PATH) || ns.is(Blocks.FARMLAND) || ns.is(BlockTags.BEDS) || ns.is(BlockTags.DOORS)) return false;
        }
        if (GatherUtil.inStructure(level, cell, 2, 2, 2)) return false;
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(Items.COBBLESTONE) || st.is(Items.DIRT)) {
                Block block = st.is(Items.COBBLESTONE) ? Blocks.COBBLESTONE : Blocks.DIRT;
                if (!SafeEdit.place(level, cell, block.defaultBlockState(), 3)) return false;
                st.shrink(1);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    private static Fix detour(ServerLevel level, Colony colony, Villager v, BlockPos p, BlockPos t, Direction d) {
        double pt = p.distSqr(t);
        long lim = colony.radius() - 2;
        BlockPos ahead = p.relative(d);
        List<BlockPos> cands = new ArrayList<>();
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2}) {
                        BlockPos c = p.offset(dx, dy, dz);
                        if (GatherUtil.hDist2(c, colony.core()) > lim * lim) continue;
                        if (c.equals(ahead) || !Nav.standable(level, c)) continue;
                        if (c.distSqr(t) > pt + 16 || !GatherUtil.freeOfLiving(level, c, v)) continue;
                        cands.add(c);
                        break;
                    }
                }
            }
        }
        cands.sort(Comparator.comparingDouble(c -> c.distSqr(t) + 0.25 * c.distSqr(p)));
        for (int i = 0; i < Math.min(6, cands.size()); i++) {
            if (!PathBudget.tryAcquire(level)) break;
            Path path = v.getNavigation().createPath(cands.get(i), 0);
            if (path != null && path.canReach() && v.getNavigation().moveTo(path, 0.85)) return Fix.MOVED;
        }
        return Fix.NONE;
    }

    // ---------------------------------------------------------------- suppression

    /** True while the villager is suppressed (forced idle). */
    public static boolean isForcedIdle(Villager v, long now) {
        Long until = SUPPRESSED.get(v);
        if (until == null) return false;
        if (now < until) return true;
        SUPPRESSED.remove(v);
        return false;
    }

    public static void suppress(Villager v, long until) {
        SUPPRESSED.put(v, until);
    }

    // ---------------------------------------------------------------- emergency teleport

    /** Teleports the villager to a safe cell near the core; returns true on success. */
    public static boolean emergencyTeleport(ServerLevel level, Colony colony, Villager v) {
        BlockPos dest = teleportDestination(level, colony, v, true);
        if (dest == null) dest = teleportDestination(level, colony, v, false);
        if (dest == null) return false;
        level.sendParticles(ParticleTypes.PORTAL, v.getX(), v.getY() + v.getBbHeight() / 2, v.getZ(), 16, 0.3, 0.6, 0.3, 0.1);
        v.getNavigation().stop();
        if (colony.roleOf(v.getUUID()) == ColonyRole.MINER) {
            v.addTag(MinerTask.EMERGENCY_TAG);
        } else {
            Brain<Villager> brain = v.getBrain();
            brain.eraseMemory(ModRegistries.BUILD_TARGET_POS.get());
            brain.eraseMemory(ModRegistries.TREE_TARGET_POS.get());
            brain.eraseMemory(ModRegistries.FARM_TARGET_POS.get());
            brain.eraseMemory(ModRegistries.FISH_TARGET_POS.get());
            brain.eraseMemory(ModRegistries.ANIMAL_TARGET_POS.get());
            brain.eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
            suppress(v, level.getGameTime() + 200);
        }
        GatherUtil.teleport(v, dest);
        level.sendParticles(ParticleTypes.PORTAL, dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5, 16, 0.3, 0.6, 0.3, 0.1);
        return true;
    }

    /**
     * Open-sky ground cell near the core: standable, within 4 blocks of the core's height, on natural ground
     * or road (never a roof), outside every construction box. The first pass accepts road floors only.
     */
    private static BlockPos teleportDestination(ServerLevel level, Colony colony, Villager v, boolean roadOnly) {
        BlockPos core = colony.core();
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = core.getX() + dx, z = core.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos c = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (Math.abs(c.getY() - core.getY()) > 4 || !Nav.standable(level, c)) continue;
                    BlockState floor = level.getBlockState(c.below());
                    boolean road = Terrain.isRoad(floor);
                    if (roadOnly ? !road : !(road || Terrain.natural(floor))) continue;
                    if (GatherUtil.inStructure(level, c, 1, 2, 1) || !GatherUtil.freeOfLiving(level, c, v)) continue;
                    return c;
                }
            }
        }
        return null;
    }
}
