package net.betterthanvillage.world;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ledger of a village (docs_v2/08-simulation.md §Ledgers): for every treasury and home chest, the contents it had
 * when the village went abstract ("base") and, while the village is abstract, a virtual chest that every storage
 * operation uses instead of the real one. When the village loads again the difference between the two is applied to
 * the real chest, so the real chests keep whatever happened to them meanwhile and nothing is created or destroyed.
 */
public final class Ledger {
    public final UUID colony;
    /** Chest position -> entry (treasury and home chests). */
    public final Map<BlockPos, Chest> chests = new LinkedHashMap<>();
    /** The treasury's chests when the ledger was last captured (the scan's list is runtime only). */
    public final List<BlockPos> treasury = new ArrayList<>();
    /** The village is abstract: virtual chests stand for the real ones until applied. */
    public boolean open;
    public long openedAt;
    /** Bumped whenever a virtual chest changes (status). */
    public int version;

    public Ledger(UUID colony) {
        this.colony = colony;
    }

    /** One chest of the ledger. */
    public final class Chest {
        public final BlockPos pos;
        /** Contents at the last capture (live) or when the ledger opened. */
        public NonNullList<ItemStack> base;
        /** The virtual chest while the ledger is open and this chest is not applied yet; else null. */
        public Virtual current;

        Chest(BlockPos pos, NonNullList<ItemStack> base) {
            this.pos = pos;
            this.base = base;
        }
    }

    /** A virtual chest: a plain container whose changes bump the ledger's version. */
    public final class Virtual extends SimpleContainer {
        Virtual(int size) {
            super(size);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            version++;
        }
    }

    Virtual virtual(NonNullList<ItemStack> contents) {
        Virtual v = new Virtual(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            v.setItem(i, contents.get(i).copy());
        }
        return v;
    }

    Chest chest(BlockPos pos, NonNullList<ItemStack> base) {
        Chest c = new Chest(pos.immutable(), base);
        chests.put(c.pos, c);
        return c;
    }

    static NonNullList<ItemStack> copy(net.minecraft.world.Container container) {
        NonNullList<ItemStack> out = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < out.size(); i++) {
            out.set(i, container.getItem(i).copy());
        }
        return out;
    }

    // ---------------------------------------------------------------- persistence

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "colony", colony);
        tag.putBoolean("open", open);
        tag.putLong("opened_at", openedAt);
        tag.putLongArray("treasury", NbtHelper.packPositions(treasury));
        ListTag list = new ListTag();
        for (Chest c : chests.values()) {
            CompoundTag e = new CompoundTag();
            e.putLong("pos", c.pos.asLong());
            e.putInt("size", c.base.size());
            e.put("base", ContainerHelper.saveAllItems(new CompoundTag(), c.base, registries));
            if (c.current != null) {
                NonNullList<ItemStack> cur = copy(c.current);
                e.put("current", ContainerHelper.saveAllItems(new CompoundTag(), cur, registries));
            }
            list.add(e);
        }
        tag.put("chests", list);
        return tag;
    }

    public static Ledger load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID colony = NbtHelper.uuid(tag, "colony");
        if (colony == null) return null;
        Ledger l = new Ledger(colony);
        l.open = tag.getBoolean("open");
        l.openedAt = tag.getLong("opened_at");
        l.treasury.addAll(NbtHelper.unpackPositions(tag.getLongArray("treasury")));
        ListTag list = tag.getList("chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int size = NbtHelper.clamp(e.getInt("size"), 1, 108);
            NonNullList<ItemStack> base = NonNullList.withSize(size, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(e.getCompound("base"), base, registries);
            Chest c = l.chest(BlockPos.of(e.getLong("pos")), base);
            if (e.contains("current")) {
                NonNullList<ItemStack> cur = NonNullList.withSize(size, ItemStack.EMPTY);
                ContainerHelper.loadAllItems(e.getCompound("current"), cur, registries);
                c.current = l.virtual(cur);
            }
        }
        return l;
    }
}
