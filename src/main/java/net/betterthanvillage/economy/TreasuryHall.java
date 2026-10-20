package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.colony.StorageKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * The treasury lives in the elder's house (docs_v2/03-economy.md §Taxes and treasury). Its chests are placed inside
 * that house; chests of the treasury elsewhere (the first warehouse by the bell, the old hall after a new elder)
 * are emptied into the hall one at a time, and those standing outdoors are taken down. A full hall gets another
 * chest, paid by the treasury.
 */
public final class TreasuryHall {
    /** The hall is the elder's house: this far from the elder's bed (horizontal, vertical). */
    static final int HALL_XZ = 6;
    static final int HALL_Y = 3;
    /** The hall gets another chest when fewer slots than this are free. */
    private static final int MIN_FREE_SLOTS = 9;
    /** At most this many treasury chests in the hall. */
    private static final int MAX_CHESTS = 8;

    private TreasuryHall() {
    }

    /** The elder's bed, the centre of the hall; null while the elder is homeless or there is no elder. */
    public static BlockPos hall(Colony colony) {
        Household h = colony.elder == null ? null : colony.households.get(colony.elder);
        return h == null ? null : h.bed;
    }

    /**
     * The hall in use: it stays where it is while the same elder lives (villagers swap beds at night) and the
     * bed still stands; a new elder, or the loss of the bed, moves it to the elder's present bed.
     */
    static BlockPos currentHall(ServerLevel level, Colony colony) {
        BlockPos bed = hall(colony);
        boolean keep = colony.hallBed != null && colony.elder != null && colony.elder.equals(colony.hallElder)
                && (!level.isLoaded(colony.hallBed) || level.getBlockState(colony.hallBed).getBlock() instanceof BedBlock);
        if (!keep && bed != null) {
            colony.hallBed = bed;
            colony.hallElder = colony.elder;
            ColonyManager.markDirty(level);
        }
        return keep ? colony.hallBed : bed;
    }

    static boolean inHall(BlockPos hall, BlockPos p) {
        return Math.abs(p.getX() - hall.getX()) <= HALL_XZ && Math.abs(p.getZ() - hall.getZ()) <= HALL_XZ
                && Math.abs(p.getY() - hall.getY()) <= HALL_Y;
    }

    /** Census step: one relocation or one expansion. */
    public static void ensure(ServerLevel level, Colony colony) {
        if (!ColonyManager.terrainChangesAllowed(level)) return;
        BlockPos hall = currentHall(level, colony);
        if (hall == null || !level.isLoaded(hall)) {
            // No hall yet: a full treasury still grows where it is.
            Treasury.expand(level, colony);
            return;
        }
        List<BlockPos> inside = new ArrayList<>();
        List<BlockPos> outside = new ArrayList<>();
        for (BlockPos pos : colony.managedStorages.keySet()) {
            (inHall(hall, pos) ? inside : outside).add(pos);
        }
        if (!outside.isEmpty()) {
            relocate(level, colony, hall, outside.get(0), inside);
        } else if (inside.size() < MAX_CHESTS && freeSlots(level, inside) < MIN_FREE_SLOTS) {
            List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
            boolean paid = !Chests.take(treasury, s -> s.is(ItemTags.PLANKS), 8).isEmpty()
                    || !Chests.take(treasury, s -> s.is(ItemTags.LOGS), 2).isEmpty();
            if (paid) placeChest(level, colony, hall);
        }
        if (!colony.looseAbsorbed && colony.lastScanFinish != Colony.NEVER && colony.householdsSettled) {
            absorbLoose(level, colony);
        }
    }

    /** Empties one treasury chest outside the hall into the hall (adding a hall chest for it as needed). */
    private static void relocate(ServerLevel level, Colony colony, BlockPos hall, BlockPos from, List<BlockPos> inside) {
        Container c = ColonyStorage.container(level, from);
        if (c == null) {
            if (level.isLoaded(from)) colony.removeManagedStorage(from);
            return;
        }
        List<ColonyStorage.Slot> hallSlots = slots(level, inside);
        if (hallSlots.isEmpty() || freeSlots(level, inside) < occupied(c)) {
            // The chest moves house: a hall chest replaces it (no cost, it is the same chest).
            if (inside.size() < MAX_CHESTS && placeChest(level, colony, hall)) return;
            if (hallSlots.isEmpty()) return;
        }
        empty(c, hallSlots);
        if (occupied(c) > 0) return;
        colony.removeManagedStorage(from);
        // Treasury chests out in the street are taken down; one inside some house stays as a loose chest.
        if (level.canSeeSky(from.above())) {
            SafeEditScope.run(() -> level.setBlock(from, Blocks.AIR.defaultBlockState(), 3));
        } else if (!colony.looseChests.contains(from)) {
            colony.looseChests.add(from);
        }
        ColonyManager.markDirty(level);
    }

    /** Once after settlement: what loose village chests hold (outside houses' own chests) goes to the treasury. */
    private static void absorbLoose(ServerLevel level, Colony colony) {
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        if (treasury.isEmpty()) return;
        for (BlockPos pos : colony.looseChests) {
            Container c = ColonyStorage.container(level, pos);
            if (c != null) empty(c, treasury);
        }
        colony.looseAbsorbed = true;
        ColonyManager.markDirty(level);
    }

    /** Moves everything that fits from a container into the chests. */
    static void empty(Container from, List<ColonyStorage.Slot> to) {
        boolean changed = false;
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack s = from.getItem(i);
            if (s.isEmpty()) continue;
            List<ItemStack> rest = Chests.put(to, List.of(s.copy()));
            from.setItem(i, rest.isEmpty() ? ItemStack.EMPTY : rest.get(0));
            changed = true;
        }
        if (changed) from.setChanged();
    }

    /** A single treasury chest inside the elder's house (indoors first), registered as a managed storage. */
    static boolean placeChest(ServerLevel level, Colony colony, BlockPos hall) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(hall.offset(-HALL_XZ, -HALL_Y, -HALL_XZ), hall.offset(HALL_XZ, HALL_Y, HALL_XZ))) {
            if (!Households.chestFits(level, colony, p) || nextToBed(level, p)) continue;
            int score = (level.canSeeSky(p) ? 100 : 0) + (int) p.distManhattan(hall);
            if (score < bestScore) {
                bestScore = score;
                best = p.immutable();
            }
        }
        if (best == null) return false;
        BlockPos at = best;
        SafeEditScope.run(() -> level.setBlock(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 3));
        if (ColonyStorage.container(level, at) == null) return false;
        colony.managedStorages.put(at, StorageKind.GENERAL);
        if (!colony.storages.contains(at)) colony.storages.add(at);
        colony.starterWarehousePlaced = true;
        ColonyManager.markDirty(level);
        return true;
    }

    /** Beside a bed: a chest there could block the way to it. */
    private static boolean nextToBed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(p.relative(d)).getBlock() instanceof BedBlock) return true;
        }
        return false;
    }

    private static List<ColonyStorage.Slot> slots(ServerLevel level, List<BlockPos> positions) {
        List<ColonyStorage.Slot> out = new ArrayList<>();
        for (BlockPos pos : positions) {
            Container c = ColonyStorage.container(level, pos);
            if (c != null) out.add(new ColonyStorage.Slot(pos, c));
        }
        return out;
    }

    private static int freeSlots(ServerLevel level, List<BlockPos> positions) {
        int free = 0;
        for (ColonyStorage.Slot slot : slots(level, positions)) {
            for (int i = 0; i < slot.container().getContainerSize(); i++) {
                if (slot.container().getItem(i).isEmpty()) free++;
            }
        }
        return free;
    }

    private static int occupied(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (!c.getItem(i).isEmpty()) n++;
        }
        return n;
    }
}
