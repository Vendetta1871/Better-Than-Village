package net.betterthanvillage.economy;

import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * A paid-for birth (docs_v2/03-economy.md §Births and housing): the parents (or the treasury) bought a plot and a
 * house with the workplace of the child's profession; the child is born once the house stands.
 */
public final class BirthOrder {
    public final UUID id;
    /** Parents; null for an order the treasury paid to keep the village alive. */
    public UUID parentA;
    public UUID parentB;
    public ColonyRole profession = ColonyRole.FARMER;
    public float greed;
    /** Emeralds paid per payer, for a refund if the house cannot be built. */
    public int paidA;
    public int paidB;
    public int paidTreasury;
    /** The house job once a site was found. */
    public UUID job;
    public long placedDay;
    public boolean workplaceDone;
    /** The child's generations in its trade (0: first of its line). */
    public int lineage;
    /** Masons' labour put into the house while the village was abstract. */
    public int abstractLabor;
    /** Game tick the house was finished in the abstract world (0: not yet); the child is born when the village loads. */
    public long builtAbstract;

    public BirthOrder(UUID id) {
        this.id = id;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "id", id);
        NbtHelper.putUuid(tag, "parent_a", parentA);
        NbtHelper.putUuid(tag, "parent_b", parentB);
        tag.putString("profession", profession.serializedName());
        tag.putFloat("greed", greed);
        tag.putInt("paid_a", paidA);
        tag.putInt("paid_b", paidB);
        tag.putInt("paid_treasury", paidTreasury);
        NbtHelper.putUuid(tag, "job", job);
        tag.putLong("placed_day", placedDay);
        tag.putBoolean("workplace_done", workplaceDone);
        tag.putInt("lineage", lineage);
        tag.putInt("abstract_labor", abstractLabor);
        tag.putLong("built_abstract", builtAbstract);
        return tag;
    }

    public static BirthOrder load(CompoundTag tag) {
        UUID id = NbtHelper.uuid(tag, "id");
        if (id == null) return null;
        BirthOrder o = new BirthOrder(id);
        o.parentA = NbtHelper.uuid(tag, "parent_a");
        o.parentB = NbtHelper.uuid(tag, "parent_b");
        ColonyRole role = ColonyRole.byName(tag.getString("profession"));
        o.profession = role.isProfession() ? role : ColonyRole.FARMER;
        float greed = tag.getFloat("greed");
        o.greed = Float.isFinite(greed) ? Math.max(-0.5f, Math.min(0.5f, greed)) : 0f;
        o.paidA = Math.max(0, tag.getInt("paid_a"));
        o.paidB = Math.max(0, tag.getInt("paid_b"));
        o.paidTreasury = Math.max(0, tag.getInt("paid_treasury"));
        o.job = NbtHelper.uuid(tag, "job");
        o.placedDay = tag.getLong("placed_day");
        o.workplaceDone = tag.getBoolean("workplace_done");
        o.lineage = NbtHelper.clamp(tag.getInt("lineage"), 0, 9);
        o.abstractLabor = Math.max(0, tag.getInt("abstract_labor"));
        o.builtAbstract = Math.max(0, tag.getLong("built_abstract"));
        return o;
    }
}
