package net.betterthanvillage.gather;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.ToolSupply;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.construction.Damage;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Miner (03 §3): shaft route, ore veins, torches, bridging, return/outfitting and deep expeditions. */
public final class MinerTask extends ColonyTask {
    /** Walking pace along the mine route (1.05 looked like a sprint next to other villagers). */
    private static final double ROUTE_SPEED = 0.7;
    /** Ticks a miner may spend walking to the route end; after that the expedition runs where it stands (underground only). */
    private static final long DEEP_WALK_LIMIT = 2400;
    /** Walk-time estimate per route cell, for the "fits in the work day" check. */
    private static final long DEEP_TICKS_PER_CELL = 8;
    /** The colony work activity ends at this time of day. */
    private static final long WORK_END = 9000;
    /** How far below the entrance a stranded deep walk may still start its expedition. */
    private static final int DEEP_MIN_DEPTH = 16;
    /** Reachability probes allowed per mining tick (shared PathBudget), and how long a probe result is reused. */
    private static final int PROBES_PER_TICK = 2;
    private static final long PROBE_TTL = 200;
    public static final String STATUS_DEEP_ABORT = "глубинная экспедиция отложена: забой недоступен";
    public static final String EMERGENCY_TAG = "btv_emergency_return";
    public static final String STATUS_ROUTE_ACCESS = "восстановление доступа к шахтному маршруту";
    public static final String STATUS_STORAGE_ACCESS = "восстановление доступа к складу шахтёра";
    public static final String STATUS_DEEP = "глубинная экспедиция: работа в забое";
    public static final String STATUS_DEEP_YIELD = "глубинная добыча";
    public static final String STATUS_UNREACHABLE = "недоступный забой: возврат с грузом";
    private static final String NEXT_DEEP_KEY = "gather.miner.next_deep";

    private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** Ore-scan offsets: dx,dz in [-32,32], dy in [-12,12], sorted by squared distance (packed). */
    private static final long[] SCAN = buildScan();

    enum Phase { PREPARE, MINING, DEEP, RETURN }

    private Phase phase = Phase.PREPARE;
    private boolean done;
    private long cooldownUntil;
    private BlockPos blockTarget;
    private BlockPos standPos;
    private BlockPos oreTarget;
    private BlockPos adjacentTarget;
    private int minedThisTrip;
    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();
    private final Set<BlockPos> veinQueued = new HashSet<>();
    private final List<BlockPos> branch = new ArrayList<>();
    private int branchStart = -1;
    private int branchReturnIndex;
    private int routeReturnIndex;
    private int routeWalkIndex;
    private int deepestIndex;
    private boolean resume;
    private final Map<BlockPos, Long> oreBlacklist = new HashMap<>();
    private boolean deepEligible;
    private boolean teleportEligible;
    /** The previous run went mining (or deep); only such runs count as stalled trips. */
    private boolean enteredMining;
    /** Arrived at the mine head this run; stall time is counted only from then. */
    private boolean atFace;
    /** The face cannot advance and no deep expedition is possible: park after delivering. */
    private boolean faceBlocked;
    private long deepCheckAt;
    private int probesLeft;
    private record Probe(boolean ok, long until) {
    }
    private final Map<BlockPos, Probe> reachCache = new HashMap<>();
    private long lastProgress;
    private int seenRouteSize;
    private long lastGrowth;
    private long nextOreScan;
    private BlockPos scanAnchor;
    private int scanCursor;
    private int entranceCursor;
    private final Deque<BlockPos> widenQueue = new ArrayDeque<>();
    private int lastWidenSize = -1;
    private long deepEnd;
    /** Deep expedition: walking down to the route end before the timer starts. */
    private boolean deepArrived;
    private long deepWalkStart;
    private int deepTicks;
    // movement tracking
    private BlockPos moveTarget;
    private double moveBest;
    private long moveLastImprove;
    private BlockPos sidestep;
    private long sidestepUntil;
    // return tracking
    private BlockPos retTarget;
    private double retBest;
    private BlockPos retAnchor;
    private long retLast;
    private long storageLast;
    private boolean teleported;
    private double storageBest;
    private final BlockWorkTimer timer = new BlockWorkTimer();

    public MinerTask() {
        super(24000);
    }

    private static long serverNow(ServerLevel level) {
        return level.getServer().getTickCount();
    }

    // ---------------------------------------------------------------- activation

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && v.getTradingPlayer() == null && serverNow(level) >= cooldownUntil
                && Math.floorMod(level.getDayTime(), 24000L) < 7800 && ColonyManager.terrainChangesAllowed(level)
                && colony.roleOf(v.getUUID()) == ColonyRole.MINER && colony.hasStorage();
    }

    @Override
    protected void start(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        long now = serverNow(level);
        phase = Phase.PREPARE;
        done = false;
        blockTarget = null;
        standPos = null;
        oreTarget = null;
        adjacentTarget = null;
        minedThisTrip = 0;
        veinQueue.clear();
        veinQueued.clear();
        branch.clear();
        branchStart = -1;
        branchReturnIndex = 0;
        widenQueue.clear();
        resume = false;
        routeWalkIndex = 0;
        deepestIndex = 0;
        if (colony == null) {
            done = true;
            return;
        }
        List<BlockPos> route = colony.mineRoute;
        routeReturnIndex = 0;
        if (!route.isEmpty()) {
            int i = nearestIndex(route, v.blockPosition());
            if (route.get(i).distSqr(v.blockPosition()) <= 9 && v.getY() < route.get(0).getY() - 2) {
                routeReturnIndex = i;
            }
        }
        oreBlacklist.values().removeIf(exp -> exp <= now);
        reachCache.values().removeIf(p -> p.until() <= now);
        int s;
        if (route.size() > colony.lastMineRouteSize) {
            colony.lastMineRouteSize = route.size();
            colony.mineStallTrips = 0;
            ColonyManager.markDirty(level);
        } else if (enteredMining) {
            // Restarts that never reached the mine (cooldowns, missing tools) are not stalled trips.
            colony.mineStallTrips++;
            ColonyManager.markDirty(level);
        }
        enteredMining = false;
        atFace = false;
        faceBlocked = false;
        deepCheckAt = now;
        s = colony.mineStallTrips;
        deepEligible = (route.size() >= 32 && s >= 2) || s >= 6;
        teleportEligible = route.size() < 32 && s >= 6 && s < 12;
        seenRouteSize = route.size();
        lastGrowth = now;
        lastProgress = now;
        resetMoveTracking(now);
        resetReturnTracking(now);
        v.getBrain().setMemory(ModRegistries.MINE_TARGET_POS.get(), route.isEmpty() ? colony.core() : route.get(route.size() - 1));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        return !done && !v.isSleeping() && v.getTradingPlayer() == null && colony != null && colony.roleOf(v.getUUID()) == ColonyRole.MINER;
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long time) {
        if (phase == Phase.DEEP && deepArrived) {
            // Interrupted at the face (night, role change): pay out the part already worked.
            Colony colony = colony(level, v);
            if (colony != null) payDeep(level, colony, v, serverNow(level), deepFraction(serverNow(level)));
            phase = Phase.RETURN;
        }
        timer.cancel(level);
        v.getNavigation().stop();
        v.getBrain().eraseMemory(ModRegistries.MINE_TARGET_POS.get());
    }

    private void finish(long cooldown) {
        cooldownUntil = cooldown;
        done = true;
    }

    // ---------------------------------------------------------------- dispatcher

    @Override
    protected void tick(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        if (colony == null) {
            done = true;
            return;
        }
        long now = serverNow(level);
        List<BlockPos> route = colony.mineRoute;
        if (route.size() > seenRouteSize) {
            seenRouteSize = route.size();
            lastGrowth = now;
        } else if (atFace && ((route.size() >= 32 && now - lastGrowth >= 400) || (!route.isEmpty() && now - lastGrowth >= 2400))) {
            deepEligible = true;
        }
        if (v.getTags().contains(EMERGENCY_TAG)) {
            v.removeTag(EMERGENCY_TAG);
            if (phase == Phase.DEEP && deepArrived) payDeep(level, colony, v, now, deepFraction(now));
            phase = Phase.RETURN;
            blockTarget = null;
            oreTarget = null;
            adjacentTarget = null;
            branch.clear();
            branchStart = -1;
            branchReturnIndex = 0;
            routeReturnIndex = 0;
            resetMoveTracking(now);
            resetReturnTracking(now);
        }
        if (phase == Phase.DEEP) {
            deepTick(level, colony, v, now, time);
            return;
        }
        if (deepEligible && teleportEligible && phase == Phase.PREPARE && teleportToRouteEnd(level, colony, v)) {
            deepEligible = false;
            teleportEligible = false;
            phase = Phase.MINING;
            enteredMining = true;
            lastProgress = now;
            lastGrowth = now;
            return;
        }
        if (deepEligible && !route.isEmpty() && now >= nextDeep(colony)[0] && now >= deepCheckAt) {
            deepCheckAt = now + 100;
            if (canStartDeep(level, colony, v)) {
                startDeep(level, v, now);
                return;
            }
        }
        long tod = Math.floorMod(level.getDayTime(), 24000L);
        if (phase == Phase.MINING && tod >= 8000 && tod < 12000) {
            returnHome(colony, v, now);
        } else if (phase != Phase.MINING) {
            returnTick(level, colony, v, now);
        } else {
            miningTick(level, colony, v, now);
        }
    }

    private static long[] nextDeep(Colony colony) {
        return colony.runtime(NEXT_DEEP_KEY, () -> new long[]{0L});
    }

    // ---------------------------------------------------------------- mining (3.4)

    private void miningTick(ServerLevel level, Colony colony, Villager v, long now) {
        probesLeft = PROBES_PER_TICK;
        if (oreTarget != null && now - lastProgress > 200) {
            oreBlacklist.put(oreTarget, now + 6000);
            oreTarget = null;
            blockTarget = null;
            veinQueue.clear();
            veinQueued.clear();
            if (!branch.isEmpty()) {
                returnHome(colony, v, now);
                resume = true;
                return;
            }
        }
        if (!(now - lastProgress <= 1600 && minedThisTrip < 192 && lootCount(v) < 256 && valuables(v) < 24
                && v.getHealth() >= v.getMaxHealth() * 0.6f)) {
            returnHome(colony, v, now);
            return;
        }
        if (blockTarget != null) {
            handleBlockTarget(level, colony, v, now);
            return;
        }
        if (adjacentTarget != null || findAdjacentOre(level, colony, v)) {
            breakAdjacent(level, colony, v, now);
            return;
        }
        List<BlockPos> route = colony.mineRoute;
        if (oreTarget != null) {
            BlockState s = level.getBlockState(oreTarget);
            if (!GatherUtil.isOre(s) || toolInsufficient(v, s) || route.contains(oreTarget.above())) oreTarget = null;
        }
        while (!veinQueue.isEmpty()) {
            BlockPos o = veinQueue.pollFirst();
            if (GatherUtil.isOre(level.getBlockState(o)) && !outside(colony, o)) {
                if (oreCandidate(level, colony, v, o, now)) return;
                if (oreTarget != null) break;
            }
        }
        if (oreTarget == null && branchStart >= 0 && veinQueue.isEmpty()) {
            returnHome(colony, v, now);
            resume = true;
            return;
        }
        if (oreTarget == null && now >= nextOreScan) {
            nextOreScan = now + 10;
            if (oreScan(level, colony, v, now)) return;
        }
        if (route.isEmpty()) {
            searchEntrance(level, colony, v);
            return;
        }
        BlockPos head = branch.isEmpty() ? route.get(route.size() - 1) : branch.get(branch.size() - 1);
        v.getBrain().setMemory(ModRegistries.MINE_TARGET_POS.get(), head);
        if (v.position().distanceToSqr(Vec3.atBottomCenterOf(head)) > 2.5) {
            if (!branch.isEmpty()) {
                move(level, colony, v, head, now);
            } else {
                routeWalkIndex = Math.min(routeWalkIndex, route.size() - 1);
                if (at(v, route.get(routeWalkIndex)) && routeWalkIndex < route.size() - 1) routeWalkIndex++;
                if (routeWalkIndex > deepestIndex) {
                    deepestIndex = routeWalkIndex;
                    lastProgress = now;
                }
                move(level, colony, v, route.get(routeWalkIndex), now);
            }
            return;
        }
        if (!atFace) {
            // Stall time (deep eligibility) counts from arrival at the face, not from the walk down.
            atFace = true;
            lastGrowth = now;
        }
        if (!torchCheck(level, v, head)) {
            returnHome(colony, v, now);
            return;
        }
        if (oreTarget == null && branch.isEmpty() && (route.size() <= 8 || route.size() % 8 == 0) && widen(level, colony, head)) {
            return;
        }
        extend(level, colony, v, head, now);
    }

    private void handleBlockTarget(ServerLevel level, Colony colony, Villager v, long now) {
        BlockState state = level.getBlockState(blockTarget);
        if (GatherUtil.passable(level, blockTarget)) {
            blockTarget = null;
            lastProgress = now;
            return;
        }
        if (!mineable(level, colony, blockTarget)) {
            blockTarget = null;
            oreTarget = null;
            return;
        }
        if (toolInsufficient(v, state)) {
            blockTarget = null;
            returnHome(colony, v, now);
            return;
        }
        v.getBrain().setMemory(ModRegistries.MINE_TARGET_POS.get(), blockTarget);
        if (v.position().distanceToSqr(Vec3.atBottomCenterOf(standPos)) > 2.5) {
            if (Nav.isBlacklisted(v, standPos, level.getGameTime())) {
                oreBlacklist.put(blockTarget, now + 1200);
                blockTarget = null;
                oreTarget = null;
            } else {
                move(level, colony, v, standPos, now);
            }
            return;
        }
        if (!torchCheck(level, v, v.blockPosition())) {
            returnHome(colony, v, now);
            return;
        }
        if (blockTarget.equals(v.blockPosition().below())) {
            blockTarget = null;
            return;
        }
        // Eye-based reach: the top cell of an ascending step is 3.5 blocks above the feet.
        if (v.getEyePosition().distanceToSqr(Vec3.atCenterOf(blockTarget)) > 20.25) {
            move(level, colony, v, standPos, now);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(Vec3.atCenterOf(blockTarget));
        List<ItemStack> drops = Block.getDrops(state, level, blockTarget, null, v, v.getMainHandItem());
        if (!fits(v, drops)) {
            returnHome(colony, v, now);
            return;
        }
        if (!timer.tick(level, v, blockTarget, true)) return;
        boolean ore = GatherUtil.isOre(state);
        BlockPos mined = blockTarget;
        if (SafeEdit.breakBlock(level, v, mined)) {
            ItemStack bonus = Quarry.bonus(level, mined, state, v.getRandom(), net.betterthanvillage.economy.Skill.master(colony, v.getUUID()));
            if (!bonus.isEmpty()) drops = new java.util.ArrayList<>(drops);
            if (!bonus.isEmpty()) drops.add(bonus);
            giveAll(level, v, drops);
            v.swing(InteractionHand.MAIN_HAND);
            minedThisTrip++;
            lastProgress = now;
            if (ore) enqueueVein(level, mined);
            if (GatherUtil.wearTool(v, ItemTags.PICKAXES, 1)) {
                // Pickaxe broke: go home, the outfitting step supplies a new one.
                timer.reset();
                returnHome(colony, v, now);
                return;
            }
        }
        timer.reset();
        blockTarget = null;
    }

    private boolean findAdjacentOre(ServerLevel level, Colony colony, Villager v) {
        BlockPos p = v.blockPosition();
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockPos n = p.relative(d);
            BlockState s = level.getBlockState(n);
            if (GatherUtil.isOre(s) && mineable(level, colony, n) && !toolInsufficient(v, s)) {
                adjacentTarget = n;
                return true;
            }
        }
        return false;
    }

    private void breakAdjacent(ServerLevel level, Colony colony, Villager v, long now) {
        BlockPos n = adjacentTarget;
        BlockState s = level.getBlockState(n);
        if (!GatherUtil.isOre(s) || !mineable(level, colony, n) || toolInsufficient(v, s)) {
            adjacentTarget = null;
            timer.cancel(level);
            return;
        }
        List<ItemStack> drops = Block.getDrops(s, level, n, null, v, v.getMainHandItem());
        if (!fits(v, drops)) {
            adjacentTarget = null;
            returnHome(colony, v, now);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(Vec3.atCenterOf(n));
        if (!timer.tick(level, v, n, true)) return;
        if (SafeEdit.breakBlock(level, v, n)) {
            giveAll(level, v, drops);
            v.swing(InteractionHand.MAIN_HAND);
            minedThisTrip++;
            lastProgress = now;
            enqueueVein(level, n);
            if (GatherUtil.wearTool(v, ItemTags.PICKAXES, 1)) {
                timer.reset();
                returnHome(colony, v, now);
                return;
            }
        }
        timer.reset();
        adjacentTarget = null;
    }

    private void extend(ServerLevel level, Colony colony, Villager v, BlockPos head, long now) {
        List<BlockPos> route = colony.mineRoute;
        if (oreTarget != null && branch.isEmpty()) {
            branchStart = route.size() - 1;
            branch.add(head);
        }
        if (oreTarget == null && branch.isEmpty() && route.size() >= 512) {
            deepOrPark(level, colony, v, now);
            return;
        }
        BlockPos next = oreTarget == null ? nextStep(level, colony) : stepToward(head, oreTarget, route);
        if (next == null) {
            if (oreTarget != null) oreBlacklist.put(oreTarget, now + 6000);
            oreTarget = null;
            return;
        }
        if (!GatherUtil.solidFloor(level, next.below()) && tryBridge(level, colony, v, next.below(), now)) {
            return;
        }
        if (!(diggable(level, colony, v, next) && !outside(colony, next) && !route.contains(next) && !branch.contains(next))) {
            if (oreTarget != null) {
                oreBlacklist.put(oreTarget, now + 6000);
                oreTarget = null;
                return;
            }
            int dir = colony.mineDirection;
            BlockPos alt = null;
            for (int k = 1; k <= 3 && alt == null; k++) {
                colony.mineDirection = Math.floorMod(dir + k, 4);
                BlockPos c = nextStep(level, colony);
                if (c != null && diggable(level, colony, v, c) && !outside(colony, c) && !route.contains(c)) {
                    alt = c;
                    ColonyManager.markDirty(level);
                }
            }
            if (alt == null) {
                colony.mineDirection = dir;
                outer:
                for (int dy : new int[]{0, 1, -1}) {
                    for (Direction d : DIRS) {
                        BlockPos c = head.relative(d).above(dy);
                        if (!outside(colony, c) && !route.contains(c) && diggable(level, colony, v, c)) {
                            alt = c;
                            break outer;
                        }
                    }
                }
            }
            if (alt == null) {
                deepOrPark(level, colony, v, now);
                return;
            }
            next = alt;
        }
        for (int y = 2; y >= 0; y--) {
            BlockPos c = next.above(y);
            if (GatherUtil.passable(level, c)) continue;
            if (!mineable(level, colony, c)) {
                returnHome(colony, v, now);
                return;
            }
            blockTarget = c;
            standPos = head;
            return;
        }
        if (oreTarget != null) {
            branch.add(next);
        } else {
            route.add(next.immutable());
            routeWalkIndex = route.size() - 1;
            ColonyManager.markDirty(level);
        }
        lastProgress = now;
        move(level, colony, v, next, now);
    }

    private boolean tryBridge(ServerLevel level, Colony colony, Villager v, BlockPos floor, long now) {
        if (!GatherUtil.passable(level, floor) || outside(colony, floor)) return false;
        boolean support = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = floor.relative(d);
            if (level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite())) {
                support = true;
                break;
            }
        }
        if (!support) return false;
        int slot = findSlot(v.getInventory(), Items.COBBLESTONE);
        if (slot < 0) return false;
        if (!SafeEdit.place(level, floor, Blocks.COBBLESTONE.defaultBlockState(), 3)) return false;
        v.getInventory().getItem(slot).shrink(1);
        v.swing(InteractionHand.MAIN_HAND);
        lastProgress = now;
        return true;
    }

    /** Widening near the entrance and every 8 steps: one bay cell per call; true if a block target was produced. */
    private boolean widen(ServerLevel level, Colony colony, BlockPos head) {
        List<BlockPos> route = colony.mineRoute;
        if (route.size() != lastWidenSize) {
            lastWidenSize = route.size();
            widenQueue.clear();
            if (route.size() >= 2) {
                BlockPos prev = route.get(route.size() - 2);
                int sx = Integer.signum(head.getX() - prev.getX());
                int sz = Integer.signum(head.getZ() - prev.getZ());
                if (sx != 0 || sz != 0) {
                    for (int dy = 0; dy <= 1; dy++) {
                        widenQueue.add(head.offset(-sz, dy, sx));
                        widenQueue.add(head.offset(sz, dy, -sx));
                    }
                }
            }
        }
        while (!widenQueue.isEmpty()) {
            BlockPos c = widenQueue.pollFirst();
            if (!GatherUtil.passable(level, c) && mineable(level, colony, c)) {
                blockTarget = c;
                standPos = head;
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- shaft geometry (3.6)

    /** Next shaft cell from the persisted route and direction (serpentine levels at Y=16 and the bottom, else a spiral staircase). */
    public static BlockPos nextStep(ServerLevel level, Colony colony) {
        List<BlockPos> route = colony.mineRoute;
        if (route.isEmpty()) return null;
        int size = route.size();
        BlockPos last = route.get(size - 1);
        int flat = 0;
        for (int i = size - 2; i >= 0 && route.get(i).getY() == last.getY(); i--) flat++;
        int minY = Math.max(level.getMinBuildHeight() + 10, -52);
        int dir = Math.floorMod(colony.mineDirection, 4);
        if (last.getY() <= minY || (last.getY() == 16 && flat < 131)) {
            int row = flat / 67;
            int col = flat % 67;
            Direction d = DIRS[dir];
            Direction step = col < 64 ? (row % 2 == 0 ? d : d.getOpposite()) : d.getClockWise();
            return last.relative(step);
        }
        Direction d = DIRS[Math.floorMod(dir + (size - 1) / 24, 4)];
        BlockPos next = last.relative(d);
        return last.getY() > minY ? next.below() : next;
    }

    private void searchEntrance(ServerLevel level, Colony colony, Villager v) {
        BlockPos core = colony.core();
        boolean probed = false;
        for (int n = 0; n < 8; n++) {
            int c = entranceCursor;
            entranceCursor = (entranceCursor + 1) % 2401;
            int dx = (c % 49) - 24;
            int dz = (c / 49) - 24;
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 14) continue;
            int x = core.getX() + dx, z = core.getZ() + dz;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!GatherUtil.passable(level, p) || !GatherUtil.passable(level, p.above()) || !mineable(level, colony, p.below())) continue;
            if (!entranceBoxOk(level, colony, p)) continue;
            // One path probe per call (shared PathBudget); the next candidate waits for the next tick.
            if (probed) {
                entranceCursor = c;
                return;
            }
            probed = true;
            if (!reachable(level, v, p)) continue;
            colony.mineRoute.add(p);
            colony.mineDirection = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 1 : 3) : (dz > 0 ? 2 : 0);
            ColonyManager.markDirty(level);
            return;
        }
    }

    private boolean entranceBoxOk(ServerLevel level, Colony colony, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -1, -3), p.offset(3, 2, 3))) {
            BlockState s = level.getBlockState(q);
            if (s.is(Blocks.DIRT_PATH) || s.hasBlockEntity()) return false;
            if (!(s.isAir() || s.canBeReplaced() || GatherUtil.naturalTerrain(s) || GatherUtil.isOre(s) || s.is(BlockTags.LEAVES))) return false;
        }
        return true;
    }

    private static boolean reachable(ServerLevel level, Villager v, BlockPos p) {
        if (!PathBudget.tryAcquire(level)) return false;
        Path path = v.getNavigation().createPath(p, 0);
        return path != null && path.canReach();
    }

    /** Outside the mining radius: horizontal distance to the core greater than radius - 4. */
    private static boolean outside(Colony colony, BlockPos pos) {
        long r = colony.radius() - 4;
        return GatherUtil.hDist2(pos, colony.core()) > r * r;
    }

    private boolean inRouteOrBranch(Colony colony, BlockPos pos) {
        return colony.mineRoute.contains(pos) || branch.contains(pos);
    }

    /** Every block the miner breaks must pass this (no fluids next to it, no falling block above, natural terrain only). */
    private boolean mineable(ServerLevel level, Colony colony, BlockPos pos) {
        if (inRouteOrBranch(colony, pos.above())) return false;
        if (!Protection.terrainModifiable(level, pos)) return false;
        if (Damage.isProtectedHousePiece(level, pos)) return false;
        BlockState s = level.getBlockState(pos);
        if (!(GatherUtil.naturalTerrain(s) || GatherUtil.isOre(s)) || s.hasBlockEntity()) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.isLoaded(n) || !level.getFluidState(n).isEmpty()) return false;
        }
        BlockState above = level.getBlockState(pos.above());
        if (above.is(Blocks.GRAVEL) || above.is(BlockTags.SAND)) return false;
        return !GatherUtil.underStructure(level, pos, 3, 5);
    }

    /** Next tunnel cell can be dug; an ore the held pickaxe cannot harvest counts as an obstacle (the route goes around). */
    private boolean diggable(ServerLevel level, Colony colony, Villager v, BlockPos next) {
        if (!GatherUtil.solidFloor(level, next.below())) return false;
        for (int y = 0; y <= 2; y++) {
            BlockPos c = next.above(y);
            if (GatherUtil.passable(level, c)) continue;
            if (!mineable(level, colony, c)) return false;
            BlockState s = level.getBlockState(c);
            if (GatherUtil.isOre(s) && toolInsufficient(v, s)) return false;
        }
        return true;
    }

    /** Reachability of a stand cell for the ore scan: cached, and at most PROBES_PER_TICK path probes per tick. */
    private boolean probeReachable(ServerLevel level, Villager v, BlockPos n, long now) {
        Probe p = reachCache.get(n);
        if (p != null && p.until() > now) return p.ok();
        if (probesLeft <= 0 || !PathBudget.tryAcquire(level)) return false;
        probesLeft--;
        Path path = v.getNavigation().createPath(n, 0);
        boolean ok = path != null && path.canReach();
        if (reachCache.size() > 512) reachCache.clear();
        reachCache.put(n.immutable(), new Probe(ok, now + PROBE_TTL));
        return ok;
    }

    /** Pickaxe gating by block id path (3.7). */
    private static boolean toolInsufficient(Villager v, BlockState s) {
        String path = GatherUtil.path(s);
        ItemStack held = v.getMainHandItem();
        boolean ironPlus = held.is(Items.IRON_PICKAXE) || held.is(Items.DIAMOND_PICKAXE) || held.is(Items.NETHERITE_PICKAXE);
        if (path.contains("diamond") || path.contains("gold") || path.contains("redstone") || path.contains("emerald")) return !ironPlus;
        if (path.contains("iron") || path.contains("copper")) return !(ironPlus || held.is(Items.STONE_PICKAXE));
        return false;
    }

    // ---------------------------------------------------------------- ores (3.5)

    private boolean oreScan(ServerLevel level, Colony colony, Villager v, long now) {
        BlockPos here = v.blockPosition();
        for (int i = 0; i < 512; i++) {
            if (oreCandidate(level, colony, v, offset(here, SCAN[i]), now)) return true;
        }
        if (scanAnchor == null || scanCursor >= SCAN.length || here.distSqr(scanAnchor) > 1024) {
            scanAnchor = here;
            scanCursor = 0;
        }
        for (int n = 0; n < 1024 && scanCursor < SCAN.length; n++) {
            if (oreCandidate(level, colony, v, offset(scanAnchor, SCAN[scanCursor++]), now)) return true;
        }
        return false;
    }

    /** Candidate check; returns true when o became a directly reachable block target. */
    private boolean oreCandidate(ServerLevel level, Colony colony, Villager v, BlockPos o, long now) {
        // Cheap checks first: most scanned cells are not ores, and the route lookup is linear.
        if (!level.isLoaded(o)) return false;
        BlockState s = level.getBlockState(o);
        if (!GatherUtil.isOre(s)) return false;
        Long exp = oreBlacklist.get(o);
        if (exp != null && exp > now) return false;
        if (outside(colony, o) || inRouteOrBranch(colony, o.above())) return false;
        if (!mineable(level, colony, o) || toolInsufficient(v, s)) return false;
        List<BlockPos> route = colony.mineRoute;
        if (!route.isEmpty() && route.size() < 20) return false;
        if (route.size() >= 1 && route.size() <= 31) {
            BlockPos ns = nextStep(level, colony);
            if (ns != null && (o.equals(ns) || o.equals(ns.above()) || o.equals(ns.above(2)))) return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = o.relative(d);
            if (GatherUtil.passable(level, n) && GatherUtil.passable(level, n.above()) && GatherUtil.solidFloor(level, n.below())
                    && (n.distSqr(v.blockPosition()) <= 4 || probeReachable(level, v, n, now))) {
                blockTarget = o.immutable();
                standPos = n.immutable();
                return true;
            }
        }
        BlockPos vp = v.blockPosition();
        boolean atHead = route.isEmpty() || vp.distSqr(route.get(route.size() - 1)) <= 9
                || (!branch.isEmpty() && vp.distSqr(branch.get(branch.size() - 1)) <= 9);
        if (atHead && (vp.getY() <= 48 || priorityOre(level, colony, s, o, vp)) && o.getY() <= vp.getY() + 4
                && (oreTarget == null || o.distSqr(vp) < oreTarget.distSqr(vp))) {
            oreTarget = o.immutable();
        }
        return false;
    }

    private boolean priorityOre(ServerLevel level, Colony colony, BlockState s, BlockPos o, BlockPos vp) {
        if (s.is(Blocks.IRON_ORE) || s.is(Blocks.COPPER_ORE)) return true;
        return s.is(Blocks.COAL_ORE) && minedThisTrip < 16 && o.distSqr(vp) <= 64
                && ColonyStorage.count(level, colony, st -> st.is(Items.COAL)) < 8;
    }

    private BlockPos stepToward(BlockPos h, BlockPos t, List<BlockPos> route) {
        if (t.getX() == h.getX() && t.getZ() == h.getZ()) {
            if (t.getY() == h.getY()) return null;
            int dy = t.getY() > h.getY() ? 1 : -1;
            for (Direction d : DIRS) {
                BlockPos c = h.relative(d).above(dy);
                if (!route.contains(c) && !branch.contains(c)) return c;
            }
            return null;
        }
        int dx = t.getX() - h.getX();
        int dz = t.getZ() - h.getZ();
        int dy = t.getY() < h.getY() - 1 ? -1 : t.getY() > h.getY() + 1 ? 1 : 0;
        if (Math.abs(dx) >= Math.abs(dz)) return h.offset(Integer.signum(dx), dy, 0);
        return h.offset(0, dy, Integer.signum(dz));
    }

    private void enqueueVein(ServerLevel level, BlockPos mined) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos n = mined.offset(dx, dy, dz);
                    if (GatherUtil.isOre(level.getBlockState(n)) && veinQueued.add(n)) veinQueue.add(n);
                }
            }
        }
    }

    // ---------------------------------------------------------------- movement (3.10)

    private void resetMoveTracking(long now) {
        moveTarget = null;
        moveLastImprove = now;
        sidestep = null;
        sidestepUntil = 0;
    }

    private void move(ServerLevel level, Colony colony, Villager v, BlockPos target, long now) {
        if (sidestep != null && now < sidestepUntil) {
            Nav.approach(level, v, sidestep, 0.85, Nav.R2_EXACT);
            return;
        }
        double d = v.position().distanceToSqr(Vec3.atBottomCenterOf(target));
        if (!target.equals(moveTarget)) {
            moveTarget = target;
            moveBest = d;
            moveLastImprove = now;
        } else if (d < moveBest - 0.5) {
            moveBest = d;
            moveLastImprove = now;
        }
        if (now - moveLastImprove > 60 && d <= 16) {
            boolean blocked = !level.getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(1.2),
                    o -> o != v && o.getId() < v.getId()).isEmpty();
            if (blocked) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos c = v.blockPosition().relative(dir);
                    if (!c.equals(target) && Nav.standable(level, c) && GatherUtil.freeOfLiving(level, c, v)) {
                        sidestep = c;
                        sidestepUntil = now + 40;
                        Nav.approach(level, v, c, 0.85, Nav.R2_EXACT);
                        return;
                    }
                }
            }
        }
        Nav.approach(level, v, target, ROUTE_SPEED);
        if (phase == Phase.MINING && now - moveLastImprove > 400) {
            moveLastImprove = now;
            if (routeWalkIndex == 0 && blockTarget == null && lootCount(v) == 0 && teleportToRouteEnd(level, colony, v)) {
                // Shortcut taken.
            } else {
                if (blockTarget != null) oreBlacklist.put(blockTarget, now + 1200);
                if (oreTarget != null) oreBlacklist.put(oreTarget, now + 6000);
                blockTarget = null;
                oreTarget = null;
                veinQueue.clear();
                veinQueued.clear();
                resume = false;
                WorkTracker.status(v, STATUS_UNREACHABLE);
                returnHome(colony, v, now);
            }
        }
    }

    private static boolean at(Villager v, BlockPos p) {
        double dx = v.getX() - (p.getX() + 0.5);
        double dz = v.getZ() - (p.getZ() + 0.5);
        return Math.abs(v.getY() - p.getY()) < 0.6 && dx * dx + dz * dz < 1.3;
    }

    private boolean teleportToRouteEnd(ServerLevel level, Colony colony, Villager v) {
        if (!ColonyConfig.get().work.miner_route_teleport) return false;
        List<BlockPos> route = colony.mineRoute;
        for (int i = route.size() - 1; i >= Math.max(0, route.size() - 8); i--) {
            BlockPos p = route.get(i);
            if (Nav.standable(level, p) && GatherUtil.freeOfLiving(level, p, v)) {
                GatherUtil.teleport(v, p);
                routeWalkIndex = i;
                WorkTracker.status(v, STATUS_ROUTE_ACCESS);
                return true;
            }
        }
        return false;
    }

    /** Torch within (−4..4, −2..3, −4..4) of the cell, else place one there if possible; false when out of torches. */
    private static boolean torchCheck(ServerLevel level, Villager v, BlockPos cell) {
        for (BlockPos p : BlockPos.betweenClosed(cell.offset(-4, -2, -4), cell.offset(4, 3, 4))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) return true;
        }
        BlockState torch = Blocks.TORCH.defaultBlockState();
        if (!level.getBlockState(cell).isAir() || !torch.canSurvive(level, cell)) return true;
        int slot = findSlot(v.getInventory(), Items.TORCH);
        if (slot < 0) return false;
        if (SafeEdit.place(level, cell, torch, 3)) {
            v.getInventory().getItem(slot).shrink(1);
            v.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    // ---------------------------------------------------------------- return, delivery, outfitting (3.8)

    private void resetReturnTracking(long now) {
        retTarget = null;
        retAnchor = null;
        retLast = now;
        storageLast = now;
        storageBest = Double.MAX_VALUE;
    }

    private void returnHome(Colony colony, Villager v, long now) {
        resume = false;
        branchReturnIndex = Math.max(0, branch.size() - 1);
        phase = Phase.RETURN;
        blockTarget = null;
        adjacentTarget = null;
        List<BlockPos> route = colony.mineRoute;
        if (branch.isEmpty()) {
            routeReturnIndex = route.isEmpty() ? 0 : nearestIndex(route, v.blockPosition());
            if (!route.isEmpty() && route.get(routeReturnIndex).distSqr(v.blockPosition()) > 9) routeReturnIndex = 0;
        } else {
            routeReturnIndex = Math.max(0, branchStart);
        }
        resetReturnTracking(now);
    }

    /** Walks back toward a cell with progress tracking; true once "at" it. Emergency-teleports after 360 ticks without progress. */
    private boolean walkBack(ServerLevel level, Colony colony, Villager v, BlockPos target, long now) {
        if (at(v, target)) return true;
        double d = v.position().distanceToSqr(Vec3.atBottomCenterOf(target));
        if (retAnchor == null) {
            retAnchor = v.blockPosition();
            retLast = now;
        }
        if (!target.equals(retTarget)) {
            retTarget = target;
            retBest = d;
        } else if (d < retBest - 1 || v.blockPosition().distSqr(retAnchor) >= 16) {
            retBest = Math.min(retBest, d);
            retAnchor = v.blockPosition();
            retLast = now;
        }
        if (now - retLast > 360) {
            resetReturnTracking(now);
            if (net.betterthanvillage.ai.nav.AntiStuck.emergencyTeleport(level, colony, v)) {
                teleported = true;
                return true;
            }
        }
        move(level, colony, v, target, now);
        return false;
    }

    private void returnTick(ServerLevel level, Colony colony, Villager v, long now) {
        List<BlockPos> route = colony.mineRoute;
        while (branchReturnIndex > 0 && !branch.isEmpty()) {
            int idx = Math.min(branchReturnIndex, branch.size() - 1);
            if (idx <= 0) {
                branchReturnIndex = 0;
                break;
            }
            teleported = false;
            if (!walkBack(level, colony, v, branch.get(idx - 1), now)) return;
            branchReturnIndex = teleported ? 0 : idx - 1;
        }
        if (!branch.isEmpty()) {
            routeReturnIndex = route.isEmpty() ? 0 : Math.min(Math.max(0, branchStart), route.size() - 1);
            branch.clear();
        }
        branchStart = -1;
        branchReturnIndex = 0;
        if (resume) {
            resume = false;
            phase = Phase.MINING;
            enteredMining = true;
            blockTarget = null;
            oreTarget = null;
            routeWalkIndex = routeReturnIndex;
            lastProgress = now;
            return;
        }
        while (routeReturnIndex > 0 && !route.isEmpty()) {
            int idx = Math.min(routeReturnIndex, route.size() - 1);
            if (idx <= 0) {
                routeReturnIndex = 0;
                break;
            }
            if (!walkBack(level, colony, v, route.get(idx - 1), now)) return;
            routeReturnIndex = idx - 1;
        }
        BlockPos storage = ColonyStorage.nearestFor(level, colony, v, StorageKind.MINING);
        if (storage == null) {
            finish(now + 100);
            return;
        }
        v.getBrain().setMemory(ModRegistries.MINE_TARGET_POS.get(), storage);
        double d = v.position().distanceToSqr(Vec3.atCenterOf(storage));
        if (d > 9) {
            if (d < storageBest - 1) {
                storageBest = d;
                storageLast = now;
            }
            if (now - storageLast > 200 || Nav.stuck(v, level.getGameTime())) {
                teleportNearStorage(level, v, storage);
                storageLast = now;
                storageBest = Double.MAX_VALUE;
                return;
            }
            move(level, colony, v, storage, now);
            return;
        }
        v.getNavigation().stop();
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isLoot(s)) WorkTracker.deliver(level, colony, v, s);
        }
        inv.setChanged();
        ToolSupply.smeltAll(level, colony);
        boolean deepWanted = deepEligible && !route.isEmpty();
        if (deepWanted && now >= nextDeep(colony)[0] && canStartDeep(level, colony, v)) {
            startDeep(level, v, now);
        } else if (deepWanted && now < nextDeep(colony)[0]) {
            // Wait for the deep-expedition cooldown instead of restarting every tick.
            finish(nextDeep(colony)[0]);
        } else if (faceBlocked) {
            finish(now + 1200);
        } else if (phase == Phase.RETURN) {
            finish(now + 40);
        } else {
            outfit(level, colony, v, now);
        }
    }

    private static void teleportNearStorage(ServerLevel level, Villager v, BlockPos chest) {
        for (int r = 1; r <= 2; r++) {
            for (int dy : new int[]{0, 1, -1}) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos c = chest.offset(dx, dy, dz);
                        if (Nav.standable(level, c) && GatherUtil.freeOfLiving(level, c, v)) {
                            GatherUtil.teleport(v, c);
                            WorkTracker.status(v, STATUS_STORAGE_ACCESS);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void outfit(ServerLevel level, Colony colony, Villager v, long now) {
        ToolSupply.ensureTool(level, colony, v, ToolSupply.Kind.PICKAXE);
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < 32 && count(inv, Items.TORCH) < 32 && inv.canAddItem(new ItemStack(Items.TORCH)); i++) {
            ItemStack torch = ColonyStorage.withdrawOne(level, colony, s -> s.is(Items.TORCH));
            if (torch.isEmpty()) {
                boolean coal = ColonyStorage.has(level, colony, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
                if (coal && ColonyStorage.has(level, colony, s -> s.is(ItemTags.LOGS))) {
                    ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1);
                    ColonyStorage.consume(level, colony, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1);
                } else if (!ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 2)) {
                    break;
                }
                torch = new ItemStack(Items.TORCH, 4);
            }
            ItemStack rest = inv.addItem(torch);
            ToolSupply.stash(level, colony, v, rest);
        }
        int cobble = count(inv, Items.COBBLESTONE);
        if (cobble < 16) {
            BlockPos src = ColonyStorage.nearestContaining(level, colony, v.blockPosition(), s -> s.is(Items.COBBLESTONE));
            if (src != null) {
                ItemStack got = ColonyStorage.takeFrom(level, colony, src, s -> s.is(Items.COBBLESTONE), 16 - cobble);
                ToolSupply.stash(level, colony, v, inv.addItem(got));
            }
        }
        boolean room = lootRoom(level, colony);
        if (!room) WorkTracker.status(v, WorkTracker.STATUS_STORAGE_FULL);
        if (v.getMainHandItem().is(ItemTags.PICKAXES) && count(inv, Items.TORCH) >= 1 && room) {
            phase = Phase.MINING;
            enteredMining = true;
            lastProgress = now;
            routeWalkIndex = 0;
            deepestIndex = 0;
            resetMoveTracking(now);
        } else {
            finish(now + 100);
        }
    }

    // ---------------------------------------------------------------- deep expedition (3.9)

    private static int deepTicks() {
        return Math.max(400, Math.min(12000, ColonyConfig.get().work.deep_mining_ticks));
    }

    /** Storage can take mining loot: an empty slot, or room for more than a stack of cobblestone. */
    private static boolean lootRoom(ServerLevel level, Colony colony) {
        return ColonyStorage.emptySlots(level, colony) > 0 || ColonyStorage.freeCapacity(level, colony, new ItemStack(Items.COBBLESTONE)) > 64;
    }

    /** Pickaxe in hand, a torch (or logs to make one), room for the yield, and the walk plus the expedition fit in the work day. */
    private boolean canStartDeep(ServerLevel level, Colony colony, Villager v) {
        List<BlockPos> route = colony.mineRoute;
        if (route.isEmpty() || !v.getMainHandItem().is(ItemTags.PICKAXES)) return false;
        if (findSlot(v.getInventory(), Items.TORCH) < 0
                && !ColonyStorage.has(level, colony, s -> s.is(Items.TORCH) || s.is(ItemTags.LOGS))) return false;
        if (!lootRoom(level, colony)) return false;
        int from = nearestIndex(route, v.blockPosition());
        long walk = Math.min(DEEP_WALK_LIMIT, (long) (route.size() - 1 - from) * DEEP_TICKS_PER_CELL);
        long tod = Math.floorMod(level.getDayTime(), 24000L);
        return tod + walk + deepTicks() <= WORK_END;
    }

    /** The face cannot advance: start a deep expedition if allowed, else go home and park for a while. */
    private void deepOrPark(ServerLevel level, Colony colony, Villager v, long now) {
        deepEligible = true;
        if (now >= nextDeep(colony)[0] && canStartDeep(level, colony, v)) {
            startDeep(level, v, now);
        } else {
            faceBlocked = true;
            returnHome(colony, v, now);
        }
    }

    private void startDeep(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
        phase = Phase.DEEP;
        enteredMining = true;
        blockTarget = null;
        oreTarget = null;
        adjacentTarget = null;
        deepTicks = deepTicks();
        deepEnd = Long.MAX_VALUE;
        deepArrived = false;
        deepWalkStart = now;
        Colony colony = colony(level, v);
        routeWalkIndex = colony == null || colony.mineRoute.isEmpty() ? 0 : nearestIndex(colony.mineRoute, v.blockPosition());
        Nav.reset(v);
        WorkTracker.status(v, STATUS_DEEP);
    }

    private void deepTick(ServerLevel level, Colony colony, Villager v, long now, long worldTime) {
        if (!deepArrived) {
            // Work happens at the face, so walk down the shaft first instead of idling at the storage.
            List<BlockPos> route = colony.mineRoute;
            int idx = route.isEmpty() ? -1 : Math.min(routeWalkIndex, route.size() - 1);
            boolean atEnd = idx < 0 || (idx == route.size() - 1 && at(v, route.get(idx)));
            if (!atEnd && now - deepWalkStart <= DEEP_WALK_LIMIT) {
                if (at(v, route.get(idx))) routeWalkIndex = ++idx;
                move(level, colony, v, route.get(Math.min(idx, route.size() - 1)), now);
                return;
            }
            if (!atEnd && !underground(colony, v)) {
                // Walk limit ran out near the surface: never mine "deep" from there, postpone instead.
                nextDeep(colony)[0] = now + 1200;
                WorkTracker.status(v, STATUS_DEEP_ABORT);
                returnHome(colony, v, now);
                return;
            }
            deepArrived = true;
            deepEnd = now + deepTicks;
            Nav.reset(v);
            v.getBrain().setMemory(ModRegistries.MINE_TARGET_POS.get(), v.blockPosition());
        }
        v.getNavigation().stop();
        if (worldTime % 40 == 0) v.swing(InteractionHand.MAIN_HAND);
        if (now < deepEnd) return;
        payDeep(level, colony, v, now, 1.0);
        returnHome(colony, v, now);
        if (!colony.mineRoute.isEmpty()) routeReturnIndex = nearestIndex(colony.mineRoute, v.blockPosition());
    }

    /** Next to a route cell and at least DEEP_MIN_DEPTH below the entrance. */
    private static boolean underground(Colony colony, Villager v) {
        List<BlockPos> route = colony.mineRoute;
        if (route.isEmpty()) return false;
        BlockPos pos = v.blockPosition();
        return route.get(nearestIndex(route, pos)).distSqr(pos) <= 9 && pos.getY() <= route.get(0).getY() - DEEP_MIN_DEPTH;
    }

    /** Share of the expedition already worked (0..1). */
    private double deepFraction(long now) {
        if (deepTicks <= 0 || deepEnd == Long.MAX_VALUE) return 0;
        return Math.max(0, Math.min(1, (now - (deepEnd - deepTicks)) / (double) deepTicks));
    }

    /** Deep-expedition yield scaled by the worked fraction; the pickaxe wears by the blocks this yield stands for. */
    private void payDeep(ServerLevel level, Colony colony, Villager v, long now, double fraction) {
        RandomSource r = v.getRandom();
        ItemStack held = v.getMainHandItem();
        boolean ironPlus = held.is(Items.IRON_PICKAXE) || held.is(Items.DIAMOND_PICKAXE) || held.is(Items.NETHERITE_PICKAXE);
        int cobble = share(48 + r.nextInt(49), fraction);
        int coal = share(8 + r.nextInt(13), fraction);
        int iron = share(4 + r.nextInt(9), fraction);
        int copper = share(4 + r.nextInt(13), fraction);
        int diamonds = ironPlus && r.nextInt(3) == 0 ? share(1 + r.nextInt(2), fraction) : 0;
        int redstone = ironPlus ? share(4 + r.nextInt(9), fraction) : 0;
        int sum = depositDeep(level, colony, Items.COBBLESTONE, cobble) + depositDeep(level, colony, Items.COAL, coal)
                + depositDeep(level, colony, Items.RAW_IRON, iron) + depositDeep(level, colony, Items.RAW_COPPER, copper)
                + depositDeep(level, colony, Items.DIAMOND, diamonds) + depositDeep(level, colony, Items.REDSTONE, redstone);
        // Ore blocks drop several copper/redstone items each.
        int blocks = cobble + coal + iron + (copper + 2) / 3 + diamonds + (redstone + 3) / 4;
        GatherUtil.wearTool(v, ItemTags.PICKAXES, blocks);
        colony.deepMiningTrips++;
        colony.deepMiningYield += sum;
        nextDeep(colony)[0] = now + 600;
        ColonyManager.markDirty(level);
        WorkTracker.credit(v, STATUS_DEEP_YIELD, sum);
        minedThisTrip += sum;
    }

    private static int share(int n, double fraction) {
        return (int) Math.round(n * fraction);
    }

    /** Deposits up to n items; returns how many actually fit (the rest is not produced). */
    private static int depositDeep(ServerLevel level, Colony colony, Item item, int n) {
        int left = n;
        int done = 0;
        while (left > 0) {
            int k = Math.min(left, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, k);
            ColonyStorage.deposit(level, colony, stack);
            done += k - stack.getCount();
            if (!stack.isEmpty()) break;
            left -= k;
        }
        return done;
    }

    // ---------------------------------------------------------------- inventory helpers

    /** Loot = any non-empty stack that is not a pickaxe, a torch or food. */
    private static boolean isLoot(ItemStack s) {
        return !s.isEmpty() && !s.is(ItemTags.PICKAXES) && !s.is(Items.TORCH) && !ColonyStorage.isFood(s);
    }

    private static int lootCount(Villager v) {
        SimpleContainer inv = v.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isLoot(inv.getItem(i))) n += inv.getItem(i).getCount();
        }
        return n;
    }

    private static int valuables(Villager v) {
        SimpleContainer inv = v.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.RAW_IRON) || s.is(Items.RAW_COPPER) || s.is(Items.RAW_GOLD) || s.is(Items.DIAMOND) || s.is(Items.EMERALD)
                    || s.is(Items.REDSTONE) || s.is(Items.LAPIS_LAZULI) || s.is(Items.COAL)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int count(SimpleContainer inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        }
        return n;
    }

    private static int findSlot(SimpleContainer inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** True if the whole drop list fits into the inventory (checked on a copy). */
    private static boolean fits(Villager v, List<ItemStack> drops) {
        SimpleContainer inv = v.getInventory();
        SimpleContainer copy = new SimpleContainer(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) copy.setItem(i, inv.getItem(i).copy());
        for (ItemStack d : drops) {
            if (!copy.addItem(d.copy()).isEmpty()) return false;
        }
        return true;
    }

    private static void giveAll(ServerLevel level, Villager v, List<ItemStack> drops) {
        for (ItemStack d : drops) {
            ItemStack rest = v.getInventory().addItem(d.copy());
            if (!rest.isEmpty()) level.addFreshEntity(new ItemEntity(level, v.getX(), v.getY() + 0.5, v.getZ(), rest));
        }
    }

    private static int nearestIndex(List<BlockPos> route, BlockPos pos) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double d = route.get(i).distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static BlockPos offset(BlockPos base, long packed) {
        return base.offset((int) (byte) (packed >> 16), (int) (byte) (packed >> 8), (int) (byte) packed);
    }

    private static long[] buildScan() {
        List<long[]> list = new ArrayList<>(105625);
        for (int dx = -32; dx <= 32; dx++) {
            for (int dy = -12; dy <= 12; dy++) {
                for (int dz = -32; dz <= 32; dz++) {
                    long packed = ((long) (dx & 0xFF) << 16) | ((long) (dy & 0xFF) << 8) | (dz & 0xFF);
                    list.add(new long[]{dx * dx + dy * dy + dz * dz, packed});
                }
            }
        }
        list.sort(Comparator.comparingLong(a -> a[0]));
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i)[1];
        return out;
    }
}
