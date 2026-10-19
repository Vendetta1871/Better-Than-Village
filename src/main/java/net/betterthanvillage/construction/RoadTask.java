package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;

/** Builder road task: lays the queued 1-wide dirt path (05 §3.2). */
public final class RoadTask extends ColonyTask {
    static final String STATUS_SKIPPED = "непроходимый сегмент дороги пропущен: ";

    private final BlockWorkTimer timer = new BlockWorkTimer();

    public RoadTask() {
        super(12000);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && !v.isTrading() && colony.roleOf(v.getUUID()) == ColonyRole.BUILDER && HousePlanner.established(level, colony)
                && colony.hasStorage() && !colony.roadQueue.isEmpty() && !Fortifications.isWallBuilder(level, colony, v)
                && !Damage.hasActionableRepair(level, colony);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        return colony != null && shouldRun(level, v, colony);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (now % 10 != 0) return;
        Colony colony = colony(level, v);
        if (colony == null || colony.roadQueue.isEmpty()) return;
        if (!ColonyManager.terrainChangesAllowed(level)) {
            WorkTracker.status(v, HouseConstructionTask.STATUS_NO_TERRAIN);
            return;
        }
        BlockPos node = colony.roadQueue.get(0);
        if (!node.equals(colony.roadAttemptHead)) {
            colony.roadAttemptHead = node;
            colony.roadStallTicks = 0;
        }
        if (Roads.isRoad(level, node)) {
            Roads.pop(colony, false);
            return;
        }
        BlockPos above = node.above();
        if (!Roads.validNode(level, colony, node) || Nav.isBlacklisted(v, above, now) || colony.roadStallTicks >= 600) {
            Roads.pop(colony, true);
            WorkTracker.status(v, STATUS_SKIPPED + node.toShortString());
            ColonyManager.markDirty(level);
            return;
        }
        if (v.blockPosition().distSqr(above) > 9) {
            Roads.addStall(colony, now);
            Nav.approach(level, v, above, 0.65, Nav.R2_DEFAULT);
            return;
        }
        if (!level.getBlockState(above).isAir()) {
            boolean broken = !HouseConstructionTask.standsOn(v, above) && Protection.terrainModifiable(level, above)
                    && SafeEditScope.call(() -> level.destroyBlock(above, true, v));
            if (!broken) Roads.addStall(colony, now);
            return;
        }
        if (!timer.tickAction(level, v, node, ColonyConfig.get().work.placement_ticks)) return;
        timer.reset();
        if (SafeEdit.place(level, node, Blocks.DIRT_PATH.defaultBlockState(), 3)) {
            Roads.pop(colony, false);
            WorkTracker.credit(v, "дорога", 1);
            v.swing(InteractionHand.MAIN_HAND);
            ColonyManager.markDirty(level);
        } else {
            Roads.addStall(colony, now);
        }
    }
}
