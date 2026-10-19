package net.betterthanvillage.society;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.RoleBalancer;
import net.betterthanvillage.defense.GuardGear;
import net.betterthanvillage.mixin.society.VillagerTradeAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Player trading driven by colony stock (06 §1.3): offer building, validation and settlement. */
public final class Trading {
    public static final String LABEL_PERMISSION = "продажа разрешения";
    public static final String LABEL_SELL = "торговля с игроком: продажа";
    public static final String LABEL_BUY = "торговля с игроком: покупка";

    /** Per-villager offer descriptors (weak: dropped with the villager). */
    private static final Map<Villager, Map<MerchantOffer, Desc>> TRACKED = new WeakHashMap<>();

    /** Descriptor of one of our offers. */
    record Desc(UUID colony, ColonyRole role, Item item, int count, int price, boolean villagerSells, int reserve,
                Predicate<ItemStack> predicate, boolean permission) {
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
     * Villager right-click by a player (main hand, adult, server side, villager inside a colony).
     * Returns a result to cancel vanilla, or null to let vanilla handle it.
     */
    public static InteractionResult interact(ServerLevel level, Villager v, Player player, InteractionHand hand) {
        Colony colony = ColonyManager.colonyAt(level, v.blockPosition());
        if (colony == null) {
            return null;
        }
        ColonyRole role = colony.roleOf(v.getUUID());
        ItemStack held = player.getItemInHand(hand);
        if (role == ColonyRole.BUILDER && Deeds.propertyLevel(held) > 0) {
            Deeds.builderOrder(level, colony, player, hand);
            return InteractionResult.SUCCESS;
        }
        if (role == ColonyRole.CITIZEN || v.isSleeping() || v.getTradingPlayer() != null) {
            return null;
        }
        RoleBalancer.applyRole(level, colony, v);
        rebuildOffers(level, colony, v, role);
        freeze(v);
        ((VillagerTradeAccess) v).btv$startTrading(player);
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- offers

    private static boolean hasTable(ColonyRole role) {
        return switch (role) {
            case ELDER, MINER, LUMBERJACK, FARMER, FISHERMAN, RANCHER, BUILDER, GUARD -> true;
            default -> false;
        };
    }

    /** Rebuilds the role's offer list from colony storage; roles without a table keep their vanilla offers. */
    public static void rebuildOffers(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        if (!hasTable(role)) {
            return;
        }
        Builder b = new Builder(level, colony, role);
        switch (role) {
            case ELDER -> {
                ColonyStorage.recount(level, colony);
                int uses = Prices.deedMaxUses(colony);
                String dim = level.dimension().location().toString();
                // House deeds can only be redeemed when terrain changes are allowed (Deeds.placeOrder).
                if (ColonyManager.terrainChangesAllowed(level)) {
                    b.permission(Deeds.propertyDeed(1, colony.id(), dim), Prices.smallHousePrice(colony), uses, 10);
                    b.permission(Deeds.propertyDeed(2, colony.id(), dim), Prices.mediumHousePrice(colony), uses, 20);
                }
                b.permission(Deeds.landDeed(colony.id(), dim), Prices.landPrice(colony), uses, 10);
            }
            // Sell reserves are >= the colony's buy target for the same item family (no buy/sell arbitrage).
            case MINER -> {
                b.sell(Goods.COBBLE, 16, 96, 1);
                b.sell(Goods.item(Items.COPPER_INGOT), 8, 8, 1);
                b.sell(Goods.item(Items.IRON_INGOT), 4, 36, 2);
                b.sell(Goods.item(Items.DIAMOND), 1, 4, 4);
                b.buy(Items.OAK_LOG, Goods.LOGS, 8, 16, 1);
                b.buy(Items.COAL, 8, 16, 1);
            }
            case LUMBERJACK -> {
                b.sell(Goods.LOGS, 8, 48, 1);
                b.sell(Goods.SAPLINGS, 4, 8, 1);
                b.buy(Items.OAK_SAPLING, Goods.SAPLINGS, 8, 8, 1);
            }
            case FARMER -> {
                b.sell(Goods.item(Items.WHEAT), 16, 32, 1);
                b.sell(Goods.item(Items.BREAD), 6, 64, 1);
                b.sell(Goods.ROOTS, 12, 24, 1);
                b.buy(Items.WHEAT_SEEDS, 16, 16, 1);
                b.buy(Items.PUMPKIN_SEEDS, 4, 8, 1);
                b.buy(Items.BREAD, 8, 64, 2);
            }
            case FISHERMAN -> {
                b.sell(Goods.anyOf(Items.COD, Items.COOKED_COD), 8, 16, 1);
                b.sell(Goods.anyOf(Items.SALMON, Items.COOKED_SALMON), 6, 12, 1);
                b.buy(Items.STRING, 3, 3, 1);
            }
            case RANCHER -> {
                b.sell(Goods.item(Items.EGG), 16, 16, 1);
                b.sell(Goods.item(Items.LEATHER), 4, 8, 1);
                b.sell(Goods.item(Items.FEATHER), 8, 12, 1);
                b.sell(Goods.WOOL, 6, 12, 1);
                b.buy(Items.WHEAT, 16, 16, 2);
            }
            case BUILDER -> {
                b.sell(Goods.PLANKS, 16, 64, 1);
                b.sell(Goods.STONE, 16, 128, 1);
                b.buy(Items.OAK_LOG, Goods.LOGS, 16, 48, 2);
                b.buy(Items.COBBLESTONE, Goods.COBBLE, 32, 96, 2);
            }
            case GUARD -> {
                GuardGear.ensureStock(level, colony);
                for (GuardGear.Entry entry : GuardGear.table()) {
                    b.sell(Goods.exact(entry.item()), 1, 1, entry.price());
                }
                b.sell(Goods.item(Items.ARROW), 16, 32, 1);
                b.buy(Items.ARROW, 32, 32, 2);
                b.buy(Items.IRON_INGOT, 8, 24, 3);
            }
            default -> {
            }
        }
        if (b.offers.isEmpty()) {
            MerchantOffer placeholder = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(placeholderItem(role)), 1, 0, 0f);
            placeholder.setToOutOfStock();
            b.offers.add(placeholder);
        }
        v.setOffers(b.offers);
        TRACKED.put(v, b.descs);
    }

    private static Item placeholderItem(ColonyRole role) {
        return switch (role) {
            case ELDER -> Items.PAPER;
            case MINER, BUILDER -> Items.COBBLESTONE;
            case LUMBERJACK -> Items.OAK_LOG;
            case FARMER, RANCHER -> Items.WHEAT;
            case FISHERMAN -> Items.COD;
            case GUARD -> Items.ARROW;
            default -> Items.BREAD;
        };
    }

    /** Collects offers and their descriptors for one villager. */
    private static final class Builder {
        final ServerLevel level;
        final Colony colony;
        final ColonyRole role;
        final MerchantOffers offers = new MerchantOffers();
        final Map<MerchantOffer, Desc> descs = new IdentityHashMap<>();
        final List<ColonyStorage.Slot> roleSlots;

        Builder(ServerLevel level, Colony colony, ColonyRole role) {
            this.level = level;
            this.colony = colony;
            this.role = role;
            this.roleSlots = roleSlots(level, colony, role);
        }

        void permission(ItemStack item, int price, int uses, int xp) {
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, price), item, uses, xp, 0f);
            add(offer, new Desc(colony.id(), role, item.getItem(), item.getCount(), price, true, 0, s -> false, true));
        }

        void sell(Predicate<ItemStack> pred, int baseBatch, int reserve, int basePrice) {
            int stock = ColonyStorage.count(roleSlots, pred);
            int reserve2 = sellReserve(colony, role, reserve);
            int batch = Prices.sellBatch(baseBatch, stock, reserve2);
            int price = Prices.sellPrice(basePrice, baseBatch, stock, reserve2, batch);
            if (price <= 0) return;
            Item chosen = null;
            int itemTotal = 0;
            search:
            for (ColonyStorage.Slot slot : roleSlots) {
                for (int i = 0; i < slot.container().getContainerSize(); i++) {
                    ItemStack s = slot.container().getItem(i);
                    if (s.isEmpty() || !pred.test(s)) continue;
                    Item item = s.getItem();
                    int total = ColonyStorage.count(roleSlots, x -> x.is(item) && pred.test(x));
                    if (total >= batch) {
                        chosen = item;
                        itemTotal = total;
                        break search;
                    }
                }
            }
            if (chosen == null) return;
            int uses = Math.min(12, Math.min((stock - reserve2) / batch, itemTotal / batch));
            if (uses <= 0) return;
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, price), new ItemStack(chosen, batch), uses, 1, 0f);
            add(offer, new Desc(colony.id(), role, chosen, batch, price, true, reserve2, pred, false));
        }

        void buy(Item item, int baseBatch, int target, int basePrice) {
            buy(item, s -> s.is(item), baseBatch, target, basePrice);
        }

        /**
         * The stock ("have") is counted with the same family predicate the colony's sellers use, so a colony never
         * buys an item it is selling at the same time. The batch never shrinks: the price is for the full batch.
         */
        void buy(Item item, Predicate<ItemStack> family, int baseBatch, int target, int basePrice) {
            int have = ColonyStorage.count(level, colony, family);
            int target2 = Math.max(target, (role == ColonyRole.FARMER || role == ColonyRole.RANCHER) ? colony.population() * 2 : target);
            int price = Prices.buyPrice(basePrice, have, target2);
            int batch = Math.max(1, baseBatch);
            int affordable = ColonyStorage.count(level, colony, Goods.EMERALD) / price;
            int wanted = (Math.max(0, target2 - have) + batch - 1) / batch;
            int uses = Math.min(12, Math.min(affordable, wanted));
            if (uses <= 0 || ColonyStorage.freeCapacity(level, colony, new ItemStack(item)) < batch) return;
            MerchantOffer offer = new MerchantOffer(new ItemCost(item, batch), new ItemStack(Items.EMERALD, price), uses, 1, 0f);
            add(offer, new Desc(colony.id(), role, item, batch, price, false, 0, s -> s.is(item), false));
        }

        private void add(MerchantOffer offer, Desc desc) {
            offers.add(offer);
            descs.put(offer, desc);
        }
    }

    private static List<ColonyStorage.Slot> roleSlots(ServerLevel level, Colony colony, ColonyRole role) {
        return ColonyStorage.usableFor(level, colony, role, role == ColonyRole.GUARD);
    }

    private static int sellReserve(Colony colony, ColonyRole role, int reserve) {
        return Math.max(reserve, (role == ColonyRole.FARMER || role == ColonyRole.FISHERMAN) ? colony.population() * 2 : 0);
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
        if (colony == null) {
            return false;
        }
        return validate(level, colony, d, offer);
    }

    private static boolean validate(ServerLevel level, Colony colony, Desc d, MerchantOffer offer) {
        int paid = offer.getCostA().getCount();
        ItemStack emerald = new ItemStack(Items.EMERALD);
        if (d.permission()) {
            return ColonyStorage.freeCapacity(level, colony, emerald) >= paid;
        }
        if (d.villagerSells()) {
            List<ColonyStorage.Slot> slots = roleSlots(level, colony, d.role());
            return ColonyStorage.count(slots, d.predicate()) >= d.reserve() + d.count()
                    && ColonyStorage.count(slots, s -> s.is(d.item()) && d.predicate().test(s)) >= d.count()
                    && ColonyStorage.freeCapacity(level, colony, emerald) >= paid;
        }
        return ColonyStorage.count(level, colony, Goods.EMERALD) >= d.price()
                && ColonyStorage.freeCapacity(level, colony, new ItemStack(d.item())) >= paid;
    }

    /** Disables a failed offer and resends the offer list to the trading player. */
    public static void disable(Villager v, MerchantOffer offer, Player player) {
        offer.setToOutOfStock();
        if (player instanceof ServerPlayer sp && sp.containerMenu != null) {
            sp.sendMerchantOffers(sp.containerMenu.containerId, v.getOffers(), v.getVillagerData().getLevel(), v.getVillagerXp(),
                    v.showProgressBar(), v.canRestock());
        }
    }

    /** Storage-side settlement after a completed trade. */
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
        if (d.permission()) {
            if (ColonyStorage.transaction(level, colony, () -> true, List.of(new ItemStack(Items.EMERALD, paid)))) {
                colony.soldTrades++;
                WorkTracker.status(v, LABEL_PERMISSION);
            }
        } else if (d.villagerSells()) {
            List<ColonyStorage.Slot> slots = roleSlots(level, colony, d.role());
            if (!ColonyStorage.transaction(level, colony,
                    () -> ColonyStorage.consume(level, colony, slots, s -> s.is(d.item()) && d.predicate().test(s), d.count()),
                    List.of(new ItemStack(Items.EMERALD, paid)))) return;
            colony.soldTrades++;
            WorkTracker.credit(v, LABEL_SELL, d.count());
        } else {
            if (!ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, Goods.EMERALD, d.price()),
                    List.of(new ItemStack(d.item(), paid)))) return;
            colony.boughtTrades++;
            WorkTracker.status(v, LABEL_BUY);
        }
        ColonyManager.markDirty(level);
    }
}
