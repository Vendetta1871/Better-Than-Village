package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.Values.Family;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order matching of one gathering (docs_v2/03-economy.md §Gatherings): every party posts asks (goods above its
 * reserve) and bids (families below it); bids are served most urgent first from the cheapest acceptable asks,
 * each lot at the midpoint of ask and bid, in whole emeralds. Goods and emeralds move chest to chest.
 */
public final class Market {
    /** Smoothing of the market index between gatherings (weight of the new ratio). */
    private static final double INDEX_WEIGHT = 0.2;
    /** One gathering moves the index by at most this factor either way. */
    private static final double RATIO_LIMIT = 2.0;
    public static final double INDEX_MIN = 0.25;
    public static final double INDEX_MAX = 4.0;

    private Market() {
    }

    /** One trading party: a household or the treasury. */
    public static final class Party {
        final UUID id;
        final ColonyRole role;
        final List<ColonyStorage.Slot> slots;
        /** The villager that trades for the party (gossip, reputation); null for an absent elder. */
        final Villager villager;
        final float greed;
        /** Price factor of the party's goods (first-generation craftsmen sell for less). */
        double quality = 1.0;
        final EnumMap<Family, Integer> reserve;
        int cash;
        /** Emeralds the party may still buy on credit at this gathering. */
        int creditLeft;
        int earned;
        int spent;
        int borrowed;

        public Party(UUID id, ColonyRole role, List<ColonyStorage.Slot> slots, Villager villager, float greed, EnumMap<Family, Integer> reserve) {
            this.id = id;
            this.role = role;
            this.slots = slots;
            this.villager = villager;
            this.greed = greed;
            this.reserve = reserve;
            this.cash = Chests.count(slots, s -> s.is(Items.EMERALD));
        }

        boolean treasury() {
            return id == null;
        }

        public Party withQuality(double quality) {
            this.quality = quality;
            return this;
        }

        public Party withCredit(int credit) {
            this.creditLeft = Math.max(0, credit);
            return this;
        }

        public UUID id() {
            return id;
        }

        public int earned() {
            return earned;
        }

        public int borrowed() {
            return borrowed;
        }

        public List<ColonyStorage.Slot> slots() {
            return slots;
        }
    }

    /** Records credit from seller to buyer; false if it cannot (no room for the note). */
    @FunctionalInterface
    public interface Lender {
        boolean lend(Party seller, Party buyer, int emeralds);
    }

    private static final class Ask {
        final Party seller;
        final Item item;
        final Family family;
        final int units;
        final double price;
        int qty;

        Ask(Party seller, Item item, Family family, int units, double price, int qty) {
            this.seller = seller;
            this.item = item;
            this.family = family;
            this.units = units;
            this.price = price;
            this.qty = qty;
        }
    }

    private record Bid(Party buyer, Family family, int units, double urgency) {
    }

    /** Outcome of a gathering, for the status line. */
    public static final class Report {
        public int trades;
        public int emeralds;
        public int unmetBids;
        public int credit;
        public int repaid;
        public final Map<Family, Integer> demand = new EnumMap<>(Family.class);
        public final Map<Family, Integer> supply = new EnumMap<>(Family.class);
    }

    /** Plain tradable stacks only: the same item without damage, enchantments or names. */
    private static boolean plain(ItemStack s, Item item) {
        return s.is(item) && Values.family(s) != null;
    }

    public static double index(Colony colony, Family family) {
        return colony.marketIndex.getOrDefault(family.name(), 1.0);
    }

    private static double idx(Map<String, Double> index, Family family) {
        return index.getOrDefault(family.name(), 1.0);
    }

    /**
     * Runs the gathering among the parties and updates the market index (the colony's map, or a copy when the
     * gathering is cleared on a worker thread).
     */
    public static Report clear(Map<String, Double> index, List<Party> parties, RandomSource random, Lender lender) {
        Report report = new Report();
        List<Ask> asks = new ArrayList<>();
        List<Bid> bids = new ArrayList<>();
        for (Party p : parties) {
            collect(index, p, asks, bids, report);
        }
        // Most urgent first; ties in random order so nobody is always served last.
        List<Bid> order = new ArrayList<>(bids);
        java.util.Collections.shuffle(order, new java.util.Random(random.nextLong()));
        order.sort(Comparator.comparingDouble((Bid b) -> -b.urgency()));
        for (Bid bid : order) {
            int left = serve(index, bid, asks, report, lender);
            if (left > 0) report.unmetBids++;
        }
        updateIndex(index, report);
        return report;
    }

    /** Holdings of a party per family and the asks above its reserve (most valuable items offered first). */
    private static void collect(Map<String, Double> index, Party p, List<Ask> asks, List<Bid> bids, Report report) {
        Map<Item, Integer> items = new LinkedHashMap<>();
        Map<Item, ItemStack> samples = new LinkedHashMap<>();
        for (ColonyStorage.Slot slot : p.slots) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.is(Items.EMERALD) || Values.family(s) == null) continue;
                items.merge(s.getItem(), s.getCount(), Integer::sum);
                samples.putIfAbsent(s.getItem(), s);
            }
        }
        EnumMap<Family, Integer> held = new EnumMap<>(Family.class);
        Map<Family, List<Item>> byFamily = new EnumMap<>(Family.class);
        items.forEach((item, n) -> {
            ItemStack sample = samples.get(item);
            Family f = Values.family(sample);
            held.merge(f, n * Values.units(sample, f), Integer::sum);
            byFamily.computeIfAbsent(f, k -> new ArrayList<>()).add(item);
        });
        for (Map.Entry<Family, Integer> e : p.reserve.entrySet()) {
            int have = held.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) {
                double urgency = p.treasury() ? Needs.NORMAL * 0.7 : Needs.urgency(e.getKey(), have);
                bids.add(new Bid(p, e.getKey(), e.getValue() - have, urgency));
                report.demand.merge(e.getKey(), e.getValue() - have, Integer::sum);
            }
        }
        for (Map.Entry<Family, List<Item>> e : byFamily.entrySet()) {
            Family f = e.getKey();
            int sellable = held.get(f) - p.reserve.getOrDefault(f, 0);
            if (sellable <= 0) continue;
            List<Item> list = e.getValue();
            // Keep the cheapest per unit, offer the most valuable first.
            list.sort(Comparator.comparingDouble((Item it) -> -Values.base(samples.get(it)) / Values.units(samples.get(it), f)));
            for (Item item : list) {
                if (sellable <= 0) break;
                ItemStack sample = samples.get(item);
                int units = Values.units(sample, f);
                int qty = Math.min(items.get(item), sellable / units);
                if (qty <= 0) continue;
                double price = Values.base(sample) * idx(index, f) * (1 + p.greed) * p.quality;
                asks.add(new Ask(p, item, f, units, price, qty));
                sellable -= qty * units;
                report.supply.merge(f, qty * units, Integer::sum);
            }
        }
    }

    /** Serves one bid from the cheapest acceptable asks; returns the units still wanted. */
    private static int serve(Map<String, Double> index, Bid bid, List<Ask> asks, Report report, Lender lender) {
        Party buyer = bid.buyer();
        double maxUnit = bid.family().unit * idx(index, bid.family()) * (1 + bid.urgency()) * (1 - buyer.greed * 0.5);
        List<Ask> offers = new ArrayList<>();
        for (Ask a : asks) {
            if (a.family == bid.family() && a.qty > 0 && a.seller != buyer) offers.add(a);
        }
        offers.sort(Comparator.comparingDouble(a -> effective(a, buyer) / a.units));
        int need = bid.units();
        for (Ask a : offers) {
            int purse = buyer.cash + buyer.creditLeft;
            if (need <= 0 || purse <= 0) break;
            double askItem = effective(a, buyer);
            if (askItem / a.units > maxUnit) break;
            double priceItem = (askItem + maxUnit * a.units) / 2;
            int minItems = (int) Math.ceil(1.0 / priceItem);
            int n = Math.min(a.qty, Math.max(minItems, (need + a.units - 1) / a.units));
            n = Math.min(n, (int) Math.floor(purse / priceItem));
            if (n < minItems || n <= 0) continue;
            int total = Math.max(1, (int) Math.round(n * priceItem));
            if (total > purse) continue;
            // What the buyer's emeralds do not cover is bought on credit (03 §Credit).
            int pay = Math.min(total, buyer.cash);
            int onCredit = total - pay;
            if (Chests.room(buyer.slots, new ItemStack(a.item)) < n) {
                // The buyer has no room for the goods: its bid ends, the ask stays for others.
                break;
            }
            if (!transfer(a.seller, buyer, a.item, n, pay)) {
                a.qty = 0;
                continue;
            }
            if (onCredit > 0) {
                if (lender == null || !lender.lend(a.seller, buyer, onCredit)) {
                    transfer(buyer, a.seller, a.item, n, pay);
                    a.seller.earned -= pay;
                    buyer.spent -= pay;
                    buyer.earned -= pay;
                    a.seller.spent -= pay;
                    continue;
                }
                buyer.creditLeft -= onCredit;
                buyer.borrowed += onCredit;
                a.seller.earned += onCredit;
            }
            a.qty -= n;
            need -= n * a.units;
            report.trades++;
            report.emeralds += total;
            if (onCredit > 0) report.credit += onCredit;
            gossip(a.seller, buyer);
        }
        return Math.max(0, need);
    }

    /** Ask price of one item for this buyer: the seller's reputation of the buyer moves it by up to 25 %. */
    private static double effective(Ask a, Party buyer) {
        return a.price * reputationFactor(a.seller, buyer);
    }

    static double reputationFactor(Party seller, Party buyer) {
        if (seller.villager == null || buyer.villager == null) return 1.0;
        int rep = seller.villager.getGossips().getReputation(buyer.villager.getUUID(), t -> true);
        return 1.0 - Math.max(-0.25, Math.min(0.25, rep / 100.0));
    }

    /** Moves n items seller -> buyer and total emeralds buyer -> seller; nothing changes when it cannot complete. */
    static boolean transfer(Party seller, Party buyer, Item item, int n, int total) {
        if (Chests.room(buyer.slots, new ItemStack(item)) < n || Chests.room(seller.slots, new ItemStack(Items.EMERALD)) < total) return false;
        List<ItemStack> goods = Chests.take(seller.slots, s -> plain(s, item), n);
        if (Chests.total(goods) < n) {
            Chests.put(seller.slots, goods);
            return false;
        }
        List<ItemStack> money = Chests.take(buyer.slots, s -> s.is(Items.EMERALD), total);
        if (Chests.total(money) < total) {
            Chests.put(buyer.slots, money);
            Chests.put(seller.slots, goods);
            return false;
        }
        Chests.put(buyer.slots, goods);
        Chests.put(seller.slots, money);
        seller.cash += total;
        seller.earned += total;
        buyer.cash -= total;
        buyer.spent += total;
        return true;
    }

    private static void gossip(Party a, Party b) {
        if (a.villager == null || b.villager == null) return;
        a.villager.getGossips().add(b.villager.getUUID(), GossipType.TRADING, GossipType.REPUTATION_CHANGE_PER_TRADE);
        b.villager.getGossips().add(a.villager.getUUID(), GossipType.TRADING, GossipType.REPUTATION_CHANGE_PER_TRADE);
    }

    /** Market index per family: smoothed bid/ask volume ratio, clamped to [0.25, 4] (03 §Prices). */
    private static void updateIndex(Map<String, Double> index, Report report) {
        for (Family f : Family.values()) {
            int demand = report.demand.getOrDefault(f, 0);
            int supply = report.supply.getOrDefault(f, 0);
            if (demand == 0 && supply == 0) continue;
            double ratio = Math.max(1 / RATIO_LIMIT, Math.min(RATIO_LIMIT, (demand + 1.0) / (supply + 1.0)));
            double old = idx(index, f);
            double next = Math.exp((1 - INDEX_WEIGHT) * Math.log(old) + INDEX_WEIGHT * Math.log(ratio));
            index.put(f.name(), Math.max(INDEX_MIN, Math.min(INDEX_MAX, next)));
        }
    }
}
