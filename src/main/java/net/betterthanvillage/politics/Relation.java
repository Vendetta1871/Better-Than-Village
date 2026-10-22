package net.betterthanvillage.politics;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Relations between two countries (docs_v2/06-politics.md §Diplomacy): a score fed by trade, debts and incidents,
 * and the diplomatic state. Stored once per pair, the smaller id first.
 */
public final class Relation {
    public enum State {
        ALLIANCE, PEACE, HOSTILITY, WAR;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static State byKey(String key) {
            for (State s : values()) {
                if (s.key().equals(key)) return s;
            }
            return PEACE;
        }
    }

    /** Why a war was declared (06-politics.md §Causes of war). */
    public enum Cause {
        ALLY, ATTACKED, RESOURCES, TERRITORY, BORDER, DEBT, INCIDENTS, REBELLION;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Cause byKey(String key) {
            for (Cause c : values()) {
                if (c.key().equals(key)) return c;
            }
            return null;
        }
    }

    /** One side's account of the current war (docs_v2/07-warfare.md §War weariness). */
    public static final class Side {
        /** Warriors the side had when the war began. */
        public int warriors;
        public int losses;
        public int villagesLost;
        /** Hours its villages spent besieged. */
        public int besiegedHours;
        /** Emerald value the war cost it. */
        public int spent;
        public double weariness;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("warriors", warriors);
            tag.putInt("losses", losses);
            tag.putInt("villages_lost", villagesLost);
            tag.putInt("besieged_hours", besiegedHours);
            tag.putInt("spent", spent);
            tag.putDouble("weariness", weariness);
            return tag;
        }

        void load(CompoundTag tag) {
            warriors = Math.max(0, tag.getInt("warriors"));
            losses = Math.max(0, tag.getInt("losses"));
            villagesLost = Math.max(0, tag.getInt("villages_lost"));
            besiegedHours = Math.max(0, tag.getInt("besieged_hours"));
            spent = Math.max(0, tag.getInt("spent"));
            double w = tag.getDouble("weariness");
            weariness = Double.isFinite(w) ? Math.max(0, w) : 0;
        }

        public void reset() {
            warriors = losses = villagesLost = besiegedHours = spent = 0;
            weariness = 0;
        }
    }

    public final UUID a;
    public final UUID b;
    /** War accounts of a and b (reset when a war begins). */
    public final Side sideA = new Side();
    public final Side sideB = new Side();
    /** -100 (hatred) .. 100 (friendship). */
    public double score;
    public State state = State.PEACE;
    /** Tick the current state began. */
    public long since;
    /** Cause of the current war, or null. */
    public Cause cause;
    /** The country that declared the current war. */
    public UUID aggressor;
    /** In-game days of the recent caravan trades between the two (steady trade, alliances). */
    public final List<Long> tradeDays = new ArrayList<>();
    /** No war may be declared before this tick (truce of the last peace). */
    public long truceUntil;

    public Relation(UUID a, UUID b) {
        this.a = a;
        this.b = b;
    }

    public UUID other(UUID country) {
        return country.equals(a) ? b : a;
    }

    public boolean involves(UUID country) {
        return a.equals(country) || b.equals(country);
    }

    public Side side(UUID country) {
        return country.equals(a) ? sideA : sideB;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "a", a);
        NbtHelper.putUuid(tag, "b", b);
        tag.putDouble("score", score);
        tag.putString("state", state.key());
        tag.putLong("since", since);
        if (cause != null) tag.putString("cause", cause.key());
        NbtHelper.putUuid(tag, "aggressor", aggressor);
        tag.putLongArray("trade_days", tradeDays.stream().mapToLong(Long::longValue).toArray());
        tag.putLong("truce_until", truceUntil);
        tag.put("side_a", sideA.save());
        tag.put("side_b", sideB.save());
        return tag;
    }

    public static Relation load(CompoundTag tag) {
        UUID a = NbtHelper.uuid(tag, "a"), b = NbtHelper.uuid(tag, "b");
        if (a == null || b == null || a.equals(b)) return null;
        Relation r = new Relation(a, b);
        double score = tag.getDouble("score");
        r.score = Double.isFinite(score) ? Math.max(-100, Math.min(100, score)) : 0;
        r.state = State.byKey(tag.getString("state"));
        r.since = tag.getLong("since");
        r.cause = tag.contains("cause") ? Cause.byKey(tag.getString("cause")) : null;
        r.aggressor = NbtHelper.uuid(tag, "aggressor");
        for (long d : tag.getLongArray("trade_days")) {
            if (r.tradeDays.size() < 32) r.tradeDays.add(d);
        }
        r.truceUntil = tag.getLong("truce_until");
        r.sideA.load(tag.getCompound("side_a"));
        r.sideB.load(tag.getCompound("side_b"));
        return r;
    }
}
