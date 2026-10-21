package net.betterthanvillage.politics;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

/** An order of the ruler to a village of the country (docs_v2/06-politics.md §Orders). */
public final class Order {
    public enum Kind {
        /** Send resources from the village's treasury to another village, without payment. */
        REDISTRIBUTE,
        /** Send a share of the treasury's emeralds to the capital (temporary higher taxes). */
        TAX,
        /** Send warriors to a gathering point for a campaign (fought in phase 5). */
        MOBILIZE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byKey(String key) {
            for (Kind k : values()) {
                if (k.key().equals(key)) return k;
            }
            return null;
        }
    }

    public enum Status {
        ISSUED, OBEYED, IGNORED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Status byKey(String key) {
            for (Status s : values()) {
                if (s.key().equals(key)) return s;
            }
            return ISSUED;
        }
    }

    public final UUID id;
    public final Kind kind;
    public final UUID country;
    /** The village that must act. */
    public final UUID colony;
    /** Receiving village (redistribution, tax), or null. */
    public UUID target;
    /** Goods family (Values.Family name) for a redistribution, or "emerald". */
    public String goods = "";
    public int amount;
    public long issued;
    public Status status = Status.ISSUED;
    /** Value in emeralds the order took from the village or gave to it (loyalty). */
    public int value;

    public Order(UUID id, Kind kind, UUID country, UUID colony) {
        this.id = id;
        this.kind = kind;
        this.country = country;
        this.colony = colony;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "id", id);
        tag.putString("kind", kind.key());
        NbtHelper.putUuid(tag, "country", country);
        NbtHelper.putUuid(tag, "colony", colony);
        NbtHelper.putUuid(tag, "target", target);
        tag.putString("goods", goods);
        tag.putInt("amount", amount);
        tag.putLong("issued", issued);
        tag.putString("status", status.key());
        tag.putInt("value", value);
        return tag;
    }

    public static Order load(CompoundTag tag) {
        UUID id = NbtHelper.uuid(tag, "id"), country = NbtHelper.uuid(tag, "country"), colony = NbtHelper.uuid(tag, "colony");
        Kind kind = Kind.byKey(tag.getString("kind"));
        if (id == null || country == null || colony == null || kind == null) return null;
        Order o = new Order(id, kind, country, colony);
        o.target = NbtHelper.uuid(tag, "target");
        o.goods = tag.getString("goods");
        o.amount = NbtHelper.clamp(tag.getInt("amount"), 0, 100_000);
        o.issued = tag.getLong("issued");
        o.status = Status.byKey(tag.getString("status"));
        o.value = NbtHelper.clamp(tag.getInt("value"), 0, 1_000_000);
        return o;
    }
}
