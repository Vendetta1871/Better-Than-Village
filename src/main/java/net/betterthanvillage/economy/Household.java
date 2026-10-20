package net.betterthanvillage.economy;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Emeralds earned since the last tax collection. */
    public int income;
    /** In-game day of the last sale (the trade still pays). */
    public long lastIncomeDay;
    /** Consecutive days without the daily food. */
    public int hungryDays;
    /** Money tax due (fractional); the whole part is collected in the evening. */
    public double taxDue;
    /** Levy in kind accumulated per item id (fractional). */
    public final Map<String, Double> levyCredit = new HashMap<>();
    /** Wages the treasury could not pay yet. */
    public int wagesOwed;
    /** Public work done for the treasury since the last pay. */
    public int labor;
    /** Average daily income (smoothed), for the credit limit. */
    public double incomeAvg;
    /** Still in default after the elder's confiscation: first to be conscripted. */
    public boolean inDefault;
    /** Idle and the treasury cannot afford to conscript it (02 §When the army is full). */
    public boolean idlePoor;

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
        tag.putInt("income", income);
        tag.putLong("last_income_day", lastIncomeDay);
        tag.putInt("hungry_days", hungryDays);
        tag.putDouble("tax_due", taxDue);
        CompoundTag levy = new CompoundTag();
        levyCredit.forEach(levy::putDouble);
        tag.put("levy_credit", levy);
        tag.putInt("wages_owed", wagesOwed);
        tag.putInt("labor", labor);
        tag.putDouble("income_avg", incomeAvg);
        tag.putBoolean("in_default", inDefault);
        tag.putBoolean("idle_poor", idlePoor);
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
        h.income = Math.max(0, tag.getInt("income"));
        h.lastIncomeDay = tag.getLong("last_income_day");
        h.hungryDays = NbtHelper.clamp(tag.getInt("hungry_days"), 0, 1000);
        double due = tag.getDouble("tax_due");
        h.taxDue = Double.isFinite(due) ? Math.max(0, Math.min(100_000, due)) : 0;
        CompoundTag levy = tag.getCompound("levy_credit");
        for (String key : levy.getAllKeys()) {
            double v = levy.getDouble(key);
            if (Double.isFinite(v) && v > 0) h.levyCredit.put(key, Math.min(64, v));
        }
        h.wagesOwed = NbtHelper.clamp(tag.getInt("wages_owed"), 0, 100_000);
        h.labor = NbtHelper.clamp(tag.getInt("labor"), 0, 1_000_000);
        double avg = tag.getDouble("income_avg");
        h.incomeAvg = Double.isFinite(avg) ? Math.max(0, Math.min(10_000, avg)) : 0;
        h.inDefault = tag.getBoolean("in_default");
        h.idlePoor = tag.getBoolean("idle_poor");
        return h;
    }
}
