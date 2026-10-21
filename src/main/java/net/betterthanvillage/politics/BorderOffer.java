package net.betterthanvillage.politics;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * An offer to buy border chunks for a search party's site (docs_v2/06-politics.md §Border changes), kept while the
 * buyer waits after a refusal: a border dispute and a possible cause of war.
 */
public final class BorderOffer {
    public UUID buyer;
    public UUID owner;
    /** The village whose search party wants the land. */
    public UUID colony;
    public BlockPos site;
    public long[] chunks = new long[0];
    public int price;
    public long made;
    /** Refused by the owner (false: the buyer could not pay). */
    public boolean refused;

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "buyer", buyer);
        NbtHelper.putUuid(tag, "owner", owner);
        NbtHelper.putUuid(tag, "colony", colony);
        if (site != null) tag.putLong("site", site.asLong());
        tag.putLongArray("chunks", chunks);
        tag.putInt("price", price);
        tag.putLong("made", made);
        tag.putBoolean("refused", refused);
        return tag;
    }

    public static BorderOffer load(CompoundTag tag) {
        BorderOffer o = new BorderOffer();
        o.buyer = NbtHelper.uuid(tag, "buyer");
        o.owner = NbtHelper.uuid(tag, "owner");
        if (o.buyer == null || o.owner == null) return null;
        o.colony = NbtHelper.uuid(tag, "colony");
        o.site = tag.contains("site") ? BlockPos.of(tag.getLong("site")) : null;
        o.chunks = tag.getLongArray("chunks");
        o.price = Math.max(0, tag.getInt("price"));
        o.made = tag.getLong("made");
        o.refused = tag.getBoolean("refused");
        return o;
    }
}
