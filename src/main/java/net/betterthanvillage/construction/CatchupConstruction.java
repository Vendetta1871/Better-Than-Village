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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/** Offline catch-up building: places pending catch-up job blocks within a block budget (05 §2.10). */
public final class CatchupConstruction {
    public static final int MAX_ITERATIONS = 128;

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
        List<BuildPlan.Step> plan = BuildPlan.load(level, job);
        if (plan == null) {
            HousePlanner.block(job, level.getGameTime() + 1200);
            return 0;
        }
        long now = level.getGameTime();
        HousePlanner.setPlan(job, plan.size(), now);
        int executed = 0;
        BoundingBox box = job.box();
        for (int it = 0; it < MAX_ITERATIONS && job.nextBlock < job.totalBlocks && executed < budget && colony.catchupLabor > 0; it++) {
            BuildPlan.Step s = plan.get(job.nextBlock);
            BlockPos pos = s.pos();
            if (!level.isLoaded(pos)) break;
            BlockState world = level.getBlockState(pos);
            boolean skip = BuildPlan.matches(world, s.state())
                    || (s.foundation() && world.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP) && world.getFluidState().isEmpty())
                    || (s.clear() && world.isAir());
            if (skip) {
                job.nextBlock++;
                continue;
            }
            if (HouseConstructionTask.builderProtected(level, pos)) break;
            if (s.clear()) {
                if (!Terrain.clearable(world) || !breakable(level, pos)) break;
                if (!SafeEditScope.call(() -> level.destroyBlock(pos, false))) break;
            } else {
                boolean inFoundation = pos.getX() >= box.minX() && pos.getX() <= box.maxX() && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ()
                        && pos.getY() >= job.origin.getY() - 4 && pos.getY() <= job.origin.getY();
                if (!(world.isAir() || Terrain.clearable(world) || s.foundation() || (world.is(Blocks.COBBLESTONE) && inFoundation))) break;
                boolean free = BuildPlan.materialItem(s.state()) == Items.AIR;
                Predicate<ItemStack> accepted = st -> BuildPlan.accepts(s.state(), st);
                if (!free && !ColonyStorage.has(level, colony, accepted)) break;
                if (!SafeEdit.place(level, pos, s.state(), 3)) {
                    HouseConstructionTask.pushOut(level, job, pos, now);
                    break;
                }
                if (!free && ColonyStorage.withdrawOne(level, colony, accepted).isEmpty()) {
                    SafeEditScope.run(() -> level.setBlock(pos, world, 3));
                    break;
                }
            }
            colony.catchupLabor--;
            job.nextBlock++;
            executed++;
            job.lastProgressTick = now;
        }
        if (job.nextBlock >= job.totalBlocks) {
            HousePlanner.complete(level, job);
        }
        if (executed > 0) ColonyManager.markDirty(level);
        return executed;
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
