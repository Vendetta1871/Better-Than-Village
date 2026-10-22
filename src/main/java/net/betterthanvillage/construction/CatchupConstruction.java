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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Offline catch-up building: places pending catch-up job blocks within a block budget (05 §2.10). */
public final class CatchupConstruction {
    public static final int MAX_ITERATIONS = 128;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** No progress for this long hands a catch-up house of a live village to its masons. */
    private static final long STARVED_TICKS = 1200;
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
        if (job == null) return 0;
        long now = level.getGameTime();
        // In a live village the catch-up only speeds things up: a house it cannot move on (no material, an
        // obstacle, a builder still assigned, ...) goes to the masons instead of waiting for days.
        if (job.lastProgressTick == Long.MIN_VALUE || now < job.lastProgressTick) job.lastProgressTick = now;
        if (ColonyManager.isLive(level, colony) && now - job.lastProgressTick > STARVED_TICKS) {
            handOver(level, colony, job);
            return 0;
        }
        if (job.owner != null || job.countsAsCompleted() || job.builder != null) return 0;
        if (!HousePlanner.protectPen(level, colony, job)) return 0;
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
        boolean starved = false;
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
                List<ItemStack> drops = Block.getDrops(world, level, pos, level.getBlockEntity(pos));
                if (!breakable(level, pos) || !SafeEditScope.call(() -> level.destroyBlock(pos, false))) {
                    obstacle = true;
                    break;
                }
                for (ItemStack d : drops) ColonyStorage.deposit(level, colony, d);
            } else {
                BuildCost.Cost cost = HouseConstructionTask.cost(level, s);
                BuildCost.Source source = BuildCost.stockThenStorage(level, colony);
                if (!cost.free() && BuildCost.payable(cost, source) == null) {
                    if (cost.kind() == BuildCost.Kind.STRUCTURAL) {
                        starved = true;
                        break;
                    }
                    // functional blocks wait as repair entries; decoration is skipped
                    if (cost.kind() == BuildCost.Kind.DEFERRABLE) Damage.defer(level, colony, pos, s.state());
                    job.nextBlock++;
                    progressed = true;
                    continue;
                }
                if (!SafeEdit.place(level, pos, s.state(), 3)) {
                    HouseConstructionTask.pushOut(level, job, pos, now);
                    obstacle = true;
                    break;
                }
                if (!BuildCost.pay(cost, source)) {
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
        } else if (starved) {
            // No material for the next block: waits (and is handed over by the stall rule above).
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
            int rewind = BuildPlan.verify(level, colony, job, plan, !job.verified);
            job.verified = true;
            if (rewind >= 0) {
                job.nextBlock = rewind;
            } else {
                HousePlanner.complete(level, job);
            }
        }
        if (executed > 0) ColonyManager.markDirty(level);
        return executed;
    }

    /** Ids of houses handed over to the masons: the catch-up never takes them back. */
    public static java.util.Set<java.util.UUID> handedOver(Colony colony) {
        return colony.runtime("btv_catchup_handed_over", java.util.HashSet::new);
    }

    private static void handOver(ServerLevel level, Colony colony, ConstructionJob job) {
        handedOver(colony).add(job.id);
        job.catchup = false;
        job.catchupStallSince = Long.MIN_VALUE;
        job.lastProgressTick = level.getGameTime();
        if (job.builder != null) job.releaseBuilder();
        colony.catchupHouses = Math.max(0, colony.catchupHouses - 1);
        ColonyManager.markDirty(level);
        LOGGER.info("[village] the catch-up of a house in {} is stuck at {}/{}: the masons take it over",
                net.betterthanvillage.politics.Countries.villageName(colony), job.nextBlock, job.totalBlocks);
    }

    /** Safe to break: no entity standing on it, no falling block above, terrain editable. */
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
