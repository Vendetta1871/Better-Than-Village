package net.betterthanvillage.society;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Market;
import net.betterthanvillage.economy.Needs;
import net.betterthanvillage.economy.Values;
import net.betterthanvillage.economy.Values.Family;
import net.betterthanvillage.mixin.society.VillagerTradeAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Player trading (docs_v2/03-economy.md §Player trade): a villager sells the goods of its household above its own
 * needs and buys what its household lacks, with the household's own emeralds, at the gathering price formula with
 * the player as the customer. The elder also sells land and house deeds for the treasury.
 */
public final class Trading {
    public static final String LABEL_PERMISSION = "продажа разрешения";
    public static final String LABEL_SELL = "торговля с игроком: продажа";
    public static final String LABEL_BUY = "торговля с игроком: покупка";
    /** Most uses one offer allows before the stock is recomputed. */
    private static final int MAX_USES = 12;

    /** Per-villager offer descriptors (weak: dropped with the villager). */
    private static final Map<Villager, Map<MerchantOffer, Desc>> TRACKED = new WeakHashMap<>();

    /** What one of our offers does (03 §Player trade, §Credit with the player). */
    enum Kind {
        /** Deed for emeralds; the treasury is paid. */
        PERMISSION,
        /** Household goods for emeralds. */
        SELL,
        /** The household buys goods with emeralds. */
        BUY,
        /** Household goods for a book and quill: the player signs a debt note. */
        SELL_ON_CREDIT,
        /** The player pays a debt and gets the note back. */
        REPAY,
        /** The household buys goods and pays with its own debt note. */
        PAY_WITH_NOTE,
        /** The player hands in a note and is paid. */
        COLLECT
    }

    /** Descriptor of one of our offers; owner is the household (null for the treasury). */
    record Desc(UUID colony, UUID owner, Kind kind, Item item, int count, int price, UUID debt) {
        boolean villagerSells() {
            return kind == Kind.SELL || kind == Kind.SELL_ON_CREDIT;
        }

        boolean permission() {
            return kind == Kind.PERMISSION;
        }
    }

    private Trading() {
    }

    /** Stop, idle and look at the customer while trading. */
    public static void freeze(Villager v) {
        v.getNavigation().stop();
        v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        v.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        if (v.getTradingPlayer() != null) {
            v.getLookControl().setLookAt(v.getTradingPlayer(), 30f, 30f);
        }
    }

    /**
     * Villager right-click by a player (main hand, adult, server side), routed by colony membership.
     * Returns a result to cancel vanilla, or null to let vanilla handle it. Colony-built offers that are not
     * tracked (e.g. loaded from NBT after a restart) never reach the vanilla trade screen.
     */
    public static InteractionResult interact(ServerLevel level, Villager v, Player player, InteractionHand hand) {
        Colony colony = ColonyManager.colonyOf(level, v);
        if (colony == null) {
            dropColonyOffers(v, false);
            return null;
        }
        // Conscripts trade as their trade (their household's goods and needs).
        ColonyRole role = colony.tradeOf(v.getUUID());
        ItemStack held = player.getItemInHand(hand);
        if (role == ColonyRole.MASON && Deeds.propertyLevel(held) > 0) {
            Deeds.builderOrder(level, colony, player, hand);
            return InteractionResult.SUCCESS;
        }
        if (!role.isProfession()) {
            dropColonyOffers(v, true);
            // Expedition members and villagers without a profession do not trade.
            return InteractionResult.SUCCESS;
        }
        if (v.isSleeping() || v.getTradingPlayer() != null) {
            dropColonyOffers(v, true);
            return null;
        }
        Professions.applyRole(level, colony, v);
        rebuildOffers(level, colony, v, role, player);
        freeze(v);
        ((VillagerTradeAccess) v).btv$startTrading(player);
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- offers

    /** Price multiplier of every colony-built offer; vanilla trade tables only use 0.05 and 0.2, so it marks ours. */
    static final float COLONY_MULTIPLIER = 0f;

    /**
     * Removes colony-built offers (all of them, or only untracked ones when keepTracked). If nothing remains the
     * offers are reset so vanilla regenerates the villager's own trades on demand.
     */
    static void dropColonyOffers(Villager v, boolean keepTracked) {
        Map<MerchantOffer, Desc> tracked = keepTracked ? TRACKED.get(v) : null;
        MerchantOffers offers = v.getOffers();
        boolean removed = offers.removeIf(o -> o.getPriceMultiplier() == COLONY_MULTIPLIER && (tracked == null || !tracked.containsKey(o)));
        if (!keepTracked) {
            TRACKED.remove(v);
        }
        if (removed && offers.isEmpty()) {
            v.setOffers(null);
        }
    }

    /** Rebuilds the villager's offers from its household (and, for the elder, the deeds). */
    public static void rebuildOffers(ServerLevel level, Colony colony, Villager v, ColonyRole role, Player player) {
        MerchantOffers offers = new MerchantOffers();
        Map<MerchantOffer, Desc> descs = new IdentityHashMap<>();
        if (role == ColonyRole.ELDER) {
            ColonyStorage.recount(level, colony);
            int uses = Prices.deedMaxUses(colony);
            String dim = level.dimension().location().toString();
            // House deeds can only be redeemed when terrain changes are allowed (Deeds.placeOrder).
            if (ColonyManager.terrainChangesAllowed(level)) {
                permission(offers, descs, colony, Deeds.propertyDeed(1, colony.id(), dim), Prices.smallHousePrice(colony), uses, 10);
                permission(offers, descs, colony, Deeds.propertyDeed(2, colony.id(), dim), Prices.mediumHousePrice(colony), uses, 20);
            }
            permission(offers, descs, colony, Deeds.landDeed(colony.id(), dim), Prices.landPrice(colony), uses, 10);
        }
        Household h = colony.households.get(v.getUUID());
        if (h != null && h.hasChest()) {
            householdOffers(level, colony, v, h, role, player, offers, descs);
        }
        PlayerCredit.debtOffers(level, colony, v, role, h, player, offers, descs);
        if (offers.isEmpty()) {
            MerchantOffer placeholder = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 1, 0, COLONY_MULTIPLIER);
            placeholder.setToOutOfStock();
            offers.add(placeholder);
        }
        v.setOffers(offers);
        TRACKED.put(v, descs);
    }

    private static void permission(MerchantOffers offers, Map<MerchantOffer, Desc> descs, Colony colony, ItemStack item, int price, int uses, int xp) {
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, price), item, uses, xp, COLONY_MULTIPLIER);
        offers.add(offer);
        descs.put(offer, new Desc(colony.id(), null, Kind.PERMISSION, item.getItem(), item.getCount(), price, null));
    }

    /**
     * Sell offers for household goods above the reserve, buy offers for families below it. A batch is the
     * smallest lot worth a whole emerald; buying keeps a survival reserve of emeralds.
     */
    private static void householdOffers(ServerLevel level, Colony colony, Villager v, Household h, ColonyRole role, Player player,
                                        MerchantOffers offers, Map<MerchantOffer, Desc> descs) {
        List<ColonyStorage.Slot> slots = ColonyStorage.usable(level, h);
        EnumMap<Family, Integer> reserve = Needs.household(role);
        Map<Item, Integer> items = new LinkedHashMap<>();
        Map<Item, ItemStack> samples = new LinkedHashMap<>();
        for (ColonyStorage.Slot slot : slots) {
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
        double rep = reputationFactor(v, player);
        double hero = heroFactor(player);
        // Sell: goods above the household's own needs.
        for (Map.Entry<Family, List<Item>> e : byFamily.entrySet()) {
            Family f = e.getKey();
            int sellable = held.get(f) - reserve.getOrDefault(f, 0);
            List<Item> list = e.getValue();
            list.sort(Comparator.comparingDouble((Item it) -> -Values.base(samples.get(it)) / Values.units(samples.get(it), f)));
            for (Item item : list) {
                if (sellable <= 0 || offers.size() >= 12) break;
                ItemStack sample = samples.get(item);
                int units = Values.units(sample, f);
                int qty = Math.min(items.get(item), sellable / units);
                if (qty <= 0) continue;
                double unit = Values.base(sample) * Market.index(colony, f) * (1 + h.greed) * rep * hero;
                int batch = Math.max(1, (int) Math.ceil(1.0 / unit));
                if (batch > qty || batch > sample.getMaxStackSize()) continue;
                int price = Math.max(1, (int) Math.round(batch * unit));
                int uses = Math.min(MAX_USES, qty / batch);
                MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, Math.min(64, price)), new ItemStack(item, batch), uses, 1, COLONY_MULTIPLIER);
                offers.add(offer);
                descs.put(offer, new Desc(colony.id(), h.owner, Kind.SELL, item, batch, Math.min(64, price), null));
                PlayerCredit.creditSellOffer(level, colony, h, player, item, batch, Math.min(64, price), offers, descs);
                sellable -= uses * batch * units;
            }
        }
        // Buy: families below the reserve, paid from emeralds above the survival reserve.
        int cash = Chests.count(slots, s -> s.is(Items.EMERALD)) - survivalReserve(colony);
        for (Map.Entry<Family, Integer> e : reserve.entrySet()) {
            Family f = e.getKey();
            int missing = e.getValue() - held.getOrDefault(f, 0);
            if (missing <= 0 || cash <= 0 || offers.size() >= 16) continue;
            Item item = buyItem(f, samples);
            if (item == null) continue;
            ItemStack sample = new ItemStack(item);
            int units = Values.units(sample, f);
            double unit = Values.base(sample) * Market.index(colony, f) * (1 + Needs.urgency(f, held.getOrDefault(f, 0))) * (1 - h.greed * 0.5);
            int batch = Math.max(1, (int) Math.ceil(1.0 / unit));
            if (batch > sample.getMaxStackSize()) continue;
            int price = Math.max(1, (int) Math.round(batch * unit));
            int wanted = (missing + batch * units - 1) / (batch * units);
            int uses = Math.min(MAX_USES, Math.min(wanted, cash / price));
            if (uses <= 0 && Chests.room(slots, sample) >= batch) {
                // No cash left: the household pays the player with a debt note (03 §The player lends).
                PlayerCredit.noteBuyOffer(level, colony, h, player, item, batch, Math.min(64, price), offers, descs);
                continue;
            }
            if (uses <= 0 || Chests.room(slots, sample) < batch) continue;
            MerchantOffer offer = new MerchantOffer(new ItemCost(item, batch), new ItemStack(Items.EMERALD, Math.min(64, price)), uses, 1, COLONY_MULTIPLIER);
            offers.add(offer);
            descs.put(offer, new Desc(colony.id(), h.owner, Kind.BUY, item, batch, Math.min(64, price), null));
            cash -= uses * price;
        }
    }

    /** The item a household asks the player for when it lacks a family: what it already uses, else the plainest kind. */
    private static Item buyItem(Family f, Map<Item, ItemStack> samples) {
        for (ItemStack s : samples.values()) {
            if (Values.family(s) == f) return s.getItem();
        }
        return switch (f) {
            case FOOD -> Items.BREAD;
            case WHEAT -> Items.WHEAT;
            case SEEDS -> Items.WHEAT_SEEDS;
            case SAPLINGS -> Items.OAK_SAPLING;
            case LOGS -> Items.OAK_LOG;
            case COAL -> Items.COAL;
            case TORCH -> Items.TORCH;
            case PICKAXE -> Items.STONE_PICKAXE;
            case AXE -> Items.STONE_AXE;
            case HOE -> Items.STONE_HOE;
            case SHEARS -> Items.SHEARS;
            case BUCKET -> Items.BUCKET;
            case FISHING_ROD -> Items.FISHING_ROD;
            default -> null;
        };
    }

    /** Emeralds a household never spends on the player's goods: a few days of food (03 §Currency). */
    private static int survivalReserve(Colony colony) {
        double food = Needs.DAILY_FOOD * Needs.FOOD_DAYS * Family.FOOD.unit * Market.index(colony, Family.FOOD);
        return Math.max(2, (int) Math.ceil(food));
    }

    /** The villager's gossip about the player moves its prices by up to 25 %. */
    static double reputationFactor(Villager v, Player player) {
        if (player == null) return 1.0;
        int rep = v.getPlayerReputation(player);
        return 1.0 - Math.max(-0.25, Math.min(0.25, rep / 100.0));
    }

    /** Hero of the Village discount, as in vanilla (30 % plus 6.25 % per level). */
    private static double heroFactor(Player player) {
        if (player == null) return 1.0;
        MobEffectInstance hero = player.getEffect(MobEffects.HERO_OF_THE_VILLAGE);
        return hero == null ? 1.0 : Math.max(0.25, 1.0 - (0.3 + 0.0625 * hero.getAmplifier()));
    }

    /** Debug listing: the offers the villager would show a neutral customer. */
    public static String describeOffers(ServerLevel level, Colony colony, Villager v) {
        ColonyRole role = colony.tradeOf(v.getUUID());
        if (!role.isProfession()) return "-";
        rebuildOffers(level, colony, v, role, null);
        StringBuilder sb = new StringBuilder();
        Map<MerchantOffer, Desc> tracked = TRACKED.get(v);
        for (MerchantOffer o : v.getOffers()) {
            Desc d = tracked == null ? null : tracked.get(o);
            if (d == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(d.kind().name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(d.count()).append(' ')
                    .append(Values.name(d.item())).append(" for ").append(d.price());
            sb.append(" x").append(o.getMaxUses());
        }
        dropColonyOffers(v, false);
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    // ---------------------------------------------------------------- validation and settlement

    static Desc desc(Villager v, MerchantOffer offer) {
        Map<MerchantOffer, Desc> map = TRACKED.get(v);
        return map == null || offer == null ? null : map.get(offer);
    }

    /** True if the offer is one of ours for this villager. */
    public static boolean isTracked(Villager v, MerchantOffer offer) {
        return desc(v, offer) != null;
    }

    /** Validation before the player takes the output (06 §1.3). */
    public static boolean validate(ServerLevel level, Villager v, MerchantOffer offer) {
        Desc d = desc(v, offer);
        if (d == null) {
            return true;
        }
        Colony colony = ColonyManager.data(level).colony(d.colony());
        return colony != null && validate(level, colony, d, offer);
    }

    private static List<ColonyStorage.Slot> slots(ServerLevel level, Colony colony, Desc d) {
        if (d.owner() == null) return ColonyStorage.usable(level, colony);
        Household h = colony.households.get(d.owner());
        return h == null ? List.of() : ColonyStorage.usable(level, h);
    }

    private static boolean validate(ServerLevel level, Colony colony, Desc d, MerchantOffer offer) {
        int paid = offer.getCostA().getCount();
        ItemStack emerald = new ItemStack(Items.EMERALD);
        List<ColonyStorage.Slot> slots = slots(level, colony, d);
        if (slots.isEmpty()) return false;
        return switch (d.kind()) {
            case PERMISSION -> Chests.room(slots, emerald) >= paid;
            case SELL -> Chests.count(slots, s -> plain(s, d.item())) >= d.count() && Chests.room(slots, emerald) >= paid;
            case BUY -> Chests.count(slots, s -> s.is(Items.EMERALD)) >= d.price() && Chests.room(slots, new ItemStack(d.item())) >= paid;
            case SELL_ON_CREDIT, REPAY, PAY_WITH_NOTE, COLLECT -> PlayerCredit.validate(level, colony, d, slots);
        };
    }

    static boolean plain(ItemStack s, Item item) {
        return s.is(item) && Values.family(s) != null;
    }

    /** Disables a failed offer and resends the offer list to the trading player. */
    public static void disable(Villager v, MerchantOffer offer, Player player) {
        offer.setToOutOfStock();
        if (player instanceof ServerPlayer sp && sp.containerMenu != null) {
            sp.sendMerchantOffers(sp.containerMenu.containerId, v.getOffers(), v.getVillagerData().getLevel(), v.getVillagerXp(),
                    v.showProgressBar(), v.canRestock());
        }
    }

    /** Chest-side settlement after a completed trade: the household (or treasury) gets or pays the real items. */
    public static void afterUsing(ServerLevel level, Villager v, MerchantOffer offer) {
        Desc d = desc(v, offer);
        if (d == null) {
            return;
        }
        Colony colony = ColonyManager.data(level).colony(d.colony());
        if (colony == null || !validate(level, colony, d, offer)) {
            return;
        }
        int paid = offer.getCostA().getCount();
        List<ColonyStorage.Slot> slots = slots(level, colony, d);
        Household h = d.owner() == null ? null : colony.households.get(d.owner());
        if (d.kind() == Kind.SELL_ON_CREDIT || d.kind() == Kind.REPAY || d.kind() == Kind.PAY_WITH_NOTE || d.kind() == Kind.COLLECT) {
            PlayerCredit.settle(level, colony, v, d, slots, h);
        } else if (d.permission()) {
            Chests.put(slots, List.of(new ItemStack(Items.EMERALD, paid)));
            colony.soldTrades++;
            WorkTracker.status(v, LABEL_PERMISSION);
        } else if (d.villagerSells()) {
            List<ItemStack> goods = Chests.take(slots, s -> plain(s, d.item()), d.count());
            if (Chests.total(goods) < d.count()) {
                Chests.put(slots, goods);
                return;
            }
            Chests.put(slots, List.of(new ItemStack(Items.EMERALD, paid)));
            if (h != null) h.income += paid;
            colony.soldTrades++;
            WorkTracker.credit(v, LABEL_SELL, d.count());
        } else {
            List<ItemStack> money = Chests.take(slots, s -> s.is(Items.EMERALD), d.price());
            if (Chests.total(money) < d.price()) {
                Chests.put(slots, money);
                return;
            }
            Chests.put(slots, List.of(new ItemStack(d.item(), paid)));
            colony.boughtTrades++;
            WorkTracker.status(v, LABEL_BUY);
        }
        ColonyManager.markDirty(level);
    }
}
