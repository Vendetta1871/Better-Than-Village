package net.betterthanvillage.politics;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * A country (docs_v2/06-politics.md §Countries): its capital and the ruler's state. The villages of a country are the
 * colonies whose {@code country} is this id; the record is created when the country is first needed.
 */
public final class Country {
    public final UUID id;
    /** The capital village; the ruler is its elder. */
    public UUID capital;
    public long founded;
    /** The ruler's patience with disobedient villages, 0..100 (docs_v2/06-politics.md §Loyalty and rebellion). */
    public double patience = 100;
    /** Temporary higher tax: share of each village's treasury emeralds sent to the capital, until this tick. */
    public double taxRate;
    public long taxUntil;
    /** In-game day of the last daily politics step. */
    public long lastDay = Long.MIN_VALUE;

    public Country(UUID id) {
        this.id = id;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "id", id);
        NbtHelper.putUuid(tag, "capital", capital);
        tag.putLong("founded", founded);
        tag.putDouble("patience", patience);
        tag.putDouble("tax_rate", taxRate);
        tag.putLong("tax_until", taxUntil);
        tag.putLong("last_day", lastDay);
        return tag;
    }

    public static Country load(CompoundTag tag) {
        UUID id = NbtHelper.uuid(tag, "id");
        if (id == null) return null;
        Country c = new Country(id);
        c.capital = NbtHelper.uuid(tag, "capital");
        c.founded = tag.getLong("founded");
        double patience = tag.getDouble("patience");
        c.patience = Double.isFinite(patience) ? Math.max(0, Math.min(100, patience)) : 100;
        double rate = tag.getDouble("tax_rate");
        c.taxRate = Double.isFinite(rate) ? Math.max(0, Math.min(0.5, rate)) : 0;
        c.taxUntil = tag.getLong("tax_until");
        c.lastDay = tag.contains("last_day") ? tag.getLong("last_day") : Long.MIN_VALUE;
        return c;
    }
}
