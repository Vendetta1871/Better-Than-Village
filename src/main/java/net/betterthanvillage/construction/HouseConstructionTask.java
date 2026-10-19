package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.registry.ModRegistries;
import net.betterthanvillage.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Builder house construction (05 §2.5 - §2.8). */
public final class HouseConstructionTask extends ColonyTask {
    static final String STATUS_PEN = "стройка пересекает загон: поиск другого участка";
    static final String STATUS_NO_TERRAIN = "изменение мира выключено";
    static final String STATUS_HIGH_SITE = "не начатая стройка на высоком склоне: поиск другого участка";
    static final String STATUS_NO_PROGRESS = "стройка без прогресса: отложена для повторной проверки";
    static final String STATUS_CLEARING = "освобождение места для строящегося блока";
    static final String STATUS_CLEAR_PROTECTED = "расчистка остановлена защитой участка";
    static final String STATUS_SITE_PROTECTED = "участок защищён: ";
    static final String STATUS_OBSTACLE = "помеха на участке: дом будет продолжен, а не брошен";
    static final String STATUS_OCCUPIED = "строящийся блок занят: ";
    static final String STATUS_STORAGE_UNREACHABLE = "путь к складу стройматериалов временно недоступен";
    static final String STATUS_WAIT_MATERIALS = "ожидание стройматериалов";
    static final String STATUS_RESERVE = "сохраняется запас для инструментов и факелов";
    static final String STATUS_NO_APPROACH = "нет безопасного подхода к стройке";
    static final String STATUS_SEARCH_APPROACH = "поиск доступного подхода к стройке";

    /** Runtime builder cooldowns per plot: builder UUID -> until time. */
    private static final Map<ConstructionJob, Map<UUID, Long>> COOLDOWNS = new WeakHashMap<>();
    private static final Pattern STARTED_BLOCKS = Pattern.compile(".*(planks|door|bed).*");

    private final BlockWorkTimer timer = new BlockWorkTimer();
    private ConstructionJob job;
    private List<BuildPlan.Step> plan;
    private long cooldownUntil;
    private long nextActionTime;
    private boolean started;
    private BlockPos stand;
    private long nextStandTime;
    private BlockPos fetchContainer;
    private double fetchBest;
    private long fetchWatchdog;
    /** The last fetch found no material anywhere (a no-progress stall is then not the plot's fault). */
    private boolean starved;
    /** Candidate window of the next stand probe. */
    private int standPage;

    public HouseConstructionTask() {
        super(12000);
    }

    // ---------------------------------------------------------------- start / keep running

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        long now = level.getGameTime();
        if (v.isSleeping() || v.isTrading() || now < cooldownUntil) return false;
        if (!ColonyManager.terrainChangesAllowed(level) || colony.roleOf(v.getUUID()) != ColonyRole.BUILDER) return false;
        if (!HousePlanner.established(level, colony) || !colony.hasStorage() || !colony.roadQueue.isEmpty()) return false;
        if (Damage.hasActionableRepair(level, colony) || Fortifications.isWallBuilder(level, colony, v)) return false;
        ConstructionJob candidate = null;
        for (ConstructionJob j : colony.jobs) {
            if ((j.status == JobStatus.QUEUED || j.status == JobStatus.BUILDING) && (j.builder == null || j.builder.equals(v.getUUID()))
                    && !j.catchup && !onCooldown(j, v.getUUID(), now)) {
                candidate = j;
                break;
            }
        }
        if (candidate == null || !HousePlanner.protectPen(level, colony, candidate)) return false;
        List<BuildPlan.Step> steps = BuildPlan.load(level, candidate);
        if (steps == null) {
            HousePlanner.blockOrAbandon(level, colony, candidate, now + 1200);
            return false;
        }
        job = candidate;
        plan = steps;
        return true;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        HousePlanner.setPlan(job, plan.size(), now);
        if (!v.getUUID().equals(job.builder)) {
            job.lastProgressTick = now;
        }
        job.builder = v.getUUID();
        if (job.status == JobStatus.QUEUED) job.status = JobStatus.BUILDING;
        v.getBrain().setMemory(ModRegistries.COLONY_ROLE.get(), ColonyRole.BUILDER);
        v.getBrain().setMemory(ModRegistries.CURRENT_STRUCTURE_BOUNDS.get(), job.box());
        Colony colony = colony(level, v);
        if (colony != null && !colony.storages.isEmpty()) {
            v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), colony.storages.get(0));
        }
        timer.reset();
        nextActionTime = now;
        stand = null;
        nextStandTime = now;
        fetchContainer = null;
        starved = false;
        started = started(level, job, plan);
        ColonyManager.markDirty(level);
    }

    /** A finished plot, or one where a planks/door/bed block of the plan already stands. */
    static boolean started(ServerLevel level, ConstructionJob job, List<BuildPlan.Step> plan) {
        if (job.countsAsCompleted()) return true;
        if (plan == null) return false;
        for (BuildPlan.Step s : plan) {
            if (s.placement() && level.isLoaded(s.pos()) && STARTED_BLOCKS.matcher(BuildPlan.path(s.state().getBlock())).matches()
                    && BuildPlan.matches(level.getBlockState(s.pos()), s.state())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        if (job == null || v.isSleeping() || v.isTrading()) return false;
        Colony colony = colony(level, v);
        if (colony == null || !colony.jobs.contains(job) || colony.roleOf(v.getUUID()) != ColonyRole.BUILDER) return false;
        if (Fortifications.isWallBuilder(level, colony, v) || Damage.hasActionableRepair(level, colony)) return false;
        return !job.catchup && job.status == JobStatus.BUILDING && v.getUUID().equals(job.builder) && job.nextBlock < job.totalBlocks;
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
        v.getNavigation().stop();
        v.getBrain().eraseMemory(ModRegistries.BUILD_TARGET_POS.get());
        if (job != null) {
            if (job.totalBlocks > 0 && job.nextBlock >= job.totalBlocks && job.status != JobStatus.BLOCKED) {
                HousePlanner.complete(level, job);
            } else if (v.getUUID().equals(job.builder)) {
                job.releaseBuilder();
            }
        }
        job = null;
        plan = null;
    }

    // ---------------------------------------------------------------- tick

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        if (colony == null || job == null) return;
        if (!HousePlanner.protectPen(level, colony, job)) {
            WorkTracker.status(v, STATUS_PEN);
            return;
        }
        if (now < nextActionTime || job.nextBlock >= job.totalBlocks) return;
        if (!ColonyManager.terrainChangesAllowed(level)) {
            WorkTracker.status(v, STATUS_NO_TERRAIN);
            return;
        }
        BuildPlan.Step current = plan.get(job.nextBlock);
        if (!started && job.owner == null && job.origin.getY() > colony.core().getY() + 16 && current.clear()) {
            HousePlanner.abandon(level, colony, job);
            WorkTracker.status(v, STATUS_HIGH_SITE);
            return;
        }
        if (job.lastProgressTick != Long.MIN_VALUE && now - job.lastProgressTick > 1200) {
            WorkTracker.status(v, STATUS_NO_PROGRESS);
            setCooldown(job, v.getUUID(), now + 2400);
            if (starved) {
                HousePlanner.block(job, now + 600);
            } else {
                HousePlanner.blockOrAbandon(level, colony, job, now + 600);
            }
            cooldownUntil = now + 2400;
            ColonyManager.markDirty(level);
            return;
        }
        int skipped = 0;
        while (skipped < 64 && job.nextBlock < job.totalBlocks && BuildPlan.satisfied(level, plan.get(job.nextBlock))) {
            job.nextBlock++;
            skipped++;
            job.lastProgressTick = now;
            job.blockCount = 0;
        }
        if (job.nextBlock >= job.totalBlocks) {
            finish(level, colony);
            return;
        }
        current = plan.get(job.nextBlock);
        if (skipped == 64 && BuildPlan.satisfied(level, current)) return;
        BlockPos pos = current.pos();
        v.getBrain().setMemory(ModRegistries.BUILD_TARGET_POS.get(), pos);
        BlockState world = level.getBlockState(pos);
        BuildCost.Cost cost = cost(level, current);
        if (cost != null && !cost.free() && !BuildPlan.matches(world, current.state())
                && !placementProtected(level, pos, world) && BuildCost.payable(cost, BuildCost.inventory(v)) == null) {
            BuildCost.Option option = BuildCost.payable(cost, BuildCost.union(BuildCost.inventory(v), BuildCost.storage(level, colony)));
            if (option != null) {
                fetch(level, colony, v, cost, option, now);
                return;
            }
            if (cost.kind() == BuildCost.Kind.STRUCTURAL) {
                WorkTracker.status(v, STATUS_WAIT_MATERIALS);
                starved = true;
                nextActionTime = now + 40;
                return;
            }
            // functional blocks wait as repair entries (built when material arrives); decoration is skipped
            if (cost.kind() == BuildCost.Kind.DEFERRABLE) Damage.defer(level, colony, pos, current.state());
            job.nextBlock++;
            job.lastProgressTick = now;
            if (job.nextBlock >= job.totalBlocks) finish(level, colony);
            return;
        }
        boolean occupied = current.clear() ? standsOn(v, pos)
                : !current.state().getCollisionShape(level, pos).isEmpty() && v.getBoundingBox().intersects(new AABB(pos));
        if (!occupied && withinReach(v, pos)) {
            v.getNavigation().stop();
            v.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            boolean fired;
            if (current.clear()) {
                fired = timer.tick(level, v, pos, true);
            } else {
                ColonyConfig cfg = ColonyConfig.get();
                fired = timer.tickAction(level, v, pos, Math.max(cfg.village.build_delay_ticks, cfg.work.placement_ticks));
            }
            if (fired) {
                timer.reset();
                if (withinReach(v, pos) && execute(level, colony, v, current, now)) {
                    job.nextBlock++;
                    job.lastProgressTick = now;
                    job.supplyStallTicks = 0;
                    job.blockCount = 0;
                    starved = false;
                    WorkTracker.credit(v, current.clear() ? "подготовка участка" : current.foundation() ? "фундамент" : "строительство", 1);
                }
                nextActionTime = now + 1;
                if (job.nextBlock >= job.totalBlocks) finish(level, colony);
            }
            return;
        }
        if (occupied) WorkTracker.status(v, STATUS_CLEARING);
        if (stand != null && v.getNavigation().isDone() && v.blockPosition().distSqr(stand) <= 9) {
            Nav.blacklist(v, stand, now, 200);
            stand = null;
        }
        if (now >= nextStandTime) {
            nextStandTime = now + 20;
            approachStand(level, v, pos, now);
        }
    }

    /** Completion with a final check: damage done while building rewinds the plan (05 §2.5). */
    private void finish(ServerLevel level, Colony colony) {
        int rewind = BuildPlan.verify(level, colony, job, plan, !job.verified);
        job.verified = true;
        if (rewind >= 0) {
            job.nextBlock = rewind;
            return;
        }
        HousePlanner.complete(level, job);
    }

    /** Material cost of a step (null for clear steps). */
    static BuildCost.Cost cost(ServerLevel level, BuildPlan.Step s) {
        if (s.clear()) return null;
        return s.foundation() ? BuildCost.FOUNDATION : BuildCost.at(level, s.pos(), s.state());
    }

    // ---------------------------------------------------------------- steps

    static boolean standsOn(LivingEntity e, BlockPos pos) {
        return e.blockPosition().below().equals(pos) || e.getBlockPosBelowThatAffectsMyMovement().equals(pos);
    }

    /** Build reach: horizontal distance^2 to the block center <= 36 and |dy| <= 14. */
    static boolean withinReach(LivingEntity e, BlockPos pos) {
        double dx = e.getX() - (pos.getX() + 0.5), dz = e.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz <= 36 && Math.abs(e.getY() - (pos.getY() + 0.5)) <= 14;
    }

    /** Clear-step protection: wall block / protected position / block entity / builder_protected tag. */
    static boolean builderProtected(ServerLevel level, BlockPos pos) {
        if (Fortifications.isWallBlock(level, pos) || Protection.isProtectedBlock(level, pos) || level.getBlockEntity(pos) != null) return true;
        return ColonyConfig.get().roles.builder_respects_player_builds && level.getBlockState(pos).is(ModTags.BUILDER_PROTECTED);
    }

    /** Placement-step protection (wall, protected position, or a player build when the builder respects them). */
    static boolean placementProtected(ServerLevel level, BlockPos pos, BlockState world) {
        if (Fortifications.isWallBlock(level, pos) || Protection.isProtectedBlock(level, pos)) return true;
        return ColonyConfig.get().roles.builder_respects_player_builds && (world.is(ModTags.BUILDER_PROTECTED) || level.getBlockEntity(pos) != null);
    }

    /** Area protection (land plot, beacon nearby) stops the whole plot; any other protected position only skips its step. */
    static boolean areaProtected(ServerLevel level, BlockPos pos) {
        return ColonyManager.data(level).inLand(pos) || Protection.beaconNear(level, pos);
    }

    private boolean execute(ServerLevel level, Colony colony, Villager v, BuildPlan.Step s, long now) {
        if (BuildPlan.satisfied(level, s)) return true;
        BlockPos pos = s.pos();
        BlockState world = level.getBlockState(pos);
        if (s.clear()) {
            if (builderProtected(level, pos)) {
                if (!areaProtected(level, pos)) return true;
                HousePlanner.blockOrAbandon(level, colony, job, now + 2400);
                WorkTracker.status(v, STATUS_CLEAR_PROTECTED);
                ColonyManager.markDirty(level);
                return false;
            }
            if (!world.isAir() && Terrain.clearable(world)) {
                if (standsOn(v, pos)) {
                    stand = null;
                    nextStandTime = now;
                    return false;
                }
                List<ItemStack> drops = Block.getDrops(world, level, pos, level.getBlockEntity(pos), v, v.getMainHandItem());
                if (!SafeEdit.breakBlock(level, v, pos)) return false;
                depositDrops(level, colony, v, drops);
                return true;
            }
            return true;
        }
        if (placementProtected(level, pos, world)) {
            if (!areaProtected(level, pos)) {
                // a single protected block stays as it is; the house is built around it
                WorkTracker.status(v, STATUS_OBSTACLE);
                return true;
            }
            WorkTracker.status(v, STATUS_SITE_PROTECTED + pos.toShortString());
            HousePlanner.blockOrAbandon(level, colony, job, now + 2400);
            ColonyManager.markDirty(level);
            return false;
        }
        BuildCost.Cost cost = cost(level, s);
        BuildCost.Source carried = BuildCost.inventory(v);
        if (!cost.free() && BuildCost.payable(cost, carried) == null) return false;
        if (blockedByEntity(level, pos, s.state())) {
            WorkTracker.status(v, STATUS_OCCUPIED + pos.toShortString());
            for (LivingEntity e : Paths.occupants(level, pos)) {
                if ((e instanceof Villager || e instanceof IronGolem) && e != v && PathBudget.tryAcquire(level)) {
                    Paths.send(level, (Mob) e, ringEscape(level, job), 0.65, Nav.R2_DEFAULT);
                }
            }
            return false;
        }
        if (!SafeEdit.place(level, pos, s.state(), 3)) {
            pushOut(level, job, pos, now);
            return false;
        }
        if (!BuildCost.pay(cost, carried)) {
            SafeEditScope.run(() -> level.setBlock(pos, world, 3));
            return false;
        }
        v.swing(InteractionHand.MAIN_HAND);
        var sound = s.state().getSoundType(level, pos, v);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
        return true;
    }

    /** Any alive non-spectator living entity intersecting the target's collision boxes. */
    static boolean blockedByEntity(ServerLevel level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(1), e -> e.isAlive() && !e.isSpectator())) {
            for (AABB box : shape.toAabbs()) {
                if (e.getBoundingBox().intersects(box.move(pos))) return true;
            }
        }
        return false;
    }

    /** First standable surface point on the rings at distance 2..5 around the plot (fallback: origin). */
    static BlockPos ringEscape(ServerLevel level, ConstructionJob job) {
        for (int k = 2; k <= 5; k++) {
            List<BlockPos> points = new ArrayList<>();
            Paths.ring(level, job.box(), k, p -> {
                if (Nav.standable(level, p)) points.add(p);
            });
            if (!points.isEmpty()) {
                return points.get(0);
            }
        }
        return job.origin;
    }

    /** Push living villagers/golems out of a block to the nearest free door point of the plot (every 20 ticks). */
    static void pushOut(ServerLevel level, ConstructionJob job, BlockPos pos, long now) {
        if (now % 20 != 0) return;
        for (LivingEntity e : Paths.occupants(level, pos)) {
            if (!(e instanceof Villager || e instanceof IronGolem)) continue;
            if (!PathBudget.tryAcquire(level)) return;
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int[] o : new int[][]{{-3, job.sizeZ / 2}, {job.sizeX + 2, job.sizeZ / 2}, {job.sizeX / 2, -3}, {job.sizeX / 2, job.sizeZ + 2}}) {
                int x = job.origin.getX() + o[0], z = job.origin.getZ() + o[1];
                if (!Terrain.loaded(level, x, z)) continue;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!Nav.standable(level, p) || ColonyManager.data(level).inLand(p) || Protection.inPlayerOwnedJob(level, p)) continue;
                double d = e.distanceToSqr(Vec3.atBottomCenterOf(p));
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best == null) continue;
            if (e instanceof Villager villager) {
                VillagerUtil.stopRunning(level, villager, b -> VillagerUtil.isModTask(b) && !(b instanceof HouseConstructionTask));
                Nav.approach(level, villager, best, 0.9, Nav.R2_EXACT);
            } else {
                ((Mob) e).getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 0.9);
            }
        }
    }

    // ---------------------------------------------------------------- materials

    /** Deposits drops of a cleared block into colony storage (leftovers drop at the villager). */
    static void depositDrops(ServerLevel level, Colony colony, Villager v, List<ItemStack> drops) {
        for (ItemStack d : drops) {
            ColonyStorage.deposit(level, colony, d);
            if (!d.isEmpty()) v.spawnAtLocation(d);
        }
    }

    private void fetch(ServerLevel level, Colony colony, Villager v, BuildCost.Cost cost, BuildCost.Option option, long now) {
        BuildCost.Source carried = BuildCost.inventory(v);
        List<BuildCost.Part> missing = new ArrayList<>();
        for (BuildCost.Part part : option.parts()) {
            if (carried.count(part) < part.count()) missing.add(part);
        }
        Predicate<ItemStack> pp = s -> missing.stream().anyMatch(part -> part.match().test(s));
        boolean remote = false;
        BlockPos container = nearestContaining(level, colony, v, pp, true);
        if (container == null) {
            BlockPos any = nearestContaining(level, colony, v, pp, false);
            if (any == null) {
                WorkTracker.status(v, STATUS_WAIT_MATERIALS);
                starved = true;
                nextActionTime = now + 40;
                return;
            }
            job.addSupplyStall(now);
            if (job.supplyStallTicks < 600) {
                WorkTracker.status(v, STATUS_STORAGE_UNREACHABLE);
                nextActionTime = now + 20;
                return;
            }
            container = any;
            remote = true;
        }
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), container);
        double d = v.blockPosition().distSqr(container);
        if (fetchContainer == null || fetchContainer.distSqr(container) > 2) {
            fetchContainer = container;
            fetchBest = d;
            fetchWatchdog = now;
        } else if (d < fetchBest - 16) {
            fetchBest = d;
            fetchWatchdog = now;
        }
        if (d > 9 && now - fetchWatchdog >= 600) remote = true;
        if (!remote && d > 9) {
            if (now % 20 == 0 && !Nav.approach(level, v, container, 0.6, Nav.R2_DEFAULT)) {
                job.addSupplyStall(now);
            }
            return;
        }
        v.getNavigation().stop();
        if (withdrawFor(level, colony, v, container, cost, missing)) {
            starved = false;
            job.supplyStallTicks = 0;
            fetchWatchdog = now;
            fetchBest = d;
            job.lastProgressTick = now;
            if (remote) WorkTracker.credit(v, "снабжение стройки", 1);
        } else {
            WorkTracker.status(v, STATUS_RESERVE);
            starved = true;
        }
        nextActionTime = now + 10;
    }

    /**
     * Deposits stacks that are no ingredient of the cost, then withdraws the missing parts from the
     * container (05 §2.6): bulk parts (logs, stone, dirt, sand) up to a stack while keeping the
     * storage reserve, other parts exactly as needed.
     */
    static boolean withdrawFor(ServerLevel level, Colony colony, Villager v, BlockPos pos, BuildCost.Cost cost, List<BuildCost.Part> missing) {
        net.minecraft.world.SimpleContainer inv = v.getInventory();
        boolean keptFood = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            // building material and tools stay with the builder (no storage round trip per block)
            if (s.isEmpty() || BuildCost.useful(cost, s) || BuildCost.buildingMaterial(s) || s.isDamageableItem()) continue;
            if (!keptFood && ColonyStorage.isFood(s)) {
                keptFood = true;
                continue;
            }
            WorkTracker.deliver(level, colony, v, s);
            inv.setChanged();
        }
        Container c = ColonyStorage.container(level, pos);
        if (c == null) return false;
        boolean any = false;
        BuildCost.Source carried = BuildCost.inventory(v);
        for (BuildCost.Part part : missing) {
            int want = part.count() - carried.count(part);
            if (part.bulk()) {
                int stored = ColonyStorage.count(level, colony, part.match()) - BuildCost.BULK_RESERVE;
                want = Math.min(Math.max(want, 64), stored);
            }
            for (int i = 0; i < c.getContainerSize() && want > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !part.match().test(s)) continue;
                ItemStack taken = s.split(Math.min(want, s.getCount()));
                want -= taken.getCount();
                c.setChanged();
                ColonyStorage.decrementCounters(colony, taken);
                ItemStack rest = inv.addItem(taken);
                if (!rest.isEmpty()) {
                    ColonyStorage.deposit(level, colony, rest);
                    if (!rest.isEmpty()) v.spawnAtLocation(rest);
                }
                any = true;
            }
        }
        if (any) v.swing(InteractionHand.MAIN_HAND);
        return any;
    }

    /** Nearest usable storage containing a matching item (optionally skipping blacklisted ones). */
    static BlockPos nearestContaining(ServerLevel level, Colony colony, Villager v, Predicate<ItemStack> pred, boolean skipBlacklisted) {
        long now = level.getGameTime();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
            if (skipBlacklisted && Nav.isBlacklisted(v, slot.pos(), now)) continue;
            Container c = slot.container();
            boolean found = false;
            for (int i = 0; i < c.getContainerSize() && !found; i++) {
                ItemStack s = c.getItem(i);
                found = !s.isEmpty() && pred.test(s);
            }
            if (!found) continue;
            double d = slot.pos().distSqr(v.blockPosition());
            if (d < bestD) {
                bestD = d;
                best = slot.pos();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- stand position

    private boolean validStand(ServerLevel level, Villager v, BlockPos c, BlockPos target, long now) {
        if (!Nav.standable(level, c)) return false;
        if (horizontal(c, target) > 9 || Math.abs(c.getY() - target.getY()) > 13) return false;
        if (c.equals(target) || c.equals(target.above())) return false;
        double w = v.getBbWidth() / 2;
        AABB body = new AABB(c.getX() + 0.5 - w, c.getY(), c.getZ() + 0.5 - w, c.getX() + 0.5 + w, c.getY() + v.getBbHeight(), c.getZ() + 0.5 + w);
        if (body.intersects(new AABB(target))) return false;
        return !Nav.isBlacklisted(v, c, now);
    }

    private void approachStand(ServerLevel level, Villager v, BlockPos target, long now) {
        if (stand != null && validStand(level, v, stand, target, now)) {
            Nav.approach(level, v, stand, 0.8, Nav.R2_DEFAULT);
            return;
        }
        stand = null;
        LinkedHashSet<BlockPos> raw = new LinkedHashSet<>();
        for (int k = 2; k <= 4; k++) {
            Paths.ring(level, job.box(), k, p -> {
                if (Math.abs(p.getY() - target.getY()) <= 13) raw.add(p);
            });
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (dx * dx + dz * dz > 9) continue;
                for (int y = target.getY() - 13; y <= target.getY() + 13; y++) {
                    raw.add(new BlockPos(target.getX() + dx, y, target.getZ() + dz));
                }
            }
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : raw) {
            if (validStand(level, v, c, target, now)) candidates.add(c);
        }
        if (candidates.isEmpty()) {
            WorkTracker.status(v, STATUS_NO_APPROACH);
            return;
        }
        candidates.sort(Comparator.comparingDouble(c -> 0.35 * horizontal(c, target) + c.distSqr(v.blockPosition())));
        if (!PathBudget.tryAcquire(level)) return;
        // probes see 16 goals at a time: page through the candidates until one is reachable
        Path path = Paths.find(v, Paths.window(candidates, standPage));
        if (path != null && path.canReach() && path.getTarget() != null) {
            stand = path.getTarget();
            standPage = 0;
        } else {
            WorkTracker.status(v, STATUS_SEARCH_APPROACH);
            if (++standPage < Paths.pages(candidates)) return;
            // nothing reachable in any window: walk toward the best candidate (blacklisted if it stays out of reach)
            standPage = 0;
            stand = candidates.get(0);
        }
        Nav.approach(level, v, stand, 0.8, Nav.R2_DEFAULT);
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ---------------------------------------------------------------- builder cooldowns

    static boolean onCooldown(ConstructionJob job, UUID builder, long now) {
        synchronized (COOLDOWNS) {
            Map<UUID, Long> map = COOLDOWNS.get(job);
            if (map == null) return false;
            map.values().removeIf(t -> t <= now);
            return map.containsKey(builder);
        }
    }

    static void setCooldown(ConstructionJob job, UUID builder, long until) {
        synchronized (COOLDOWNS) {
            COOLDOWNS.computeIfAbsent(job, j -> new HashMap<>()).put(builder, until);
        }
    }
}
