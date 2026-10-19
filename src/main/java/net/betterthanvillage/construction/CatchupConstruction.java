package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.ai.work.SafeEdit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/** Offline catch-up building: places pending catch-up job blocks within a block budget (05 §2.10). */
public final class CatchupConstruction {
    public static final int MAX_ITERATIONS = 128;
    /** Obstacle stall after which a catch-up plot counts as one blocked cycle. */
    private static final long STALL_TICKS = 6000;

    private CatchupConstruction() {
    }

    /** Returns the number of blocks placed (consumed from the budget). */
    public static int step(ServerLevel level, Colony colony, int budget) {
        if (budget <= 0 || !ColonyManager.terrainChangesAllowed(level)) return 0;
        ConstructionJob job = null;
        for (ConstructionJob j : colony.jobs) {
            if (j.catchup && (j.status == JobStatus.QUEUED || j.status == JobStatus.BUILDING)) {
                job = j;
                break;
            }
        }
        if (job == null || job.owner != null || job.countsAsCompleted() || job.builder != null) return 0;
        if (!HousePlanner.protectPen(level, colony, job)) return 0;
        long now = level.getGameTime();
        List<BuildPlan.Step> plan = BuildPlan.load(level, job);
        if (plan == null) {
            HousePlanner.blockOrAbandon(level, colony, job, now + 1200);
            return 0;
        }
        HousePlanner.setPlan(job, plan.size(), now);
        int executed = 0;
        boolean progressed = false;
        boolean obstacle = false;
        boolean blocked = false;
        for (int it = 0; it < MAX_ITERATIONS && job.nextBlock < job.totalBlocks && executed < budget && colony.catchupLabor > 0; it++) {
            BuildPlan.Step s = plan.get(job.nextBlock);
            BlockPos pos = s.pos();
            if (!level.isLoaded(pos)) break;
            if (BuildPlan.satisfied(level, s)) {
                job.nextBlock++;
                progressed = true;
                continue;
            }
            BlockState world = level.getBlockState(pos);
            boolean isProtected = s.clear() ? HouseConstructionTask.builderProtected(level, pos)
                    : HouseConstructionTask.placementProtected(level, pos, world);
            if (isProtected) {
                if (HouseConstructionTask.areaProtected(level, pos)) {
                    HousePlanner.blockOrAbandon(level, colony, job, now + 1200);
                    blocked = true;
                    break;
                }
                // a single protected block stays; the house is built around it (as the live builder does)
                job.nextBlock++;
                progressed = true;
                continue;
            }
            if (s.clear()) {
                if (!breakable(level, pos) || !SafeEditScope.call(() -> level.destroyBlock(pos, false))) {
                    obstacle = true;
                    break;
                }
            } else {
                boolean free = BuildPlan.free(s.state());
                if (!free && !hasMaterial(level, colony, s.state())) break;
                if (!SafeEdit.place(level, pos, s.state(), 3)) {
                    HouseConstructionTask.pushOut(level, job, pos, now);
                    obstacle = true;
                    break;
                }
                if (!free && !takeMaterial(level, colony, s.state())) {
                    SafeEditScope.run(() -> level.setBlock(pos, world, 3));
                    break;
                }
            }
            colony.catchupLabor--;
            job.nextBlock++;
            executed++;
            progressed = true;
            job.lastProgressTick = now;
        }
        if (blocked) {
            job.catchupStallSince = Long.MIN_VALUE;
        } else if (progressed) {
            job.blockCount = 0;
            job.catchupStallSince = Long.MIN_VALUE;
        } else if (obstacle) {
            // an obstacle that never clears (falling block above, mob parked on the spot) must not freeze catch-up forever
            if (job.catchupStallSince == Long.MIN_VALUE || now < job.catchupStallSince) {
                job.catchupStallSince = now;
            } else if (now - job.catchupStallSince > STALL_TICKS) {
                job.catchupStallSince = Long.MIN_VALUE;
                HousePlanner.blockOrAbandon(level, colony, job, now + 1200);
            }
        }
        if (job.nextBlock >= job.totalBlocks) {
            HousePlanner.complete(level, job);
        }
        if (executed > 0) ColonyManager.markDirty(level);
        return executed;
    }

    /** Accepted material in the virtual catch-up stock or in storage. */
    private static boolean hasMaterial(ServerLevel level, Colony colony, BlockState state) {
        for (Map.Entry<Item, Integer> e : colony.catchupStock.entrySet()) {
            if (e.getValue() > 0 && BuildPlan.accepts(state, new ItemStack(e.getKey()))) return true;
        }
        return ColonyStorage.has(level, colony, st -> BuildPlan.accepts(state, st));
    }

    /** Takes one accepted item: the virtual catch-up stock first (exact item preferred), then storage. */
    private static boolean takeMaterial(ServerLevel level, Colony colony, BlockState state) {
        Item exact = BuildPlan.materialItem(state);
        Item pick = null;
        for (Map.Entry<Item, Integer> e : colony.catchupStock.entrySet()) {
            if (e.getValue() <= 0 || !BuildPlan.accepts(state, new ItemStack(e.getKey()))) continue;
            pick = e.getKey();
            if (pick == exact) break;
        }
        if (pick != null) {
            int n = colony.catchupStock.get(pick);
            if (n <= 1) colony.catchupStock.remove(pick);
            else colony.catchupStock.put(pick, n - 1);
            return true;
        }
        return !ColonyStorage.withdrawOne(level, colony, st -> BuildPlan.accepts(state, st)).isEmpty();
    }

    /** Safe to break without drops: no entity standing on it, no falling block above, terrain editable. */
    private static boolean breakable(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) return false;
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos.above()).inflate(0.02)).isEmpty()) return false;
        return Protection.terrainModifiable(level, pos);
    }

    /** Plans a catch-up house job if possible; returns true when one was queued. */
    public static boolean planCatchupHouse(ServerLevel level, Colony colony) {
        return HousePlanner.planOffline(level, colony);
    }
}
