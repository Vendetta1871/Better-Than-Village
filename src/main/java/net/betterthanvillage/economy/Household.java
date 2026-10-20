package net.betterthanvillage.economy;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One adult villager's household (docs_v2/03-economy.md §Households): the bed it owns, its home chests and its
 * personality. Goods and money are the real contents of the home chests.
 */
public final class Household {
    public final UUID owner;
    /** The owned bed (vanilla HOME), or null while homeless. */
    public BlockPos bed;
    /** Home chests; the first one receives new goods. */
    public final List<BlockPos> chests = new ArrayList<>();
    /** Greed trait, roughly -0.3..0.3 (03 §Prices). */
    public float greed;
    /** Starting emeralds still to be put into the first home chest. */
    public int pendingCapital;
    /** The starting kit (tools and food of the trade) is still to be put into the first home chest. */
    public boolean pendingKit;
    public long founded;

    public Household(UUID owner) {
        this.owner = owner;
    }

    public boolean hasChest() {
        return !chests.isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "owner", owner);
        if (bed != null) {
            tag.putLong("bed", bed.asLong());
        }
        tag.putLongArray("chests", NbtHelper.packPositions(chests));
        tag.putFloat("greed", greed);
        tag.putInt("pending_capital", pendingCapital);
        tag.putBoolean("pending_kit", pendingKit);
        tag.putLong("founded", founded);
        return tag;
    }

    public static Household load(CompoundTag tag) {
        UUID owner = NbtHelper.uuid(tag, "owner");
        if (owner == null) {
            return null;
        }
        Household h = new Household(owner);
        h.bed = tag.contains("bed") ? BlockPos.of(tag.getLong("bed")) : null;
        h.chests.addAll(NbtHelper.unpackPositions(tag.getLongArray("chests")));
        float greed = tag.getFloat("greed");
        h.greed = Float.isFinite(greed) ? Math.max(-0.5f, Math.min(0.5f, greed)) : 0f;
        h.pendingCapital = NbtHelper.clamp(tag.getInt("pending_capital"), 0, 4096);
        h.pendingKit = tag.getBoolean("pending_kit");
        h.founded = tag.getLong("founded");
        return h;
    }
}
