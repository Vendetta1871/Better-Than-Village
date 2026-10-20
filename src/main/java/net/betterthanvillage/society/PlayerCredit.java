package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Credit;
import net.betterthanvillage.economy.Debt;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Market;
import net.betterthanvillage.economy.Values;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Credit with the player through the vanilla trade screen (docs_v2/03-economy.md §Credit with the player): the
 * player signs for goods with a book and quill, pays debts back, lends by taking a villager's note as payment and
 * collects notes from the debtor or, for a defaulted villager, from the elder as guarantor.
 */
final class PlayerCredit {
    /** At most this many sign-for-it offers per villager. */
    private static final int MAX_CREDIT_OFFERS = 3;

    private PlayerCredit() {
    }

    /** Credit the player may still take from this village: base plus reputation, nothing after a default. */
    static int available(ServerLevel level, Villager v, Player player) {
        Debt.Party me = Debt.Party.player(player.getUUID());
        if (Credit.hasDefaulted(level, me)) return 0;
        int rep = Math.max(0, v.getPlayerReputation(player));
        int limit = ColonyConfig.get().economy.credit_base + rep / 10;
        return Math.max(0, limit - Credit.owedBy(level, me));
    }

    private static long count(Map<MerchantOffer, Trading.Desc> descs, Trading.Kind kind) {
        return descs.values().stream().filter(d -> d.kind() == kind).count();
    }

    /** A sell offer the player cannot pay for may be signed for instead (cost: a book and quill). */
    static void creditSellOffer(ServerLevel level, Colony colony, Household h, Player player, Item item, int batch, int price,
                                MerchantOffers offers, Map<MerchantOffer, Trading.Desc> descs) {
        if (player == null || count(descs, Trading.Kind.SELL_ON_CREDIT) >= MAX_CREDIT_OFFERS) return;
        if (player.getInventory().countItem(Items.EMERALD) >= price) return;
        Villager v = level.getEntity(h.owner) instanceof Villager vv ? vv : null;
        if (v == null || available(level, v, player) < price) return;
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.WRITABLE_BOOK, 1), new ItemStack(item, batch), 1, 1, Trading.COLONY_MULTIPLIER);
        offers.add(offer);
        descs.put(offer, new Trading.Desc(colony.id(), h.owner, Trading.Kind.SELL_ON_CREDIT, item, batch, price, null));
    }

    /** A household without cash pays the player for goods with its own debt note (generation 0). */
    static void noteBuyOffer(ServerLevel level, Colony colony, Household h, Player player, Item item, int batch, int price,
                             MerchantOffers offers, Map<MerchantOffer, Trading.Desc> descs) {
        if (player == null || count(descs, Trading.Kind.PAY_WITH_NOTE) >= 2) return;
        if (Credit.available(level, colony, h, 0) < price) return;
        Debt preview = new Debt(UUID.randomUUID(), Debt.Party.player(player.getUUID()), Debt.Party.household(h.owner, colony.id()));
        preview.principal = price;
        preview.balance = price;
        preview.rate = ColonyConfig.get().economy.interest_per_day;
        preview.dueDay = Credit.day(level) + ColonyConfig.get().economy.debt_term_days;
        preview.debtorName = Credit.name(level, colony, preview.debtor);
        preview.creditorName = player.getGameProfile().getName();
        MerchantOffer offer = new MerchantOffer(new ItemCost(item, batch), Credit.note(preview), 1, 1, Trading.COLONY_MULTIPLIER);
        offers.add(offer);
        descs.put(offer, new Trading.Desc(colony.id(), h.owner, Trading.Kind.PAY_WITH_NOTE, item, batch, price, preview.id));
    }

    /** Repaying the player's debts to this villager (or, at the elder, to anyone in the village) and collecting notes. */
    static void debtOffers(ServerLevel level, Colony colony, Villager v, ColonyRole role, Household h, Player player,
                           MerchantOffers offers, Map<MerchantOffer, Trading.Desc> descs) {
        if (player == null) return;
        boolean elder = role == ColonyRole.ELDER;
        Debt.Party me = Debt.Party.player(player.getUUID());
        long today = Credit.day(level);
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (offers.size() >= 20) return;
            if (!d.debtor.equals(me) || !colony.id().equals(d.creditor.colony())) continue;
            boolean mine = h != null && d.creditor.kind() == Debt.Kind.HOUSEHOLD && d.creditor.id().equals(h.owner);
            if (!mine && !elder) continue;
            int pay = Math.min(64, d.owed());
            if (pay <= 0) continue;
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, pay), Credit.note(d), 1, 0, Trading.COLONY_MULTIPLIER);
            offers.add(offer);
            UUID owner = d.creditor.kind() == Debt.Kind.HOUSEHOLD ? d.creditor.id() : null;
            descs.put(offer, new Trading.Desc(colony.id(), owner, Trading.Kind.REPAY, Items.EMERALD, pay, pay, d.id));
        }
        for (ItemStack stack : player.getInventory().items) {
            if (offers.size() >= 20) return;
            UUID id = Credit.noteId(stack);
            if (id == null || !Credit.isOriginal(stack)) continue;
            Debt d = ColonyManager.data(level).debts.get(id);
            if (d == null || !d.creditor.equals(me) || !colony.id().equals(d.debtor.colony())) continue;
            boolean mine = h != null && d.debtor.kind() == Debt.Kind.HOUSEHOLD && d.debtor.id().equals(h.owner);
            // The village guarantees its villagers' debts; the elder pays defaulted notes and the treasury's own.
            boolean guarantor = elder && (d.debtor.kind() == Debt.Kind.TREASURY || d.overdue(today));
            if (!mine && !guarantor) continue;
            UUID payer = mine ? h.owner : null;
            List<ColonyStorage.Slot> slots = payer == null ? ColonyStorage.treasury(level, colony) : ColonyStorage.usable(level, h);
            int pay = Math.min(64, d.owed());
            if (Chests.count(slots, s -> s.is(Items.EMERALD)) < pay) {
                // Nobody can pay: the player may now take the debtor's goods (05 §Offences: not theft).
                if (guarantor && d.debtor.kind() == Debt.Kind.HOUSEHOLD && !d.seizable) {
                    d.seizable = true;
                    ColonyManager.data(level).setDirty();
                }
                continue;
            }
            DataComponentPredicate exact = DataComponentPredicate.builder().expect(DataComponents.CUSTOM_DATA, stack.get(DataComponents.CUSTOM_DATA)).build();
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.WRITTEN_BOOK.builtInRegistryHolder(), 1, exact),
                    new ItemStack(Items.EMERALD, pay), 1, 0, Trading.COLONY_MULTIPLIER);
            offers.add(offer);
            descs.put(offer, new Trading.Desc(colony.id(), payer, Trading.Kind.COLLECT, Items.EMERALD, pay, pay, d.id));
        }
    }

    static boolean validate(ServerLevel level, Colony colony, Trading.Desc d, List<ColonyStorage.Slot> slots) {
        Debt debt = d.debt() == null ? null : ColonyManager.data(level).debts.get(d.debt());
        return switch (d.kind()) {
            case SELL_ON_CREDIT -> Chests.count(slots, s -> Trading.plain(s, d.item())) >= d.count()
                    && Chests.room(slots, new ItemStack(Items.WRITTEN_BOOK)) >= 1;
            case REPAY -> debt != null && Chests.room(slots, new ItemStack(Items.EMERALD)) >= d.price();
            case PAY_WITH_NOTE -> Chests.room(slots, new ItemStack(d.item())) >= d.count();
            case COLLECT -> debt != null && Chests.count(slots, s -> s.is(Items.EMERALD)) >= d.price();
            default -> false;
        };
    }

    /** Chest side of a completed credit trade. */
    static void settle(ServerLevel level, Colony colony, Villager v, Trading.Desc d, List<ColonyStorage.Slot> slots, Household h) {
        Player player = v.getTradingPlayer();
        Debt debt = d.debt() == null ? null : ColonyManager.data(level).debts.get(d.debt());
        switch (d.kind()) {
            case SELL_ON_CREDIT -> {
                if (player == null || h == null) return;
                List<ItemStack> goods = Chests.take(slots, s -> Trading.plain(s, d.item()), d.count());
                Debt.Party creditor = Debt.Party.household(h.owner, colony.id());
                Debt.Party debtor = Debt.Party.player(player.getUUID());
                Credit.lend(level, creditor, debtor, d.price(), slots, Credit.name(level, colony, creditor), player.getGameProfile().getName());
                if (Chests.total(goods) > 0) h.income += d.price();
            }
            case REPAY -> {
                if (debt == null) return;
                Chests.put(slots, List.of(new ItemStack(Items.EMERALD, d.price())));
                Credit.settle(level, debt, d.price(), slots);
            }
            case PAY_WITH_NOTE -> {
                if (player == null || h == null) return;
                Chests.put(slots, List.of(new ItemStack(d.item(), d.count())));
                Debt.Party creditor = Debt.Party.player(player.getUUID());
                Debt.Party debtor = Debt.Party.household(h.owner, colony.id());
                Credit.register(level, d.debt(), creditor, debtor, d.price(), player.getGameProfile().getName(), Credit.name(level, colony, debtor));
            }
            case COLLECT -> {
                if (debt == null) return;
                List<ItemStack> money = Chests.take(slots, s -> s.is(Items.EMERALD), d.price());
                // The note is handed in: the debt ends (anything above 64 emeralds is written off).
                Credit.settle(level, debt, debt.balance, null);
                if (Chests.total(money) < d.price()) Chests.put(slots, money);
            }
            default -> {
            }
        }
        ColonyManager.markDirty(level);
    }

    /** Value of goods a creditor may take from a defaulted debtor's chest without it being theft (0 if none). */
    static double seizable(ServerLevel level, Colony colony, UUID household, UUID player) {
        double total = 0;
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (d.seizable && d.creditor.equals(Debt.Party.player(player)) && d.debtor.equals(Debt.Party.household(household, colony.id()))) {
                total += d.balance;
            }
        }
        return total;
    }

    /** Writes the value of seized goods off the player's notes on this household. */
    static void seize(ServerLevel level, Colony colony, UUID household, UUID player, double value) {
        for (Debt d : new java.util.ArrayList<>(ColonyManager.data(level).debts.values())) {
            if (value <= 0) break;
            if (!d.seizable || !d.creditor.equals(Debt.Party.player(player)) || !d.debtor.equals(Debt.Party.household(household, colony.id()))) continue;
            double part = Math.min(value, d.balance);
            Credit.settle(level, d, part, null);
            value -= part;
        }
    }

    /** Market value of goods at this village's prices. */
    static double value(Colony colony, ItemStack stack, int count) {
        Values.Family f = Values.family(stack);
        return f == null ? 0 : Values.base(stack) * Market.index(colony, f) * count;
    }
}
