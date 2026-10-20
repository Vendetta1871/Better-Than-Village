package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.Territory;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Damage;
import net.betterthanvillage.defense.ThreatManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
        if (nearAnyColony(level, pos)) {
            long now = level.getGameTime();
            data.recordPlacement(pos.immutable(), player.getUUID(), now);
            if (state.getBlock() instanceof DoorBlock) {
                data.recordPlacement(pos.above(), player.getUUID(), now);
            } else if (state.getBlock() instanceof BedBlock) {
                data.recordPlacement(pos.relative(state.getValue(BedBlock.FACING)), player.getUUID(), now);
            }
        }
        splitFromColonyChest(level, pos, state);
        if (level.getBlockEntity(pos) instanceof Container || nextToColonyStorage(level, pos)) {
            Protection.sanitizeStorages(level);
        }
    }

    /**
     * Player blocks matter only where colonies can reach: the territory at its maximum plus the 104-block player-build
     * check of colonization sites (founded up to {@link Colonization#MAX_SITE_DISTANCE} from a core).
     */
    private static boolean nearAnyColony(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            long range = Math.max(Math.max(colony.radius(), Territory.maxRadius(colony)), Colonization.MAX_SITE_DISTANCE) + 104L;
            long dx = pos.getX() - colony.core().getX(), dz = pos.getZ() - colony.core().getZ();
            if (dx * dx + dz * dz <= range * range) return true;
        }
        return false;
    }

    private static boolean nextToColonyStorage(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (Deeds.isColonyStorage(level, pos.relative(d))) return true;
        }
        return false;
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
        /** Colony whose storage is being broken, its item count and whether it holds valuables. */
        Colony storageColony;
        int storageItems;
        boolean storageValuable;
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
        if (level.getBlockEntity(pos) instanceof Container c && (s.storageColony = Theft.storageOwner(level, pos)) != null) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack item = c.getItem(i);
                s.storageItems += item.getCount();
                s.storageValuable |= !item.isEmpty() && Theft.valuable(item);
            }
        }
        BreakState companionOnly = s.companion == null && s.storageColony == null ? null : s;
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
        if (s.storageColony != null) {
            // A broken storage drops its contents: theft of the contents plus the chest, if noticed.
            Theft.taken(level, s.storageColony, player, s.storageItems + 1, s.storageValuable);
        }
        Colony colony = s.colony;
        if (colony == null) return;
        if (s.ownedByPlayer) {
            colony.repairs.remove(pos);
            s.snapshot.keySet().forEach(colony.repairs::remove);
            s.plot.status = JobStatus.COMPLETE;
            s.plot.completedOnce = true;
        } else {
            // The repair queue restores the block; the plot itself stays complete.
            Damage.recordRepairs(level, colony, s.snapshot);
            if (s.storageColony == null && !player.isCreative() && !player.isSpectator()) {
                ThreatManager.addPlayerHostility(level, colony, player, 4);
            }
        }
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- theft (02 §2.5 onClickSlot)

    /** One open colony container (per menu): net balance of what the player put in (+) or took out (-). */
    public static final class TheftState {
        UUID colony;
        /** Owner of the home chest, if the container is one. */
        UUID household;
        AbstractContainerMenu menu;
        Container container;
        final Map<Key, Integer> balance = new HashMap<>();
        /** Snapshot before the current click. */
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

    /** Before a container click: keeps the session of the open menu (or opens one) and snapshots the container. */
    public static TheftState beforeClick(ServerLevel level, Player player, TheftState session) {
        if (!(player.containerMenu instanceof ChestMenu menu)) return null;
        if (session == null || session.menu != menu) {
            session = openSession(level, menu);
        }
        if (session != null) {
            session.before = snapshot(session.container);
        }
        return session;
    }

    /** Session for a chest menu showing a colony storage or a home chest (either half of a double chest). */
    private static TheftState openSession(ServerLevel level, ChestMenu menu) {
        Container container = menu.getContainer();
        for (Colony colony : ColonyManager.data(level).colonies()) {
            Set<BlockPos> positions = new LinkedHashSet<>(colony.storages);
            positions.addAll(colony.managedStorages.keySet());
            colony.households.values().forEach(h -> positions.addAll(h.chests));
            for (BlockPos pos : positions) {
                if (!level.isLoaded(pos)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof Container c)) continue;
                // Cheap identity test first; the protection lookup walks plots and jobs.
                if ((c == container || (container instanceof CompoundContainer cc && cc.contains(c))) && !Protection.isProtectedContainer(level, pos)) {
                    TheftState s = new TheftState();
                    s.colony = colony.id();
                    var owner = net.betterthanvillage.economy.Households.ownerOf(colony, pos);
                    s.household = owner == null ? null : owner.owner;
                    s.menu = menu;
                    s.container = container;
                    return s;
                }
            }
        }
        return null;
    }

    /** After a click: only items taken beyond what the player deposited in this session count as theft. */
    public static void afterClick(ServerLevel level, Player player, TheftState s) {
        if (s == null || s.before == null) return;
        Map<Key, Integer> before = s.before;
        s.before = null;
        Colony colony = ColonyManager.data(level).colony(s.colony);
        if (colony == null) return;
        Map<Key, Integer> after = snapshot(s.container);
        Set<Key> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        int stolen = 0;
        boolean valuable = false;
        double value = 0;
        for (Key key : keys) {
            int delta = after.getOrDefault(key, 0) - before.getOrDefault(key, 0);
            if (delta == 0) continue;
            int balance = s.balance.getOrDefault(key, 0);
            int next = balance + delta;
            int newlyTaken = Math.max(0, -next) - Math.max(0, -balance);
            if (newlyTaken > 0) {
                stolen += newlyTaken;
                valuable |= Theft.valuable(key.stack());
                value += PlayerCredit.value(colony, key.stack(), newlyTaken);
            }
            s.balance.put(key, next);
        }
        // A creditor whose note nobody could pay may take the debtor's goods up to the debt (05 §Offences).
        if (stolen > 0 && s.household != null && value > 0 && value <= PlayerCredit.seizable(level, colony, s.household, player.getUUID())) {
            PlayerCredit.seize(level, colony, s.household, player.getUUID(), value);
            return;
        }
        Theft.taken(level, colony, player, stolen, valuable);
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

    /** True when a hopper must not pull from the block above it (a colony storage, or a double chest with one). */
    public static boolean blocksHopper(ServerLevel level, BlockPos above) {
        if (!level.getBlockState(above).hasBlockEntity()) return false;
        if (Theft.storageOwner(level, above) != null) return true;
        BlockPos other = Protection.otherChestHalf(level, above);
        return other != null && Theft.storageOwner(level, other) != null;
    }
}
