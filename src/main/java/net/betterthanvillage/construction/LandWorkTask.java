package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Commissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** The miner's land work planned by {@link LandWork}: demolishing illegal builds first, then filling pits. Public work. */
public final class LandWorkTask extends ColonyTask {
    static final String STATUS_DEMOLISH = "снос незаконной постройки";
    static final String STATUS_FILL = "засыпка ямы";
    static final String STATUS_NO_FILL = "нет земли или булыжника для засыпки";
    private static final Predicate<ItemStack> FILL = s -> s.is(Items.DIRT) || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
    /** The miner works on a block from this far (horizontal), whatever its height. */
    private static final int REACH = 3;
    private static final int MAX_HEIGHT = 8;

    private final BlockWorkTimer timer = new BlockWorkTimer();

    public LandWorkTask() {
        super(6000);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    /** The miner has land work to do (the mining task then yields to it). */
    public static boolean wanted(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || v.isTrading() || colony.roleOf(v.getUUID()) != ColonyRole.MINER || !ColonyManager.terrainChangesAllowed(level)) return false;
        if (LandWork.demolitionCount(colony) == 0 && LandWork.pitCount(colony) == 0) return false;
        return Commissions.canPay(level, colony);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (!wanted(level, v, colony)) return false;
        // Not in the middle of a mining trip.
        for (var b : v.getBrain().getRunningBehaviors()) {
            if (b instanceof net.betterthanvillage.gather.MinerTask) return false;
        }
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        return colony != null && wanted(level, v, colony);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        if (colony == null) return;
        BlockPos target = LandWork.nextDemolition(level, colony, v.blockPosition());
        if (target != null) {
            demolish(level, colony, v, target, now);
            return;
        }
        if (now % 5 != 0) return;
        BlockPos cell = LandWork.nextFill(level, colony, v.blockPosition());
        if (cell != null) fill(level, colony, v, cell, now);
    }

    private static boolean near(Villager v, BlockPos pos) {
        BlockPos at = v.blockPosition();
        return Math.abs(at.getX() - pos.getX()) <= REACH && Math.abs(at.getZ() - pos.getZ()) <= REACH
                && pos.getY() - at.getY() <= MAX_HEIGHT && at.getY() - pos.getY() <= 4;
    }

    /** Breaks one condemned block; its drops (and a container's contents) go to the treasury. */
    private void demolish(ServerLevel level, Colony colony, Villager v, BlockPos target, long now) {
        WorkTracker.status(v, STATUS_DEMOLISH);
        if (!near(v, target)) {
            if (now % 10 == 0) Nav.approach(level, v, target, 0.65, 9);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (!timer.tick(level, v, target, true)) return;
        timer.reset();
        BlockState state = level.getBlockState(target);
        List<ItemStack> confiscated = new ArrayList<>();
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.removeItemNoUpdate(i);
                if (!s.isEmpty()) confiscated.add(s);
            }
        }
        confiscated.addAll(Block.getDrops(state, level, target, be, v, new ItemStack(Items.IRON_PICKAXE)));
        SafeEditScope.run(() -> level.setBlock(target, Blocks.AIR.defaultBlockState(), 3));
        ColonyManager.data(level).forgetPlacement(target);
        LandWork.demolished(colony, target);
        for (ItemStack s : confiscated) {
            ColonyStorage.deposit(level, colony, s);
            if (!s.isEmpty()) level.addFreshEntity(new ItemEntity(level, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, s));
        }
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STATUS_DEMOLISH, 1);
        ColonyManager.markDirty(level);
    }

    /** Places one block of fill (dirt or cobblestone of the treasury) into a pit. */
    private void fill(ServerLevel level, Colony colony, Villager v, BlockPos cell, long now) {
        if (!ColonyStorage.has(level, colony, FILL)) {
            WorkTracker.status(v, STATUS_NO_FILL);
            return;
        }
        WorkTracker.status(v, STATUS_FILL);
        if (!near(v, cell)) {
            Nav.approach(level, v, cell, 0.65, 9);
            if (Nav.isBlacklisted(v, cell, now)) LandWork.dropPit(colony, cell);
            return;
        }
        if (HouseConstructionTask.standsOn(v, cell) || HouseConstructionTask.standsOn(v, cell.above())) {
            // Step out of the pit first.
            Nav.approach(level, v, cell.offset(2, 0, 0), 0.65, 1);
            return;
        }
        if (!level.getBlockState(cell).canBeReplaced()) {
            LandWork.dropPit(colony, cell);
            return;
        }
        if (!timer.tickAction(level, v, cell, ColonyConfig.get().work.placement_ticks)) return;
        timer.reset();
        ItemStack block = ColonyStorage.withdrawOne(level, colony, FILL);
        if (block.isEmpty()) return;
        BlockState state = block.is(Items.DIRT) ? Blocks.DIRT.defaultBlockState()
                : block.is(Items.COBBLED_DEEPSLATE) ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        SafeEditScope.run(() -> level.setBlock(cell, state, 3));
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STATUS_FILL, 1);
    }
}
