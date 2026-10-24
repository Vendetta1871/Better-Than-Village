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
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int REACH = 3;
    static final String STATUS_ACCESS = "проход к дому";
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
        if (LandWork.demolitionCount(colony) == 0 && LandWork.pitCount(colony) == 0 && LandWork.accessCount(colony) == 0) return false;
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
        var door = LandWork.nextAccess(level, colony, v.blockPosition());
        if (door != null) {
            access(level, colony, v, door.getKey(), door.getValue(), now);
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

    /**
     * Within reach of a fill cell: not above the miner's head (a lid laid from below would shut the miner in under
     * it), at most four blocks below its feet.
     */
    private static boolean nearFill(Villager v, BlockPos cell) {
        BlockPos at = v.blockPosition();
        return Math.abs(at.getX() - cell.getX()) <= REACH && Math.abs(at.getZ() - cell.getZ()) <= REACH
                && cell.getY() - at.getY() <= 1 && at.getY() - cell.getY() <= 4;
    }

    /** Within reach of the column and above the cell, nothing between: the fill can be thrown down to it. */
    private static boolean dropsInto(ServerLevel level, Villager v, BlockPos cell) {
        BlockPos at = v.blockPosition();
        if (Math.abs(at.getX() - cell.getX()) > REACH || Math.abs(at.getZ() - cell.getZ()) > REACH || at.getY() <= cell.getY()) return false;
        if (at.getY() - cell.getY() > LandWork.MAX_DEPRESSION_FILL + 2) return false;
        for (int y = cell.getY() + 1; y <= at.getY(); y++) {
            if (!level.getBlockState(new BlockPos(cell.getX(), y, cell.getZ())).canBeReplaced()) return false;
        }
        return true;
    }

    /** Where to stand to fill the column from above: the nearest dry ground within reach of it that is higher, or null. */
    private static BlockPos edge(ServerLevel level, Colony colony, Villager v, BlockPos cell) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if (dx == 0 && dz == 0) continue;
                int x = cell.getX() + dx, z = cell.getZ() + dz;
                BlockPos stand = new BlockPos(x, LandWork.ground(level, x, z) + 1, z);
                // Firm ground: not a column of the hole itself.
                if (stand.getY() <= cell.getY() || LandWork.plannedFill(colony, x, z) || !level.getFluidState(stand.below()).isEmpty()
                        || !level.getBlockState(stand).canBeReplaced() || !level.getBlockState(stand.above()).canBeReplaced()) {
                    continue;
                }
                double d = stand.distSqr(v.blockPosition());
                if (d < bestD) {
                    bestD = d;
                    best = stand;
                }
            }
        }
        return best;
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

    /** A doorway fix of an unreachable house: a block taken down (to the treasury) or a step of fill put in. */
    private void access(ServerLevel level, Colony colony, Villager v, BlockPos pos, boolean place, long now) {
        WorkTracker.status(v, STATUS_ACCESS);
        if (!near(v, pos)) {
            if (now % 10 == 0) Nav.approach(level, v, pos, 0.65, 9);
            if (Nav.isBlacklisted(v, pos, now)) LandWork.accessDone(colony, pos);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (place) {
            if (!timer.tickAction(level, v, pos, ColonyConfig.get().work.placement_ticks)) return;
            timer.reset();
            ItemStack block = ColonyStorage.withdrawOne(level, colony, FILL);
            if (block.isEmpty()) {
                WorkTracker.status(v, STATUS_NO_FILL);
                return;
            }
            BlockState state = block.is(Items.DIRT) ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            SafeEditScope.run(() -> level.setBlock(pos, state, 3));
        } else {
            if (!timer.tick(level, v, pos, true)) return;
            timer.reset();
            BlockState state = level.getBlockState(pos);
            List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), v, new ItemStack(Items.IRON_PICKAXE));
            SafeEditScope.run(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
            for (ItemStack d : drops) {
                ColonyStorage.deposit(level, colony, d);
                if (!d.isEmpty()) level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, d));
            }
        }
        LandWork.accessDone(colony, pos);
        LOGGER.info("[village] a miner of {} {} {} on the way to a house", net.betterthanvillage.politics.Countries.villageName(colony),
                place ? "fills" : "clears", pos.toShortString());
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STATUS_ACCESS, 1);
        ColonyManager.markDirty(level);
    }

    /** Jumps and puts a block of fill (stone first) under its feet, when there is room above. */
    private void pillarUp(ServerLevel level, Colony colony, Villager v) {
        BlockPos at = v.blockPosition();
        if (!v.onGround() || !level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above(2)).canBeReplaced()) return;
        if (!timer.tickAction(level, v, at, ColonyConfig.get().work.placement_ticks)) return;
        timer.reset();
        ItemStack block = ColonyStorage.withdrawOne(level, colony, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
        if (block.isEmpty()) block = ColonyStorage.withdrawOne(level, colony, FILL);
        if (block.isEmpty()) return;
        BlockState state = block.is(Items.DIRT) ? Blocks.DIRT.defaultBlockState()
                : block.is(Items.COBBLED_DEEPSLATE) ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        v.getNavigation().stop();
        v.setPos(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        SafeEditScope.run(() -> level.setBlock(at, state, 3));
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STATUS_FILL, 1);
    }

    /** Places one block of fill (dirt or cobblestone of the treasury) into a pit. */
    private void fill(ServerLevel level, Colony colony, Villager v, BlockPos cell, long now) {
        if (!ColonyStorage.has(level, colony, FILL)) {
            WorkTracker.status(v, STATUS_NO_FILL);
            return;
        }
        WorkTracker.status(v, STATUS_FILL);
        if (!nearFill(v, cell) && !dropsInto(level, v, cell)) {
            if (v.getBlockY() < cell.getY() && LandWork.plannedFill(colony, v.getBlockX(), v.getBlockZ())) {
                // Down in the pit, below its lid: it climbs out the way a player would, on a pillar under its feet.
                pillarUp(level, colony, v);
                return;
            }
            // A deep hole is filled from its edge: the earth is thrown down the open column.
            BlockPos edge = edge(level, colony, v, cell);
            Nav.approach(level, v, edge == null ? cell : edge, 0.65, edge == null ? 9 : 1);
            if (Nav.isBlacklisted(v, edge == null ? cell : edge, now)) LandWork.dropPit(colony, cell);
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
        // Stone below, earth on top: the filled hole grows grass again like the ground around it.
        boolean top = cell.getY() >= LandWork.fillLevel(colony, cell);
        Predicate<ItemStack> dirt = s -> s.is(Items.DIRT);
        Predicate<ItemStack> stone = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
        ItemStack block = ColonyStorage.withdrawOne(level, colony, top ? dirt : stone);
        if (block.isEmpty()) block = ColonyStorage.withdrawOne(level, colony, FILL);
        if (block.isEmpty()) return;
        BlockState state = block.is(Items.DIRT) ? Blocks.DIRT.defaultBlockState()
                : block.is(Items.COBBLED_DEEPSLATE) ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        SafeEditScope.run(() -> level.setBlock(cell, state, 3));
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STATUS_FILL, 1);
    }
}
