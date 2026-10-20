package net.betterthanvillage.economy;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

/**
 * One debt (docs_v2/03-economy.md §Credit and debt notes). The record on the server is the truth; the signed
 * written book in the creditor's chest is only the note that shows it.
 */
public final class Debt {
    public enum Kind {
        HOUSEHOLD, TREASURY, PLAYER;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byKey(String key) {
            for (Kind k : values()) {
                if (k.key().equals(key)) return k;
            }
            return null;
        }
    }

    /** A side of a debt: a household (villager id), a village treasury (colony id) or a player. */
    public record Party(Kind kind, UUID id, UUID colony) {
        public static Party household(UUID villager, UUID colony) {
            return new Party(Kind.HOUSEHOLD, villager, colony);
        }

        public static Party treasury(UUID colony) {
            return new Party(Kind.TREASURY, colony, colony);
        }

        public static Party player(UUID player) {
            return new Party(Kind.PLAYER, player, null);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("kind", kind.key());
            NbtHelper.putUuid(tag, "id", id);
            NbtHelper.putUuid(tag, "colony", colony);
            return tag;
        }

        static Party load(CompoundTag tag) {
            Kind kind = Kind.byKey(tag.getString("kind"));
            UUID id = NbtHelper.uuid(tag, "id");
            return kind == null || id == null ? null : new Party(kind, id, NbtHelper.uuid(tag, "colony"));
        }
    }

    public final UUID id;
    public final Party creditor;
    public final Party debtor;
    /** Emeralds lent in total. */
    public int principal;
    /** Emeralds still owed, interest included. */
    public double balance;
    /** Interest per in-game day. */
    public double rate;
    public long createdDay;
    public long dueDay;
    /** Last day interest was added. */
    public long lastAccrualDay;
    /** Default steps taken so far (gossip, confiscation, conscription). */
    public int strikes;
    /** Neither the debtor nor its village could pay a player's note: the player may take the debtor's goods. */
    public boolean seizable;
    public String debtorName = "";
    public String creditorName = "";

    public Debt(UUID id, Party creditor, Party debtor) {
        this.id = id;
        this.creditor = creditor;
        this.debtor = debtor;
    }

    /** Whole emeralds owed (rounded up). */
    public int owed() {
        return (int) Math.ceil(balance - 1e-6);
    }

    public boolean overdue(long day) {
        return day > dueDay && owed() > 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "id", id);
        tag.put("creditor", creditor.save());
        tag.put("debtor", debtor.save());
        tag.putInt("principal", principal);
        tag.putDouble("balance", balance);
        tag.putDouble("rate", rate);
        tag.putLong("created_day", createdDay);
        tag.putLong("due_day", dueDay);
        tag.putLong("last_accrual_day", lastAccrualDay);
        tag.putInt("strikes", strikes);
        tag.putString("debtor_name", debtorName);
        tag.putBoolean("seizable", seizable);
        tag.putString("creditor_name", creditorName);
        return tag;
    }

    public static Debt load(CompoundTag tag) {
        UUID id = NbtHelper.uuid(tag, "id");
        Party creditor = Party.load(tag.getCompound("creditor"));
        Party debtor = Party.load(tag.getCompound("debtor"));
        if (id == null || creditor == null || debtor == null) return null;
        Debt d = new Debt(id, creditor, debtor);
        d.principal = Math.max(0, tag.getInt("principal"));
        double balance = tag.getDouble("balance");
        d.balance = Double.isFinite(balance) ? Math.max(0, Math.min(1_000_000, balance)) : 0;
        double rate = tag.getDouble("rate");
        d.rate = Double.isFinite(rate) ? Math.max(0, Math.min(1, rate)) : 0.01;
        d.createdDay = tag.getLong("created_day");
        d.dueDay = tag.getLong("due_day");
        d.lastAccrualDay = tag.contains("last_accrual_day") ? tag.getLong("last_accrual_day") : d.createdDay;
        d.strikes = NbtHelper.clamp(tag.getInt("strikes"), 0, 100);
        d.debtorName = tag.getString("debtor_name");
        d.seizable = tag.getBoolean("seizable");
        d.creditorName = tag.getString("creditor_name");
        return d;
    }
}
