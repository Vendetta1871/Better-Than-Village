package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.Damage;
import net.betterthanvillage.defense.ThreatManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Player-block registry recording, griefing on player breaks, storage protection and theft detection (06 §4). */
public final class PlayerBlocks {
    private PlayerBlocks() {
    }

    // ---------------------------------------------------------------- placements (02 §2.5 BlockItem.place)

    public static void onPlaced(ServerLevel level, Player player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        LandNotices.onPlacement(level, player, pos, state);
        ColonySavedData data = ColonyManager.data(level);
        long now = level.getGameTime();
        data.recordPlacement(pos.immutable(), player.getUUID(), now);
        if (state.getBlock() instanceof DoorBlock) {
            data.recordPlacement(pos.above(), player.getUUID(), now);
        } else if (state.getBlock() instanceof BedBlock) {
            data.recordPlacement(pos.relative(state.getValue(BedBlock.FACING)), player.getUUID(), now);
        }
        splitFromColonyChest(level, pos, state);
        Protection.sanitizeStorages(level);
    }

    /** A player chest that joined a colony chest is split back into two single chests. */
    private static void splitFromColonyChest(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
        BlockPos other = Protection.otherChestHalf(level, pos);
        if (other == null || !(level.getBlockState(other).getBlock() instanceof ChestBlock) || Protection.isProtectedContainer(level, other)) return;
        if (Deeds.isColonyStorage(level, other) && Protection.isProtectedContainer(level, pos)) {
            Protection.splitChest(level, pos);
        }
    }

    // ---------------------------------------------------------------- breaks (02 §2.5 tryBreakBlock)

    /** Per-break state captured at HEAD. */
    public static final class BreakState {
        /** Null when the break needs no colony processing (only the companion half is tracked). */
        Colony colony;
        ConstructionJob plot;
        boolean ownedByPlayer;
        Map<BlockPos, BlockState> snapshot = Map.of();
        /** Other half of a broken door or bed and its block. */
        BlockPos companion;
        Block companionBlock;
    }

    /** The other half of a door or bed, or null. */
    private static BlockPos companionHalf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING);
            return pos.relative(state.getValue(BedBlock.PART) == BedPart.FOOT ? facing : facing.getOpposite());
        }
        return null;
    }

    public static BreakState beforeBreak(ServerLevel level, Player player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BreakState s = new BreakState();
        s.companion = companionHalf(pos, state);
        s.companionBlock = state.getBlock();
        BreakState companionOnly = s.companion == null ? null : s;
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null || !Damage.isColonyOwned(level, colony, pos)) return companionOnly;
        for (ConstructionJob job : colony.jobs) {
            if (job.contains(pos)) {
                s.plot = job;
                break;
            }
        }
        s.ownedByPlayer = s.plot != null && s.plot.countsAsCompleted() && player.getUUID().equals(s.plot.owner);
        s.snapshot = Damage.restoreSnapshot(level, pos, state);
        if (s.snapshot.isEmpty() && !s.ownedByPlayer) return companionOnly;
        s.colony = colony;
        return s;
    }

    public static void afterBreak(ServerLevel level, Player player, BlockPos pos, BreakState s, boolean success) {
        if (!success) return;
        ColonySavedData data = ColonyManager.data(level);
        data.forgetPlacement(pos);
        if (s == null) return;
        if (s.companion != null && !level.getBlockState(s.companion).is(s.companionBlock)) {
            data.forgetPlacement(s.companion);
        }
        Colony colony = s.colony;
        if (colony == null) return;
        if (s.ownedByPlayer) {
            colony.repairs.remove(pos);
            s.snapshot.keySet().forEach(colony.repairs::remove);
            s.plot.status = JobStatus.COMPLETE;
            s.plot.completedOnce = true;
        } else {
            Damage.recordRepairs(level, colony, s.snapshot);
            if (s.plot != null && s.plot.status == JobStatus.COMPLETE) {
                if (s.plot.owner != null && s.plot.countsAsCompleted()) {
                    s.plot.builder = null;
                } else {
                    s.plot.status = JobStatus.QUEUED;
                    s.plot.builder = null;
                    s.plot.nextBlock = 0;
                    s.plot.retryAfter = 0;
                    s.plot.lastProgressTick = level.getGameTime();
                }
            }
            if (!player.isCreative() && !player.isSpectator()) {
                ThreatManager.addPlayerHostility(level, colony, player, 4);
            }
        }
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- theft (02 §2.5 onClickSlot)

    /** Container snapshot captured before a click. */
    public static final class TheftState {
        UUID colony;
        Container container;
        Map<Key, Integer> before;
    }

    /** Item + components key (count ignored). */
    record Key(ItemStack stack) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && ItemStack.isSameItemSameComponents(stack, k.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    public static TheftState beforeClick(ServerLevel level, Player player) {
        if (!(player.containerMenu instanceof ChestMenu menu)) return null;
        Container container = menu.getContainer();
        for (Colony colony : ColonyManager.data(level).colonies()) {
            for (BlockPos pos : colony.managedStorages.keySet()) {
                if (!level.isLoaded(pos)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof Container c)) continue;
                // Cheap identity test first; the protection lookup walks plots and jobs.
                if ((c == container || (container instanceof CompoundContainer cc && cc.contains(c))) && !Protection.isProtectedContainer(level, pos)) {
                    TheftState s = new TheftState();
                    s.colony = colony.id();
                    s.container = container;
                    s.before = snapshot(container);
                    return s;
                }
            }
        }
        return null;
    }

    public static void afterClick(ServerLevel level, Player player, TheftState s) {
        if (s == null) return;
        Colony colony = ColonyManager.data(level).colony(s.colony);
        if (colony == null) return;
        Map<Key, Integer> after = snapshot(s.container);
        int taken = 0;
        for (Map.Entry<Key, Integer> e : s.before.entrySet()) {
            taken += Math.max(0, e.getValue() - after.getOrDefault(e.getKey(), 0));
        }
        if (taken > 0 && !player.isCreative() && !player.isSpectator()) {
            ThreatManager.reportTheft(level, colony, player, taken);
        }
    }

    private static Map<Key, Integer> snapshot(Container container) {
        Map<Key, Integer> map = new HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) {
                map.merge(new Key(s.copyWithCount(1)), s.getCount(), Integer::sum);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- hoppers (06 §4.3)

    /** True when a hopper must not pull from the block above it (a managed colony storage that is not player storage). */
    public static boolean blocksHopper(ServerLevel level, BlockPos above) {
        return Protection.isManagedAnywhere(level, above) && !Protection.isProtectedContainer(level, above);
    }
}
