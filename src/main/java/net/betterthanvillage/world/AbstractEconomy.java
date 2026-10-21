package net.betterthanvillage.world;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Credit;
import net.betterthanvillage.economy.Debt;
import net.betterthanvillage.economy.Gathering;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Market;
import net.betterthanvillage.economy.Needs;
import net.betterthanvillage.economy.Skill;
import net.betterthanvillage.economy.Treasury;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The economy of an abstract village (docs_v2/08-simulation.md §Two modes): the same gatherings as a live village,
 * cleared on the ledger's virtual chests. Every household attends; in the morning it eats, is paid, works a day
 * ({@link Production}) and pays its debts. The order matching itself runs on a worker thread on copies of the chests;
 * the result is applied only if the chests did not change meanwhile, else the market is cleared again here.
 */
public final class AbstractEconomy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String IN_FLIGHT = "world.economyInFlight";
    /** At most this many missed gatherings are cleared after a long absence; older ones are skipped. */
    private static final int MAX_BACKLOG = 8;
    private static final String PLACEHOLDER = "better_than_village_placeholder";
    private static final AtomicInteger PLACEHOLDER_IDS = new AtomicInteger();

    private AbstractEconomy() {
    }

    /**
     * One scheduler step of an abstract village: clears the oldest gathering not cleared yet. Returns true while more
     * gatherings are waiting (the scheduler comes back soon).
     */
    public static boolean step(ServerLevel level, Colony colony) {
        if (!colony.householdsSettled || colony.households.isEmpty()) return false;
        if (ColonyManager.isLive(level, colony) || colony.runtime.containsKey(IN_FLIGHT)) return false;
        if (!Ledgers.open(level, colony)) return false;
        long closed = Gathering.lastClosed(level.getDayTime());
        if (closed <= colony.lastGathering) return false;
        boolean first = colony.lastGathering == Long.MIN_VALUE;
        long index = first ? closed : Math.max(colony.lastGathering + 1, closed - MAX_BACKLOG);
        colony.lastGathering = index;
        ColonyManager.markDirty(level);
        gathering(level, colony, index, first);
        return index < closed;
    }

    public static boolean inFlight(Colony colony) {
        return colony.runtime.containsKey(IN_FLIGHT);
    }

    // ---------------------------------------------------------------- one gathering

    private record Snapshot(List<Market.Party> parties, List<List<ColonyStorage.Slot>> live, List<List<NonNullList<ItemStack>>> before,
                            Map<String, Double> index, long seed) {
    }

    private record Loan(Market.Party seller, Market.Party buyer, int emeralds) {
    }

    private record Result(Market.Report report, Map<String, Double> index, List<Loan> loans) {
    }

    private static void gathering(ServerLevel level, Colony colony, long index, boolean first) {
        boolean morning = Math.floorMod(index, 2L) == 0;
        int produced = 0;
        if (morning && !first) {
            Gathering.dailyMeals(level, colony);
            Treasury.payWages(level, colony);
            Credit.accrue(level, colony);
            Credit.defaults(level, colony, Map.of());
            long day = Math.floorDiv(index, 2L);
            for (Household h : new ArrayList<>(colony.households.values())) {
                produced += Production.day(level, colony, h, day);
            }
        }
        int repaid = 0;
        for (Household h : colony.households.values()) {
            if (h.hasChest()) {
                repaid += Credit.repayDue(level, colony, Debt.Party.household(h.owner, colony.id()), ColonyStorage.usable(level, h));
            }
        }
        repaid += Credit.repayDue(level, colony, Debt.Party.treasury(colony.id()), ColonyStorage.treasury(level, colony));
        Snapshot snap = snapshot(level, colony);
        int finalRepaid = repaid;
        int finalProduced = produced;
        colony.runtime.put(IN_FLIGHT, Boolean.TRUE);
        SimWorkers.submit(() -> clear(snap), result -> {
            colony.runtime.remove(IN_FLIGHT);
            apply(level, colony, index, snap, result, finalRepaid, finalProduced);
        }, () -> colony.runtime.remove(IN_FLIGHT));
    }

    /** Copies of every party's chests: the worker clears the market on them. */
    private static Snapshot snapshot(ServerLevel level, Colony colony) {
        List<Market.Party> parties = new ArrayList<>();
        List<List<ColonyStorage.Slot>> live = new ArrayList<>();
        List<List<NonNullList<ItemStack>>> before = new ArrayList<>();
        int children = 0;
        for (UUID id : colony.roles.keySet()) {
            if (!colony.households.containsKey(id) && colony.tradeOf(id) == ColonyRole.CITIZEN) children++;
        }
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        parties.add(new Market.Party(null, ColonyRole.ELDER, copies(treasury, live, before), null, 0f, Needs.treasury(colony, children)));
        for (Household h : colony.households.values()) {
            if (!h.hasChest() || colony.isFounderParty(h.owner)) continue;
            List<ColonyStorage.Slot> slots = ColonyStorage.usable(level, h);
            if (slots.isEmpty()) continue;
            int credit = Credit.available(level, colony, h, Gathering.goodsValue(colony, slots));
            ColonyRole trade = colony.tradeOf(h.owner);
            parties.add(new Market.Party(h.owner, trade, copies(slots, live, before), null, h.greed, Needs.household(trade))
                    .withCredit(credit).withQuality(Skill.quality(colony, h.owner)));
        }
        return new Snapshot(parties, live, before, new HashMap<>(colony.marketIndex), level.random.nextLong());
    }

    private static List<ColonyStorage.Slot> copies(List<ColonyStorage.Slot> slots, List<List<ColonyStorage.Slot>> live,
                                                   List<List<NonNullList<ItemStack>>> before) {
        List<ColonyStorage.Slot> out = new ArrayList<>();
        List<NonNullList<ItemStack>> was = new ArrayList<>();
        for (ColonyStorage.Slot s : slots) {
            NonNullList<ItemStack> contents = Ledger.copy(s.container());
            SimpleContainer copy = new SimpleContainer(contents.size());
            for (int i = 0; i < contents.size(); i++) copy.setItem(i, contents.get(i).copy());
            out.add(new ColonyStorage.Slot(s.pos(), copy));
            was.add(contents);
        }
        live.add(slots);
        before.add(was);
        return out;
    }

    /** Worker thread: order matching on the copies. Loans reserve a slot for their note with a placeholder. */
    private static Result clear(Snapshot snap) {
        List<Loan> loans = new ArrayList<>();
        Market.Lender lender = (seller, buyer, emeralds) -> {
            ItemStack placeholder = placeholder();
            if (Chests.room(seller.slots(), placeholder) < 1) return false;
            Chests.put(seller.slots(), List.of(placeholder));
            loans.add(new Loan(seller, buyer, emeralds));
            return true;
        };
        Market.Report report = snap.parties().size() > 1
                ? Market.clear(snap.index(), snap.parties(), RandomSource.create(snap.seed()), lender) : new Market.Report();
        return new Result(report, snap.index(), loans);
    }

    private static ItemStack placeholder() {
        ItemStack s = new ItemStack(Items.BARRIER);
        CompoundTag tag = new CompoundTag();
        tag.putInt(PLACEHOLDER, PLACEHOLDER_IDS.incrementAndGet());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static boolean isPlaceholder(ItemStack s) {
        if (!s.is(Items.BARRIER)) return false;
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(PLACEHOLDER);
    }

    /** Server thread: validates and applies the cleared market, then the evening taxes. */
    private static void apply(ServerLevel level, Colony colony, long index, Snapshot snap, Result result, int repaid, int produced) {
        // The village was removed, or went live and its ledger was applied: the result is dropped.
        if (ColonyManager.data(level).colony(colony.id()) != colony || !Ledgers.isOpen(level, colony)) return;
        Market.Report report;
        if (unchanged(snap)) {
            for (int p = 0; p < snap.live().size(); p++) {
                List<ColonyStorage.Slot> live = snap.live().get(p);
                List<ColonyStorage.Slot> copy = snap.parties().get(p).slots();
                for (int k = 0; k < live.size(); k++) {
                    Container to = live.get(k).container();
                    Container from = copy.get(k).container();
                    for (int i = 0; i < to.getContainerSize(); i++) {
                        ItemStack s = from.getItem(i);
                        to.setItem(i, isPlaceholder(s) ? ItemStack.EMPTY : s.copy());
                    }
                    to.setChanged();
                }
            }
            for (Loan loan : result.loans()) {
                List<ColonyStorage.Slot> sellerSlots = snap.live().get(snap.parties().indexOf(loan.seller()));
                lend(level, colony, loan.seller().id(), loan.buyer().id(), loan.emeralds(), sellerSlots);
            }
            colony.marketIndex.clear();
            colony.marketIndex.putAll(result.index());
            report = result.report();
            incomes(level, colony, snap.parties());
        } else {
            // The chests changed while the worker computed (a caravan, a live visit): clear again on the real state.
            List<Market.Party> parties = snapshot(level, colony).parties();
            List<Market.Party> real = new ArrayList<>();
            for (int p = 0; p < parties.size(); p++) {
                Market.Party c = parties.get(p);
                Household h = c.id() == null ? null : colony.households.get(c.id());
                List<ColonyStorage.Slot> slots = h == null ? ColonyStorage.treasury(level, colony) : ColonyStorage.usable(level, h);
                ColonyRole trade = c.id() == null ? ColonyRole.ELDER : colony.tradeOf(c.id());
                Market.Party party = new Market.Party(c.id(), trade, slots, null, h == null ? 0f : h.greed,
                        h == null ? Needs.treasury(colony, 0) : Needs.household(trade));
                if (h != null) party.withCredit(Credit.available(level, colony, h, Gathering.goodsValue(colony, slots))).withQuality(Skill.quality(colony, h.owner));
                real.add(party);
            }
            Market.Lender lender = (seller, buyer, emeralds) -> lend(level, colony, seller.id(), buyer.id(), emeralds, seller.slots());
            report = real.size() > 1 ? Market.clear(colony.marketIndex, real, level.random, lender) : new Market.Report();
            incomes(level, colony, real);
            LOGGER.debug("[world] {}: chests changed during the market, cleared again", Ledgers.name(colony));
        }
        report.repaid = repaid;
        if (Math.floorMod(index, 2L) == 1) Treasury.collectTaxes(level, colony);
        Gathering.report(colony, index, snap.parties().size() - 1, report);
        ColonyStorage.recount(level, colony);
        ColonyManager.markDirty(level);
        if (Math.floorMod(index, 2L) == 0) {
            LOGGER.info("[world] {} abstract day {}: produced {}, trades {}, emeralds {}, credit {}, repaid {}", Ledgers.name(colony),
                    Math.floorDiv(index, 2L), produced, report.trades, report.emeralds, report.credit, repaid);
        }
    }

    private static boolean lend(ServerLevel level, Colony colony, UUID sellerId, UUID buyerId, int emeralds, List<ColonyStorage.Slot> sellerSlots) {
        Debt.Party creditor = sellerId == null ? Debt.Party.treasury(colony.id()) : Debt.Party.household(sellerId, colony.id());
        Debt.Party debtor = Debt.Party.household(buyerId, colony.id());
        return Credit.lend(level, creditor, debtor, emeralds, sellerSlots,
                Credit.name(level, colony, creditor), Credit.name(level, colony, debtor)) != null;
    }

    private static void incomes(ServerLevel level, Colony colony, List<Market.Party> parties) {
        long today = Credit.day(level);
        for (Market.Party p : parties) {
            Household h = p.id() == null ? null : colony.households.get(p.id());
            if (h == null) continue;
            h.income += p.earned();
            if (p.earned() > 0) h.lastIncomeDay = today;
        }
    }

    /** The live chests still hold what the snapshot copied. */
    private static boolean unchanged(Snapshot snap) {
        for (int p = 0; p < snap.live().size(); p++) {
            List<ColonyStorage.Slot> live = snap.live().get(p);
            List<NonNullList<ItemStack>> was = snap.before().get(p);
            if (live.size() != was.size()) return false;
            for (int k = 0; k < live.size(); k++) {
                Container c = live.get(k).container();
                NonNullList<ItemStack> w = was.get(k);
                if (c.getContainerSize() != w.size()) return false;
                for (int i = 0; i < w.size(); i++) {
                    if (!ItemStack.matches(c.getItem(i), w.get(i))) return false;
                }
            }
        }
        return true;
    }

    /** Debug: emeralds per household and in the treasury, as the (virtual or real) chests hold them. */
    public static Map<String, Integer> emeralds(ServerLevel level, Colony colony) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("treasury", Chests.count(ColonyStorage.treasury(level, colony), s -> s.is(Items.EMERALD)));
        int households = 0;
        for (Household h : colony.households.values()) households += Chests.count(ColonyStorage.usable(level, h), s -> s.is(Items.EMERALD));
        out.put("households", households);
        return out;
    }
}
