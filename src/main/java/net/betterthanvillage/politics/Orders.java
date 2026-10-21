package net.betterthanvillage.politics;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Needs;
import net.betterthanvillage.economy.Treasury;
import net.betterthanvillage.economy.Values;
import net.betterthanvillage.economy.Values.Family;
import net.betterthanvillage.world.Caravan;
import net.betterthanvillage.world.Caravans;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The ruler's orders (docs_v2/06-politics.md §Orders): redistribution between treasuries in a famine, temporary
 * higher taxes for a poor capital, mobilization for a war. A village whose loyalty is below the threshold may ignore
 * an order, which costs the ruler's patience; the ruler then tolerates (suppression needs campaigns, phase 5).
 */
public final class Orders {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** An order not carried out within this many days lapses. */
    private static final long ORDER_DAYS = 3;
    /** Share of a village's treasury emeralds a tax order takes. */
    private static final double TAX_SHARE = 0.2;
    /** Days a temporary tax lasts. */
    private static final long TAX_DAYS = 3;

    private Orders() {
    }

    private static ColonyConfig.Politics cfg() {
        return ColonyConfig.get().politics;
    }

    /** Once a day: the ruler of a country with several villages looks at their treasuries and gives orders. */
    static void daily(ServerLevel level, Country country) {
        ColonySavedData data = ColonyManager.data(level);
        long now = level.getGameTime();
        data.orders.removeIf(o -> o.kind != Order.Kind.MOBILIZE && now - o.issued > ORDER_DAYS * 24000L);
        List<Colony> villages = Countries.villages(level, country.id);
        Colony capital = data.colony(country.capital);
        if (villages.size() < 2 || capital == null) return;
        // Famine: food from the best-stocked treasury to a hungry one.
        for (Colony hungry : villages) {
            if (pending(level, hungry.id(), Order.Kind.REDISTRIBUTE)) continue;
            int need = Needs.treasury(hungry, 0).getOrDefault(Family.FOOD, 0);
            List<ColonyStorage.Slot> slots = ColonyStorage.treasury(level, hungry);
            if (slots.isEmpty() || food(slots) * 2 >= need) continue;
            Colony donor = null;
            int best = 0;
            for (Colony c : villages) {
                if (c == hungry) continue;
                int surplus = food(ColonyStorage.treasury(level, c)) - 2 * Needs.treasury(c, 0).getOrDefault(Family.FOOD, 0);
                if (surplus > best) {
                    best = surplus;
                    donor = c;
                }
            }
            if (donor != null) issue(level, country, Order.Kind.REDISTRIBUTE, donor, hungry, Family.FOOD.name(), Math.min(96, best / 2));
        }
        // A poor capital raises a temporary tax on the other villages.
        int reserve = Treasury.dailyWageBill(capital) * 3 + 8;
        if (now >= country.taxUntil && emeralds(level, capital) < reserve) {
            country.taxRate = TAX_SHARE;
            country.taxUntil = now + TAX_DAYS * 24000L;
            for (Colony c : villages) {
                if (c == capital) continue;
                int n = (int) Math.ceil(emeralds(level, c) * country.taxRate);
                if (n > 0) issue(level, country, Order.Kind.TAX, c, capital, "emerald", n);
            }
        }
        // Orders the villages obeyed but could not carry out yet (no caravan free) are tried again.
        for (Order o : new ArrayList<>(data.orders)) {
            if (o.country.equals(country.id) && o.status == Order.Status.OBEYED && o.value == 0 && o.kind != Order.Kind.MOBILIZE) execute(level, o);
        }
    }

    private static int food(List<ColonyStorage.Slot> slots) {
        int n = 0;
        for (ColonyStorage.Slot slot : slots) {
            for (int i = 0; i < slot.container().getContainerSize(); i++) {
                ItemStack s = slot.container().getItem(i);
                if (Values.family(s) == Family.FOOD) n += s.getCount() * Values.units(s, Family.FOOD);
            }
        }
        return n;
    }

    private static int emeralds(ServerLevel level, Colony c) {
        return Chests.count(ColonyStorage.treasury(level, c), s -> s.is(Items.EMERALD));
    }

    private static boolean pending(ServerLevel level, UUID colony, Order.Kind kind) {
        for (Order o : ColonyManager.data(level).orders) {
            if (o.kind == kind && (colony.equals(o.colony) || colony.equals(o.target)) && o.value == 0) return true;
        }
        return false;
    }

    /** The ruler gives an order; the village obeys or, when disloyal, ignores it. */
    public static Order issue(ServerLevel level, Country country, Order.Kind kind, Colony colony, Colony target, String goods, int amount) {
        Order o = new Order(UUID.randomUUID(), kind, country.id, colony.id());
        o.target = target == null ? null : target.id();
        o.goods = goods;
        o.amount = amount;
        o.issued = level.getGameTime();
        ColonyManager.data(level).orders.add(o);
        ColonyManager.markDirty(level);
        boolean capital = colony.id().equals(country.capital);
        if (!capital && colony.loyalty < cfg().disobedience_loyalty) {
            o.status = Order.Status.IGNORED;
            colony.ignoredOrders++;
            country.patience = Math.max(0, country.patience - cfg().patience_per_ignored_order);
            LOGGER.info("[politics] {} ignores the ruler's {} order (loyalty {}, ruler's patience {})", Countries.villageName(colony),
                    kind.key(), Math.round(colony.loyalty), Math.round(country.patience));
            Politics.rulerResponds(level, country, colony);
            return o;
        }
        o.status = Order.Status.OBEYED;
        LOGGER.info("[politics] {} obeys the ruler's {} order ({} {}{})", Countries.villageName(colony), kind.key(), amount, goods,
                target == null ? "" : " for " + Countries.villageName(target));
        execute(level, o);
        return o;
    }

    /** An obeyed order is carried out by caravan (mobilization is only recorded until campaigns exist). */
    private static void execute(ServerLevel level, Order o) {
        ColonySavedData data = ColonyManager.data(level);
        Colony from = data.colony(o.colony), to = o.target == null ? null : data.colony(o.target);
        if (from == null || to == null || o.kind == Order.Kind.MOBILIZE) return;
        Predicate<ItemStack> goods;
        int items;
        Caravan.Kind kind;
        if (o.kind == Order.Kind.TAX) {
            goods = s -> s.is(Items.EMERALD);
            items = o.amount;
            kind = Caravan.Kind.TAX;
        } else {
            Family f;
            try {
                f = Family.valueOf(o.goods);
            } catch (IllegalArgumentException e) {
                return;
            }
            goods = s -> Values.family(s) == f;
            // Food is counted in nutrition; a loaf is several units.
            items = f == Family.FOOD ? Math.max(1, o.amount / 5) : o.amount;
            kind = Caravan.Kind.REDISTRIBUTE;
        }
        Caravans.dispatch(level, from, to, kind, o.id, goods, items);
    }

    /** A caravan delivered an order: the giving village resents what it lost, the receiving one is grateful. */
    public static void delivered(ServerLevel level, UUID orderId, Colony from, Colony to, int value) {
        ColonySavedData data = ColonyManager.data(level);
        Order o = null;
        for (Order x : data.orders) if (x.id.equals(orderId)) o = x;
        if (o != null) o.value = Math.max(1, value);
        Country country = Countries.get(level, from.country);
        boolean fromCapital = country != null && from.id().equals(country.capital);
        boolean toCapital = country != null && to.id().equals(country.capital);
        if (!fromCapital) Politics.adjustLoyalty(level, from, -Math.min(15, value / 4.0));
        if (!toCapital && to.country != null && to.country.equals(from.country)) Politics.adjustLoyalty(level, to, Math.min(15, value / 4.0));
    }

    /** War: the ruler calls the warriors of every village of the country to arms (campaigns are phase 5). */
    static void mobilize(ServerLevel level, UUID country, UUID enemy) {
        Country c = Countries.get(level, country);
        if (c == null) return;
        Colony capital = ColonyManager.data(level).colony(c.capital);
        for (Colony v : Countries.villages(level, country)) {
            int warriors = 0;
            for (UUID id : v.roles.keySet()) if (v.roleOf(id) == ColonyRole.WARRIOR) warriors++;
            if (warriors > 0) issue(level, c, Order.Kind.MOBILIZE, v, capital, "warriors", warriors);
        }
    }

    public static List<String> status(ServerLevel level, UUID country) {
        List<String> out = new ArrayList<>();
        ColonySavedData data = ColonyManager.data(level);
        for (Order o : data.orders) {
            if (!o.country.equals(country)) continue;
            Colony c = data.colony(o.colony), t = o.target == null ? null : data.colony(o.target);
            out.add(o.kind.key() + " " + o.amount + " " + o.goods + " " + (c == null ? "-" : Countries.villageName(c))
                    + (t == null ? "" : " -> " + Countries.villageName(t)) + ": " + o.status.key() + (o.value > 0 ? ", delivered " + o.value : ""));
        }
        return out;
    }
}
