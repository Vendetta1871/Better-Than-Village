package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Credit between households, the treasury and (later) the player (docs_v2/03-economy.md §Credit and debt notes):
 * limits, signed notes, daily interest, repayment at gatherings and the default steps.
 */
public final class Credit {
    /** Custom-data key holding the debt id of a note. */
    public static final String NOTE_KEY = "better_than_village_debt";
    public static final String NOTE_TITLE = "Debt note";

    private Credit() {
    }

    private static ColonyConfig.Economy cfg() {
        return ColonyConfig.get().economy;
    }

    public static long day(ServerLevel level) {
        return Math.floorDiv(level.getDayTime(), 24000L);
    }

    // ---------------------------------------------------------------- queries

    /** Whole emeralds this party owes in total. */
    public static int owedBy(ServerLevel level, Debt.Party debtor) {
        int total = 0;
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (d.debtor.equals(debtor)) total += d.owed();
        }
        return total;
    }

    public static boolean hasDefaulted(ServerLevel level, Debt.Party debtor) {
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (d.debtor.equals(debtor) && d.strikes > 0) return true;
        }
        return false;
    }

    /** Debts owed by the village: its households and its treasury. */
    public static int villageDebt(ServerLevel level, Colony colony) {
        int total = 0;
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (colony.id().equals(d.debtor.colony()) && d.debtor.kind() != Debt.Kind.PLAYER) total += d.owed();
        }
        return total;
    }

    /** Emeralds the village holds: treasury and home chests. */
    public static int villageEmeralds(ServerLevel level, Colony colony) {
        return ColonyStorage.count(ColonyStorage.villageStock(level, colony), s -> s.is(Items.EMERALD));
    }

    /**
     * Credit a household may still take: a base plus a few days of its average income plus a quarter of its goods'
     * value, less what it owes. Nobody lends to a household that has defaulted, and the whole village owes at
     * most twice the emeralds it holds.
     */
    public static int available(ServerLevel level, Colony colony, Household h, int goodsValue) {
        Debt.Party me = Debt.Party.household(h.owner, colony.id());
        if (hasDefaulted(level, me)) return 0;
        int limit = cfg().credit_base + (int) Math.round(h.incomeAvg * cfg().credit_income_days) + goodsValue / 4;
        int own = limit - owedBy(level, me);
        int cap = (int) Math.round(villageEmeralds(level, colony) * cfg().village_debt_cap) - villageDebt(level, colony);
        return Math.max(0, Math.min(own, cap));
    }

    // ---------------------------------------------------------------- lending

    /**
     * Records credit of amount emeralds from creditor to debtor. An open note between the same two parties is
     * extended; otherwise a new note is put into the creditor's chests. Returns null if the note does not fit.
     */
    public static Debt lend(ServerLevel level, Debt.Party creditor, Debt.Party debtor, int amount, List<ColonyStorage.Slot> creditorSlots,
                            String creditorName, String debtorName) {
        if (amount <= 0) return null;
        ColonySavedData data = ColonyManager.data(level);
        long today = day(level);
        for (Debt d : data.debts.values()) {
            if (d.creditor.equals(creditor) && d.debtor.equals(debtor) && !d.overdue(today)) {
                d.principal += amount;
                d.balance += amount;
                d.dueDay = Math.max(d.dueDay, today + cfg().debt_term_days);
                refreshNote(creditorSlots, d);
                data.setDirty();
                return d;
            }
        }
        Debt d = new Debt(UUID.randomUUID(), creditor, debtor);
        d.principal = amount;
        d.balance = amount;
        d.rate = cfg().interest_per_day;
        d.createdDay = today;
        d.lastAccrualDay = today;
        d.dueDay = today + cfg().debt_term_days;
        d.creditorName = creditorName;
        d.debtorName = debtorName;
        if (creditorSlots != null) {
            List<ItemStack> rest = Chests.put(creditorSlots, List.of(note(d)));
            if (!rest.isEmpty()) return null;
        }
        data.debts.put(d.id, d);
        data.setDirty();
        return d;
    }

    /** Records a debt whose note already exists (a note the player took as payment). */
    public static Debt register(ServerLevel level, UUID id, Debt.Party creditor, Debt.Party debtor, int amount, String creditorName, String debtorName) {
        long today = day(level);
        Debt d = new Debt(id, creditor, debtor);
        d.principal = amount;
        d.balance = amount;
        d.rate = cfg().interest_per_day;
        d.createdDay = today;
        d.lastAccrualDay = today;
        d.dueDay = today + cfg().debt_term_days;
        d.creditorName = creditorName;
        d.debtorName = debtorName;
        ColonyManager.data(level).debts.put(id, d);
        ColonyManager.data(level).setDirty();
        return d;
    }

    // ---------------------------------------------------------------- notes

    /** The signed note: a vanilla written book authored by the debtor (generation 0 is the original). */
    public static ItemStack note(Debt d) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(Component.translatable("book.better_than_village.debt.page",
                d.debtorName, d.creditorName, d.owed(), String.format("%.1f", d.rate * 100), d.dueDay)));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(NOTE_TITLE),
                d.debtorName.isEmpty() ? "?" : d.debtorName, 0, pages, true));
        CompoundTag tag = new CompoundTag();
        tag.putString(NOTE_KEY, d.id.toString());
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return book;
    }

    /** Debt id of a note, or null. */
    public static UUID noteId(ItemStack stack) {
        if (!stack.is(Items.WRITTEN_BOOK)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(NOTE_KEY)) return null;
        try {
            return UUID.fromString(data.copyTag().getString(NOTE_KEY));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Only the original book (generation 0) counts as a bearer note. */
    public static boolean isOriginal(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return content != null && content.generation() == 0;
    }

    /** Rewrites the note of a debt found in these chests (amount or due day changed); removes it when paid. */
    static void refreshNote(List<ColonyStorage.Slot> slots, Debt d) {
        if (slots == null) return;
        for (ColonyStorage.Slot slot : slots) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!d.id.equals(noteId(s))) continue;
                c.setItem(i, d.owed() > 0 ? note(d) : ItemStack.EMPTY);
                c.setChanged();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- daily steps

    /** Morning: interest on every open debt of the village, once per day. */
    public static void accrue(ServerLevel level, Colony colony) {
        long today = day(level);
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (!colony.id().equals(d.debtor.colony()) && !colony.id().equals(d.creditor.colony())) continue;
            if (d.lastAccrualDay >= today) continue;
            long days = Math.min(30, today - d.lastAccrualDay);
            d.balance *= Math.pow(1 + d.rate, days);
            d.lastAccrualDay = today;
        }
        ColonyManager.data(level).setDirty();
    }

    /**
     * At a gathering, before trading: the household pays the debts that are due, as far as its emeralds go.
     * Paid emeralds go to the creditor's chests; a paid-off note is taken out of them and the debt ends.
     */
    public static int repayDue(ServerLevel level, Colony colony, Debt.Party debtor, List<ColonyStorage.Slot> debtorSlots) {
        ColonySavedData data = ColonyManager.data(level);
        long today = day(level);
        int paidTotal = 0;
        for (Debt d : new ArrayList<>(data.debts.values())) {
            if (!d.debtor.equals(debtor) || d.dueDay > today) continue;
            List<ColonyStorage.Slot> to = slotsOf(level, d.creditor);
            if (to == null) continue;
            int paid = Treasury.move(debtorSlots, to, d.owed());
            if (paid <= 0) continue;
            paidTotal += paid;
            settle(level, d, paid, to);
        }
        return paidTotal;
    }

    /** Applies a payment to a debt; ends it (and removes its note from the creditor's chests) when paid off. */
    public static void settle(ServerLevel level, Debt d, double paid, List<ColonyStorage.Slot> creditorSlots) {
        d.balance = Math.max(0, d.balance - paid);
        if (d.owed() <= 0) {
            d.balance = 0;
            ColonyManager.data(level).debts.remove(d.id);
            net.betterthanvillage.law.Wars.debtPaid(level, d);
        }
        refreshNote(creditorSlots, d);
        ColonyManager.data(level).setDirty();
    }

    /** Chests of a household or treasury party (null for players or unknown parties). */
    static List<ColonyStorage.Slot> slotsOf(ServerLevel level, Debt.Party party) {
        Colony colony = ColonyManager.data(level).colony(party.colony());
        if (colony == null) return null;
        return switch (party.kind()) {
            case TREASURY -> ColonyStorage.treasury(level, colony);
            case HOUSEHOLD -> {
                Household h = colony.households.get(party.id());
                yield h == null ? null : ColonyStorage.usable(level, h);
            }
            case PLAYER -> null;
        };
    }

    /**
     * Morning: steps against overdue household debtors (03 §Default): first bad gossip, two days later the elder
     * confiscates goods at market value for the creditor; a debtor who stays in default is marked for conscription.
     */
    public static void defaults(ServerLevel level, Colony colony, java.util.Map<UUID, Villager> members) {
        long today = day(level);
        ColonySavedData data = ColonyManager.data(level);
        for (Debt d : new ArrayList<>(data.debts.values())) {
            if (d.debtor.kind() == Debt.Kind.PLAYER && colony.id().equals(d.creditor.colony()) && d.overdue(today)) {
                playerDefault(level, colony, members, d, today);
                continue;
            }
            if (d.debtor.kind() == Debt.Kind.TREASURY && colony.id().equals(d.debtor.colony()) && d.overdue(today)) {
                // A village owing another village (docs_v2/06-politics.md §Causes of war: unpaid debt).
                if (d.strikes == 0) {
                    d.strikes = 1;
                    net.betterthanvillage.politics.Diplomacy.debtDefault(level, d, 1);
                } else if (d.strikes == 1 && today > d.dueDay + 2) {
                    d.strikes = 2;
                    net.betterthanvillage.politics.Diplomacy.debtDefault(level, d, 2);
                }
                continue;
            }
            if (d.debtor.kind() != Debt.Kind.HOUSEHOLD || !colony.id().equals(d.debtor.colony()) || !d.overdue(today)) continue;
            Household h = colony.households.get(d.debtor.id());
            if (h == null) continue;
            if (d.strikes == 0) {
                d.strikes = 1;
                badGossip(colony, members, d);
            } else if (d.strikes == 1 && today > d.dueDay + 2) {
                d.strikes = 2;
                List<ColonyStorage.Slot> to = slotsOf(level, d.creditor);
                if (to != null) confiscate(level, colony, d, ColonyStorage.usable(level, h), to);
            } else if (d.strikes >= 2) {
                h.inDefault = true;
            }
        }
        data.setDirty();
    }

    /**
     * A player's overdue note: a warning and bad gossip first; a day later the default stands: no more credit, and the
     * creditor's country declares war until the debt is paid (docs_v2/05-law.md §Offences).
     */
    private static void playerDefault(ServerLevel level, Colony colony, java.util.Map<UUID, Villager> members, Debt d, long today) {
        var player = level.getPlayerByUUID(d.debtor.id());
        if (d.strikes == 0) {
            d.strikes = 1;
            badGossip(colony, members, d);
            if (player != null) player.displayClientMessage(Component.translatable("message.better_than_village.debt.overdue",
                    d.creditorName, d.owed()), false);
        } else if (d.strikes == 1 && today > d.dueDay + 1) {
            d.strikes = 2;
            badGossip(colony, members, d);
            if (player != null) player.displayClientMessage(Component.translatable("message.better_than_village.debt.default",
                    d.creditorName, d.owed()), false);
            net.betterthanvillage.law.Wars.declare(level, colony, d.debtor.id(), d.debtorName, net.betterthanvillage.law.Offence.DEBT);
        }
    }

    private static void badGossip(Colony colony, java.util.Map<UUID, Villager> members, Debt d) {
        Villager elder = colony.elder == null ? null : members.get(colony.elder);
        Villager creditor = d.creditor.kind() == Debt.Kind.HOUSEHOLD ? members.get(d.creditor.id()) : null;
        for (Villager v : new Villager[]{elder, creditor}) {
            if (v != null) v.getGossips().add(d.debtor.id(), GossipType.MINOR_NEGATIVE, 25);
        }
    }

    /** Takes tradable goods from the debtor at market value, most valuable first, up to what is owed. */
    static void confiscate(ServerLevel level, Colony colony, Debt d, List<ColonyStorage.Slot> from, List<ColonyStorage.Slot> to) {
        double value = 0;
        for (ColonyStorage.Slot slot : from) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize() && value < d.balance; i++) {
                ItemStack s = c.getItem(i);
                Values.Family f = Values.family(s);
                if (f == null || s.is(Items.EMERALD)) continue;
                double unit = Values.base(s) * Market.index(colony, f);
                if (unit <= 0) continue;
                int n = Math.min(s.getCount(), (int) Math.ceil((d.balance - value) / unit));
                ItemStack part = s.copyWithCount(n);
                List<ItemStack> rest = Chests.put(to, List.of(part));
                int moved = n - Chests.total(rest);
                if (moved <= 0) continue;
                s.shrink(moved);
                c.setChanged();
                value += moved * unit;
            }
        }
        if (value > 0) settle(level, d, value, slotsOf(level, d.creditor));
    }

    /** A readable name for a debt party (book author, creditor line). */
    public static String name(ServerLevel level, Colony colony, Debt.Party party) {
        return switch (party.kind()) {
            case TREASURY -> "Treasury";
            case PLAYER -> {
                var p = level.getServer().getPlayerList().getPlayer(party.id());
                yield p != null ? p.getGameProfile().getName() : party.id().toString().substring(0, 8);
            }
            case HOUSEHOLD -> {
                Entity e = level.getEntity(party.id());
                ColonyRole role = colony == null ? ColonyRole.CITIZEN : colony.tradeOf(party.id());
                String base = e != null && e.hasCustomName() && !e.getTags().contains(net.betterthanvillage.colony.Professions.ROLE_NAME_TAG)
                        ? e.getCustomName().getString() : capitalize(role.serializedName());
                yield base + " " + party.id().toString().substring(0, 4);
            }
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
