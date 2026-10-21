package net.betterthanvillage.world;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.Household;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Ledger bookkeeping (docs_v2/08-simulation.md §Ledgers): capture of the real chests while a village is live, opening
 * the virtual chests when it goes abstract, and the gradual application of the differences when it loads again.
 * Every storage operation of {@link ColonyStorage} asks {@link #virtual} first, so live and abstract mode run the same
 * accounting code.
 */
public final class Ledgers {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Level -> chest position -> virtual chest of an open ledger. */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Container>> INDEX = new WeakHashMap<>();
    private static boolean indexDirty = true;

    private Ledgers() {
    }

    public static void clearCaches() {
        INDEX.clear();
        indexDirty = true;
    }

    public static Ledger ledger(ServerLevel level, Colony colony) {
        return ColonyManager.data(level).ledgers.computeIfAbsent(colony.id(), Ledger::new);
    }

    public static Ledger existing(ServerLevel level, Colony colony) {
        return ColonyManager.data(level).ledgers.get(colony.id());
    }

    public static boolean isOpen(ServerLevel level, Colony colony) {
        Ledger l = existing(level, colony);
        return l != null && l.open;
    }

    // ---------------------------------------------------------------- virtual chests

    /** The virtual chest standing for the real one at pos, or null when the real chest is the truth. */
    public static Container virtual(ServerLevel level, BlockPos pos) {
        if (indexDirty) rebuildIndex(level);
        Long2ObjectOpenHashMap<Container> index = INDEX.get(level);
        return index == null ? null : index.get(pos.asLong());
    }

    public static void markIndexDirty() {
        indexDirty = true;
    }

    private static void rebuildIndex(ServerLevel level) {
        indexDirty = false;
        INDEX.clear();
        for (ServerLevel l : level.getServer().getAllLevels()) {
            ColonySavedData data = ColonyManager.data(l);
            Long2ObjectOpenHashMap<Container> index = new Long2ObjectOpenHashMap<>();
            for (Ledger ledger : data.ledgers.values()) {
                if (!ledger.open) continue;
                for (Ledger.Chest c : ledger.chests.values()) {
                    if (c.current != null) index.put(c.pos.asLong(), c.current);
                }
            }
            if (!index.isEmpty()) INDEX.put(l, index);
        }
    }

    /** The real container at pos, never a virtual one (null when unloaded or gone). */
    static Container real(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    // ---------------------------------------------------------------- live: capture

    /** Positions the ledger covers: the treasury and every home chest. */
    private static Set<BlockPos> positions(Colony colony) {
        Set<BlockPos> out = new LinkedHashSet<>(colony.storages);
        for (Household h : colony.households.values()) out.addAll(h.chests);
        return out;
    }

    /**
     * Live village: the base of every loaded chest is refreshed from the real chest. Chests no longer used are
     * dropped; unloaded ones keep their last base.
     */
    public static void capture(ServerLevel level, Colony colony) {
        Ledger l = ledger(level, colony);
        if (l.open) return;
        Set<BlockPos> wanted = positions(colony);
        l.chests.keySet().removeIf(p -> !wanted.contains(p));
        for (BlockPos pos : wanted) {
            Container c = real(level, pos);
            if (c == null) continue;
            Ledger.Chest e = l.chests.get(pos);
            if (e == null) l.chest(pos, Ledger.copy(c));
            else e.base = Ledger.copy(c);
        }
        if (!colony.storages.isEmpty()) {
            l.treasury.clear();
            l.treasury.addAll(colony.storages);
        }
    }

    // ---------------------------------------------------------------- abstract: open

    /**
     * The village goes abstract: the chests still loaded are captured once more, then every chest gets its virtual
     * copy. Returns false if the ledger has nothing to stand for (the village was never captured).
     */
    public static boolean open(ServerLevel level, Colony colony) {
        Ledger l = ledger(level, colony);
        if (l.open) return true;
        capture(level, colony);
        if (l.chests.isEmpty()) return false;
        for (Ledger.Chest c : l.chests.values()) {
            c.current = l.virtual(c.base);
        }
        l.open = true;
        l.openedAt = level.getGameTime();
        // After a restart the scan's treasury list is empty until the village is live again.
        if (colony.storages.isEmpty()) colony.storages.addAll(l.treasury);
        markIndexDirty();
        ColonyManager.markDirty(level);
        LOGGER.info("[world] {} goes abstract: ledger of {} chests", name(colony), l.chests.size());
        return true;
    }

    // ---------------------------------------------------------------- live again: apply

    /**
     * Applies the ledger's differences to the loaded real chests, at most budget chests per call; the chests whose
     * chunk is not loaded stay virtual. Returns the number of chests applied. Closes the ledger when done.
     */
    public static int apply(ServerLevel level, Colony colony, int budget) {
        Ledger l = existing(level, colony);
        if (l == null || !l.open) return 0;
        int applied = 0, pending = 0;
        int added = 0, removed = 0, missing = 0;
        for (Ledger.Chest c : l.chests.values()) {
            if (c.current == null) continue;
            if (applied >= budget || !level.isLoaded(c.pos)) {
                pending++;
                continue;
            }
            Container real = real(level, c.pos);
            int[] r = applyDelta(level, c.pos, real, c.base, c.current);
            added += r[0];
            removed += r[1];
            missing += r[2];
            c.base = real != null ? Ledger.copy(real) : NonNullList.withSize(c.base.size(), ItemStack.EMPTY);
            c.current = null;
            applied++;
        }
        if (applied > 0) {
            markIndexDirty();
            ColonyManager.markDirty(level);
            LOGGER.info("[world] {} materializes: {} chests applied ({} items added, {} removed, {} missing), {} pending",
                    name(colony), applied, added, removed, missing, pending);
        }
        if (pending == 0) {
            l.open = false;
            markIndexDirty();
            ColonyManager.markDirty(level);
        }
        return applied;
    }

    /**
     * Puts current - base into the real chest: what was taken is removed (as far as it is still there), what was added
     * is inserted (dropped at the chest when it does not fit or the chest is gone). Returns {added, removed, missing}.
     */
    static int[] applyDelta(ServerLevel level, BlockPos pos, Container real, NonNullList<ItemStack> base, Container current) {
        List<ItemStack> kinds = new ArrayList<>();
        List<int[]> deltas = new ArrayList<>();
        for (ItemStack s : base) count(kinds, deltas, s, -s.getCount());
        for (int i = 0; i < current.getContainerSize(); i++) {
            ItemStack s = current.getItem(i);
            count(kinds, deltas, s, s.getCount());
        }
        int added = 0, removed = 0, missing = 0;
        for (int k = 0; k < kinds.size(); k++) {
            int d = deltas.get(k)[0];
            ItemStack kind = kinds.get(k);
            if (d < 0) {
                int left = -d;
                if (real != null) {
                    for (int i = 0; i < real.getContainerSize() && left > 0; i++) {
                        ItemStack s = real.getItem(i);
                        if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, kind)) continue;
                        int n = Math.min(left, s.getCount());
                        s.shrink(n);
                        left -= n;
                    }
                    real.setChanged();
                }
                removed += -d - left;
                missing += left;
            } else if (d > 0) {
                added += d;
                int left = d;
                while (left > 0) {
                    int n = Math.min(left, kind.getMaxStackSize());
                    ItemStack stack = kind.copyWithCount(n);
                    if (real != null) ColonyStorage.insert(real, stack);
                    if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, stack);
                    left -= n;
                }
            }
        }
        return new int[]{added, removed, missing};
    }

    private static void count(List<ItemStack> kinds, List<int[]> deltas, ItemStack s, int n) {
        if (s.isEmpty() || n == 0) return;
        for (int k = 0; k < kinds.size(); k++) {
            if (ItemStack.isSameItemSameComponents(kinds.get(k), s)) {
                deltas.get(k)[0] += n;
                return;
            }
        }
        kinds.add(s.copyWithCount(1));
        deltas.add(new int[]{n});
    }

    // ---------------------------------------------------------------- status

    static String name(Colony colony) {
        return "village (" + colony.core().getX() + ", " + colony.core().getZ() + ")";
    }

    /** Debug line: the ledger state of the village. */
    public static String summary(ServerLevel level, Colony colony) {
        Ledger l = existing(level, colony);
        if (l == null) return "no ledger";
        int virtual = 0;
        for (Ledger.Chest c : l.chests.values()) if (c.current != null) virtual++;
        return (l.open ? "abstract since " + l.openedAt + ", " + virtual + " virtual chests" : "live") + ", " + l.chests.size()
                + " chests, treasury " + l.treasury.size() + ", version " + l.version;
    }
}
