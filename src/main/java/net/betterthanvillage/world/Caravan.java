package net.betterthanvillage.world;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A travelling group (docs_v2/08-simulation.md §Materialization): a caravan of a village's treasury, led by one of
 * its villagers. While no player is near it is a position moving along the straight route; near a player the leader
 * walks as a real villager. The cargo is a container, so the same chest operations move goods in and out of it.
 */
public final class Caravan {
    public enum Kind {
        /** The elder's trade with another village's treasury: sells surpluses, buys what is missing. */
        TRADE,
        /** A ruler's order: resources moved to another village of the country without payment. */
        REDISTRIBUTE,
        /** A ruler's order: a share of the treasury's emeralds taken to the capital. */
        TAX;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byKey(String key) {
            for (Kind k : values()) {
                if (k.key().equals(key)) return k;
            }
            return TRADE;
        }
    }

    public enum Phase {
        OUTBOUND, RETURNING,
        /** Back home while the village is abstract: the leader's entity is spawned when the village loads. */
        HOME;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Phase byKey(String key) {
            for (Phase p : values()) {
                if (p.key().equals(key)) return p;
            }
            return OUTBOUND;
        }
    }

    public static final int CARGO_SLOTS = 27;

    public final UUID id;
    public final Kind kind;
    public final UUID home;
    public final UUID target;
    public final UUID leader;
    /** The order this caravan carries out, or null. */
    public UUID order;
    public Phase phase = Phase.OUTBOUND;
    /** Abstract position (block coordinates); the leader's position while it is materialized. */
    public double x;
    public double z;
    public long departed;
    public long arrived = -1;
    /** The leader's entity data, captured when it left the loaded world; null while it never did. */
    public CompoundTag leaderData;
    /**
     * The leader walks as a stand-in while its original entity is still in its unloaded home chunk; cleared when the
     * original loads during the journey and is dropped.
     */
    public boolean standIn;
    /** Goods families (Values.Family names) and units the home treasury wants bought. */
    public final Map<String, Integer> wants = new LinkedHashMap<>();
    public final SimpleContainer cargo = new SimpleContainer(CARGO_SLOTS);
    /** Emeralds earned and spent at the destination (status). */
    public int sold;
    public int bought;
    /** Runtime: the leader walks as a real entity. */
    public transient boolean live;
    /** Runtime: progress check of a live leader (tick, distance left). */
    public transient long progressTick;
    public transient double progressDist = Double.MAX_VALUE;

    public Caravan(UUID id, Kind kind, UUID home, UUID target, UUID leader) {
        this.id = id;
        this.kind = kind;
        this.home = home;
        this.target = target;
        this.leader = leader;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "id", id);
        tag.putString("kind", kind.key());
        NbtHelper.putUuid(tag, "home", home);
        NbtHelper.putUuid(tag, "target", target);
        NbtHelper.putUuid(tag, "leader", leader);
        NbtHelper.putUuid(tag, "order", order);
        tag.putString("phase", phase.key());
        tag.putDouble("x", x);
        tag.putDouble("z", z);
        tag.putLong("departed", departed);
        tag.putLong("arrived", arrived);
        if (leaderData != null) tag.put("leader_data", leaderData);
        tag.putBoolean("stand_in", standIn);
        CompoundTag w = new CompoundTag();
        wants.forEach(w::putInt);
        tag.put("wants", w);
        NonNullList<ItemStack> items = NonNullList.withSize(CARGO_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < CARGO_SLOTS; i++) items.set(i, cargo.getItem(i));
        tag.put("cargo", ContainerHelper.saveAllItems(new CompoundTag(), items, registries));
        tag.putInt("sold", sold);
        tag.putInt("bought", bought);
        return tag;
    }

    public static Caravan load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID id = NbtHelper.uuid(tag, "id"), home = NbtHelper.uuid(tag, "home"), target = NbtHelper.uuid(tag, "target"),
                leader = NbtHelper.uuid(tag, "leader");
        if (id == null || home == null || target == null || leader == null) return null;
        Caravan c = new Caravan(id, Kind.byKey(tag.getString("kind")), home, target, leader);
        c.order = NbtHelper.uuid(tag, "order");
        c.phase = Phase.byKey(tag.getString("phase"));
        c.x = tag.getDouble("x");
        c.z = tag.getDouble("z");
        c.departed = tag.getLong("departed");
        c.arrived = tag.contains("arrived") ? tag.getLong("arrived") : -1;
        if (tag.contains("leader_data")) c.leaderData = tag.getCompound("leader_data");
        c.standIn = tag.getBoolean("stand_in");
        CompoundTag w = tag.getCompound("wants");
        for (String k : w.getAllKeys()) c.wants.put(k, NbtHelper.clamp(w.getInt(k), 0, 4096));
        NonNullList<ItemStack> items = NonNullList.withSize(CARGO_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag.getCompound("cargo"), items, registries);
        for (int i = 0; i < CARGO_SLOTS; i++) c.cargo.setItem(i, items.get(i));
        c.sold = Math.max(0, tag.getInt("sold"));
        c.bought = Math.max(0, tag.getInt("bought"));
        return c;
    }
}
