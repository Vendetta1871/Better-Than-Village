package net.betterthanvillage.warfare;

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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A campaign (docs_v2/07-warfare.md §Campaigns): the army of a country at war, from the call to arms to its return.
 * While no player is near it is a position that marches, builds its outpost and fights rounds of the strength model;
 * near a player its soldiers walk and fight as real villagers. Stored in ColonySavedData.
 */
public final class Campaign {
    public enum Kind {
        /** Against a village of another country. */
        WAR,
        /** Against the base of a player at war with the country. */
        PLAYER,
        /** A night operation: one agent against a village or a player's base (docs_v2/07-warfare.md §Night operations). */
        NIGHT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byKey(String key) {
            for (Kind k : values()) {
                if (k.key().equals(key)) return k;
            }
            return WAR;
        }
    }

    public enum Phase {
        /** Warriors gather at the staging village. */
        MOBILIZE,
        /** The army travels to the enemy domain. */
        MARCH,
        /** The outpost is built near the target; no attack before it stands. */
        OUTPOST,
        /** Siege or storm of the target. */
        SIEGE,
        /** The army goes back to its staging village. */
        RETURN,
        /** Back home: soldiers wait for their villages to load to walk there again. */
        HOME;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Phase byKey(String key) {
            for (Phase p : values()) {
                if (p.key().equals(key)) return p;
            }
            return MOBILIZE;
        }
    }

    /** One member of the army: a warrior (or the village golem) of a village of the country. */
    public static final class Soldier {
        public final UUID id;
        public final UUID home;
        public final boolean golem;
        public WarriorClass cls = WarriorClass.INFANTRY;
        /** Gear part of the soldier's power (0 bare-handed .. 3 diamond). */
        public double gear;
        /** Health share 0..1 (abstract wounds). */
        public double hp = 1;
        /** Entity data while the soldier is out of the loaded world; null while it never left it. */
        public CompoundTag data;
        /** Walks as a stand-in while its original is still in its unloaded home chunk. */
        public boolean standIn;
        /** Tick the soldier reaches the army (soldiers from other villages walk to the staging village first). */
        public long joinAt;
        /** Runtime: the soldier is a real entity. */
        public transient boolean live;
        /** Runtime: progress check of a live soldier (tick, distance left). */
        public transient long progressTick;
        public transient double progressDist = Double.MAX_VALUE;

        public Soldier(UUID id, UUID home, boolean golem) {
            this.id = id;
            this.home = home;
            this.golem = golem;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            NbtHelper.putUuid(tag, "id", id);
            NbtHelper.putUuid(tag, "home", home);
            tag.putBoolean("golem", golem);
            tag.putString("class", cls.key());
            tag.putDouble("gear", gear);
            tag.putDouble("hp", hp);
            if (data != null) tag.put("data", data);
            tag.putBoolean("stand_in", standIn);
            tag.putLong("join_at", joinAt);
            return tag;
        }

        static Soldier load(CompoundTag tag) {
            UUID id = NbtHelper.uuid(tag, "id"), home = NbtHelper.uuid(tag, "home");
            if (id == null || home == null) return null;
            Soldier s = new Soldier(id, home, tag.getBoolean("golem"));
            s.cls = WarriorClass.byKey(tag.getString("class"));
            s.gear = Math.max(0, Math.min(4, tag.getDouble("gear")));
            double hp = tag.getDouble("hp");
            s.hp = Double.isFinite(hp) ? Math.max(0, Math.min(1, hp)) : 1;
            if (tag.contains("data")) s.data = tag.getCompound("data");
            s.standIn = tag.getBoolean("stand_in");
            s.joinAt = tag.getLong("join_at");
            return s;
        }
    }

    public static final int LOOT_SLOTS = 27;

    public final UUID id;
    public final Kind kind;
    public final UUID attacker;
    /** Defending country (null for a campaign against a player). */
    public final UUID defender;
    /** Target village (WAR). */
    public UUID target;
    /** Target player and the base the army storms (PLAYER). */
    public UUID player;
    public BlockPos base;
    /** Staging village of the attacker, where the army gathers and returns. */
    public UUID origin;
    public Phase phase = Phase.MOBILIZE;
    public long started;
    public long phaseSince;
    /** Abstract position of the army (block coordinates). */
    public double x;
    public double z;
    public BlockPos outpost;
    /** Labour done on the outpost. */
    public int outpostWork;
    public boolean outpostBuilt;
    /** Blocks of the outpost placed in the world. */
    public boolean outpostPlaced;
    public int outpostLosses;
    public final List<Soldier> soldiers = new ArrayList<>();
    /** Power of the army when it marched (for its morale). */
    public double initialPower;
    public int lossesAttacker;
    public int lossesDefender;
    /** Integrity of the outermost intact ring of the target (1 whole, 0 breached). */
    public double ring = 1;
    /** Rings of the target breached so far. */
    public int ringsBroken;
    /** Wounds of the target's warriors in abstract rounds (warrior id -> health share). */
    public final Map<UUID, Double> defenderHp = new LinkedHashMap<>();
    /** Night operation: theft, arson or killing. */
    public NightOps.Op op;
    /** The night operation's agent was spotted: the target's people attack it. */
    public boolean spotted;
    /** The night operation is carried out (or failed); the agent goes home. */
    public boolean opDone;
    /** Goods taken from a player's base or by a thief. */
    public final SimpleContainer loot = new SimpleContainer(LOOT_SLOTS);
    /** Runtime: the enemy the army fights now (entity id) and when a soldier last struck it. */
    public transient int focus = -1;
    public transient long focusTick;
    /** Runtime: some soldier walks as a real entity. */
    public transient boolean live;

    public Campaign(UUID id, Kind kind, UUID attacker, UUID defender) {
        this.id = id;
        this.kind = kind;
        this.attacker = attacker;
        this.defender = defender;
    }

    public Soldier soldier(UUID id) {
        for (Soldier s : soldiers) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    /** Soldiers that have reached the army (not still walking to the staging village). */
    public List<Soldier> present(long now) {
        List<Soldier> out = new ArrayList<>();
        for (Soldier s : soldiers) {
            if (now >= s.joinAt) out.add(s);
        }
        return out;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "id", id);
        tag.putString("kind", kind.key());
        NbtHelper.putUuid(tag, "attacker", attacker);
        NbtHelper.putUuid(tag, "defender", defender);
        NbtHelper.putUuid(tag, "target", target);
        NbtHelper.putUuid(tag, "player", player);
        if (base != null) tag.putLong("base", base.asLong());
        NbtHelper.putUuid(tag, "origin", origin);
        tag.putString("phase", phase.key());
        tag.putLong("started", started);
        tag.putLong("phase_since", phaseSince);
        tag.putDouble("x", x);
        tag.putDouble("z", z);
        if (outpost != null) tag.putLong("outpost", outpost.asLong());
        tag.putInt("outpost_work", outpostWork);
        tag.putBoolean("outpost_built", outpostBuilt);
        tag.putBoolean("outpost_placed", outpostPlaced);
        tag.putInt("outpost_losses", outpostLosses);
        ListTag list = new ListTag();
        soldiers.forEach(s -> list.add(s.save()));
        tag.put("soldiers", list);
        tag.putDouble("initial_power", initialPower);
        tag.putInt("losses_attacker", lossesAttacker);
        tag.putInt("losses_defender", lossesDefender);
        tag.putDouble("ring", ring);
        tag.putInt("rings_broken", ringsBroken);
        if (op != null) tag.putString("op", op.key());
        tag.putBoolean("spotted", spotted);
        tag.putBoolean("op_done", opDone);
        CompoundTag hp = new CompoundTag();
        defenderHp.forEach((k, v) -> hp.putDouble(k.toString(), v));
        tag.put("defender_hp", hp);
        NonNullList<ItemStack> items = NonNullList.withSize(LOOT_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < LOOT_SLOTS; i++) items.set(i, loot.getItem(i));
        tag.put("loot", ContainerHelper.saveAllItems(new CompoundTag(), items, registries));
        return tag;
    }

    public static Campaign load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID id = NbtHelper.uuid(tag, "id"), attacker = NbtHelper.uuid(tag, "attacker");
        if (id == null || attacker == null) return null;
        Campaign c = new Campaign(id, Kind.byKey(tag.getString("kind")), attacker, NbtHelper.uuid(tag, "defender"));
        c.target = NbtHelper.uuid(tag, "target");
        c.player = NbtHelper.uuid(tag, "player");
        if (tag.contains("base")) c.base = BlockPos.of(tag.getLong("base"));
        c.origin = NbtHelper.uuid(tag, "origin");
        c.phase = Phase.byKey(tag.getString("phase"));
        c.started = tag.getLong("started");
        c.phaseSince = tag.getLong("phase_since");
        c.x = tag.getDouble("x");
        c.z = tag.getDouble("z");
        if (tag.contains("outpost")) c.outpost = BlockPos.of(tag.getLong("outpost"));
        c.outpostWork = Math.max(0, tag.getInt("outpost_work"));
        c.outpostBuilt = tag.getBoolean("outpost_built");
        c.outpostPlaced = tag.getBoolean("outpost_placed");
        c.outpostLosses = Math.max(0, tag.getInt("outpost_losses"));
        ListTag list = tag.getList("soldiers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < 256; i++) {
            Soldier s = Soldier.load(list.getCompound(i));
            if (s != null) c.soldiers.add(s);
        }
        c.initialPower = Math.max(0, tag.getDouble("initial_power"));
        c.lossesAttacker = Math.max(0, tag.getInt("losses_attacker"));
        c.lossesDefender = Math.max(0, tag.getInt("losses_defender"));
        c.ring = tag.contains("ring") ? Math.max(0, Math.min(1, tag.getDouble("ring"))) : 1;
        c.ringsBroken = Math.max(0, tag.getInt("rings_broken"));
        c.op = tag.contains("op") ? NightOps.Op.byKey(tag.getString("op")) : null;
        c.spotted = tag.getBoolean("spotted");
        c.opDone = tag.getBoolean("op_done");
        CompoundTag hp = tag.getCompound("defender_hp");
        for (String key : hp.getAllKeys()) {
            try {
                c.defenderHp.put(UUID.fromString(key), Math.max(0, Math.min(1, hp.getDouble(key))));
            } catch (IllegalArgumentException ignored) {
            }
        }
        NonNullList<ItemStack> items = NonNullList.withSize(LOOT_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag.getCompound("loot"), items, registries);
        for (int i = 0; i < LOOT_SLOTS; i++) c.loot.setItem(i, items.get(i));
        return c;
    }
}
