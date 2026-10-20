package net.betterthanvillage.colony;

import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.StockScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Stock operations over the usable storages in scope (storage-list order, then slot order). The scope
 * ({@link StockScope}) is the treasury (the village stock) or, during a villager's AI step, its household's home
 * chests.
 */
public final class ColonyStorage {
    private ColonyStorage() {
    }

    // ---------------------------------------------------------------- food helpers

    /** Edible items the colony never counts, hands out or eats (valuable or harmful). */
    private static final Set<Item> NOT_COLONY_FOOD = Set.of(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.ROTTEN_FLESH,
            Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);

    /** max(1, nutrition) for colony food, else 0. */
    public static int foodValue(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null || NOT_COLONY_FOOD.contains(stack.getItem()) ? 0 : Math.max(1, food.nutrition());
    }

    public static boolean isFood(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && !NOT_COLONY_FOOD.contains(stack.getItem());
    }

    /** What remains after eating count items of this food (bowls of stews and soups), or EMPTY. */
    public static ItemStack eatRemainder(ItemStack food, int count) {
        FoodProperties props = food.get(DataComponents.FOOD);
        if (props == null || count <= 0 || props.usingConvertsTo().isEmpty()) {
            return ItemStack.EMPTY;
        }
        return props.usingConvertsTo().get().copyWithCount(count);
    }

    /** Sum of food value x count over a container. */
    public static int foodValue(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            total += foodValue(s) * s.getCount();
        }
        return total;
    }

    // ---------------------------------------------------------------- storage access

    public static Container container(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    /** Chunk loaded and (not an inventory, or not a protected container). */
    public static boolean isUsable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        return !(level.getBlockEntity(pos) instanceof Container) || !Protection.isProtectedContainer(level, pos);
    }

    /** Storage kind of a colony storage position (scanned containers are GENERAL). */
    public static StorageKind kindOf(Colony colony, BlockPos pos) {
        return colony.managedStorages.getOrDefault(pos, StorageKind.GENERAL);
    }

    public record Slot(BlockPos pos, Container container) {
    }

    /** Positions of the stock in scope: the scoped household's home chests, else the treasury. */
    public static List<BlockPos> stockPositions(Colony colony) {
        Household h = StockScope.household(colony);
        return h != null ? h.chests : colony.storages;
    }

    /** True if the stock in scope has a storage (the scoped household has a home chest, or the treasury one). */
    public static boolean hasStock(Colony colony) {
        return !stockPositions(colony).isEmpty();
    }

    /** Usable storage containers in storage-list order. */
    public static List<Slot> usable(ServerLevel level, Colony colony) {
        return slots(level, stockPositions(colony));
    }

    /** Usable treasury containers, whatever the scope. */
    public static List<Slot> treasury(ServerLevel level, Colony colony) {
        return slots(level, colony.storages);
    }

    /** Usable containers of a household's home chests. */
    public static List<Slot> usable(ServerLevel level, Household household) {
        return slots(level, household.chests);
    }

    private static List<Slot> slots(ServerLevel level, List<BlockPos> positions) {
        List<Slot> out = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (!isUsable(level, pos)) continue;
            Container c = container(level, pos);
            if (c != null) {
                out.add(new Slot(pos, c));
            }
        }
        return out;
    }

    /** Usable storages accessible to a role: own kind plus GENERAL (all kinds when allKinds); a household's are all its own. */
    public static List<Slot> usableFor(ServerLevel level, Colony colony, ColonyRole role, boolean allKinds) {
        if (StockScope.household(colony) != null) {
            return usable(level, colony);
        }
        List<Slot> out = new ArrayList<>();
        StorageKind own = role.ownKind();
        for (Slot slot : usable(level, colony)) {
            StorageKind kind = kindOf(colony, slot.pos());
            if (allKinds || kind == own || kind == StorageKind.GENERAL) {
                out.add(slot);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- deposit

    /** Inserts as much of the stack as possible; the stack is shrunk in place. Returns true if everything fit. */
    public static boolean deposit(ServerLevel level, Colony colony, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        StorageKind itemKind = StorageKind.classify(stack);
        List<Slot> slots = new ArrayList<>(usable(level, colony));
        slots.sort(Comparator.comparingInt(s -> kindOf(colony, s.pos()).rankFor(itemKind)));
        for (Slot slot : slots) {
            insert(slot.container(), stack);
            if (stack.isEmpty()) {
                return true;
            }
        }
        return stack.isEmpty();
    }

    /** Walks slots in order: empty slots receive, identical non-full stacks are topped up. */
    public static void insert(Container container, ItemStack stack) {
        boolean changed = false;
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            int max = Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                if (!container.canPlaceItem(i, stack)) continue;
                int n = Math.min(stack.getCount(), max);
                container.setItem(i, stack.split(n));
                changed = true;
            } else if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < Math.min(max, existing.getMaxStackSize())) {
                int n = Math.min(stack.getCount(), Math.min(max, existing.getMaxStackSize()) - existing.getCount());
                existing.grow(n);
                stack.shrink(n);
                changed = true;
            }
        }
        if (changed) {
            container.setChanged();
        }
    }

    // ---------------------------------------------------------------- withdraw

    /** Removes one item from the first matching stack; empty if none. */
    public static ItemStack withdrawOne(ServerLevel level, Colony colony, Predicate<ItemStack> pred) {
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && pred.test(s)) {
                    ItemStack one = s.split(1);
                    c.setChanged();
                    decrementCounters(colony, one);
                    return one;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Takes up to n matching items from one specific storage. */
    public static ItemStack takeFrom(ServerLevel level, Colony colony, BlockPos pos, Predicate<ItemStack> pred, int n) {
        Container c = isUsable(level, pos) ? container(level, pos) : null;
        if (c == null || n <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack out = ItemStack.EMPTY;
        for (int i = 0; i < c.getContainerSize() && (out.isEmpty() || out.getCount() < n); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !pred.test(s)) continue;
            if (out.isEmpty()) {
                out = s.split(Math.min(n, s.getMaxStackSize()));
            } else if (ItemStack.isSameItemSameComponents(out, s)) {
                int k = Math.min(n - out.getCount(), Math.min(s.getCount(), out.getMaxStackSize() - out.getCount()));
                if (k <= 0) break;
                s.shrink(k);
                out.grow(k);
            }
        }
        if (!out.isEmpty()) {
            c.setChanged();
            decrementCounters(colony, out);
        }
        return out;
    }

    public static int count(ServerLevel level, Colony colony, Predicate<ItemStack> pred) {
        return count(usable(level, colony), pred);
    }

    public static int count(List<Slot> slots, Predicate<ItemStack> pred) {
        int total = 0;
        for (Slot slot : slots) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && pred.test(s)) {
                    total += s.getCount();
                }
            }
        }
        return total;
    }

    public static boolean has(ServerLevel level, Colony colony, Predicate<ItemStack> pred) {
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && pred.test(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes exactly n matching items; fails without side effects if fewer exist. n <= 0 succeeds. */
    public static boolean consume(ServerLevel level, Colony colony, Predicate<ItemStack> pred, int n) {
        return consume(level, colony, usable(level, colony), pred, n);
    }

    public static boolean consume(ServerLevel level, Colony colony, List<Slot> slots, Predicate<ItemStack> pred, int n) {
        if (n <= 0) {
            return true;
        }
        if (count(slots, pred) < n) {
            return false;
        }
        int remaining = n;
        for (Slot slot : slots) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !pred.test(s)) continue;
                int k = Math.min(remaining, s.getCount());
                decrementCounters(colony, s.copyWithCount(k));
                s.shrink(k);
                remaining -= k;
                c.setChanged();
            }
            if (remaining <= 0) break;
        }
        return true;
    }

    private record FoodSlot(Container container, int index, int value) {
    }

    /**
     * Consumes n nutrition points of food, cheapest food value first; requires total >= n.
     * Bowls of eaten stews and soups go back to storage.
     */
    public static boolean consumeNutrition(ServerLevel level, Colony colony, int n) {
        if (n <= 0) {
            return true;
        }
        List<FoodSlot> food = new ArrayList<>();
        int total = 0;
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                int value = foodValue(s);
                if (value <= 0) continue;
                food.add(new FoodSlot(c, i, value));
                total += value * s.getCount();
            }
        }
        if (total < n) {
            return false;
        }
        food.sort(Comparator.comparingInt(FoodSlot::value));
        int remaining = n;
        List<ItemStack> leftovers = new ArrayList<>();
        for (FoodSlot f : food) {
            if (remaining <= 0) break;
            ItemStack s = f.container().getItem(f.index());
            int items = Math.min(s.getCount(), (remaining + f.value() - 1) / f.value());
            ItemStack eaten = s.copyWithCount(items);
            decrementCounters(colony, eaten);
            s.shrink(items);
            remaining -= items * f.value();
            f.container().setChanged();
            ItemStack rest = eatRemainder(eaten, items);
            if (!rest.isEmpty()) leftovers.add(rest);
        }
        for (ItemStack rest : leftovers) {
            deposit(level, colony, rest);
        }
        return true;
    }

    /**
     * Atomic transaction: snapshot every usable inventory, run the action, then deposit the outputs.
     * Rolls everything back if the action fails or an output does not fit. Always recounts stock.
     */
    public static boolean transaction(ServerLevel level, Colony colony, BooleanSupplier action, List<ItemStack> outputs) {
        List<Slot> slots = usable(level, colony);
        List<ItemStack[]> snapshot = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            Container c = slot.container();
            ItemStack[] copy = new ItemStack[c.getContainerSize()];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = c.getItem(i).copy();
            }
            snapshot.add(copy);
        }
        boolean ok = action.getAsBoolean();
        if (ok) {
            for (ItemStack out : outputs) {
                ItemStack copy = out.copy();
                if (!deposit(level, colony, copy)) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) {
            for (int s = 0; s < slots.size(); s++) {
                Container c = slots.get(s).container();
                ItemStack[] copy = snapshot.get(s);
                for (int i = 0; i < copy.length && i < c.getContainerSize(); i++) {
                    c.setItem(i, copy[i]);
                }
                c.setChanged();
            }
        }
        recount(level, colony);
        return ok;
    }

    // ---------------------------------------------------------------- capacity

    /** How many items of this stack the storages can still accept. */
    public static int freeCapacity(ServerLevel level, Colony colony, ItemStack stack) {
        int free = 0;
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            int max = Math.min(c.getMaxStackSize(stack), stack.getMaxStackSize());
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) {
                    free += max;
                } else if (ItemStack.isSameItemSameComponents(s, stack)) {
                    free += Math.max(0, max - s.getCount());
                }
            }
        }
        return free;
    }

    /** Some storage has an empty slot or a non-full stack matching the predicate. */
    public static boolean hasRoomFor(ServerLevel level, Colony colony, Predicate<ItemStack> pred) {
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || (pred.test(s) && s.getCount() < s.getMaxStackSize())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int emptySlots(ServerLevel level, Colony colony) {
        int n = 0;
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- nearest

    public static BlockPos nearest(ServerLevel level, Colony colony, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : stockPositions(colony)) {
            if (!isUsable(level, pos) || container(level, pos) == null) continue;
            double d = pos.distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    /** Best category rank first, then distance. */
    public static BlockPos nearestPreferring(ServerLevel level, Colony colony, StorageKind kind, BlockPos from) {
        return nearestPreferring(level, colony, kind, from, p -> true);
    }

    public static BlockPos nearestPreferring(ServerLevel level, Colony colony, StorageKind kind, BlockPos from, Predicate<BlockPos> filter) {
        BlockPos best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : stockPositions(colony)) {
            if (!filter.test(pos) || !isUsable(level, pos) || container(level, pos) == null) continue;
            int rank = kindOf(colony, pos).rankFor(kind);
            double d = pos.distSqr(from);
            if (rank < bestRank || (rank == bestRank && d < bestD)) {
                bestRank = rank;
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    /** Nearest storage for a villager, preferring a kind and skipping storages it recently failed to reach. */
    public static BlockPos nearestFor(ServerLevel level, Colony colony, Villager villager, StorageKind kind) {
        return nearestPreferring(level, colony, kind, villager.blockPosition(),
                pos -> !net.betterthanvillage.ai.nav.Nav.isBlacklisted(villager, pos, level.getGameTime()));
    }

    public static BlockPos nearestContaining(ServerLevel level, Colony colony, BlockPos from, Predicate<ItemStack> pred) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Slot slot : usable(level, colony)) {
            Container c = slot.container();
            boolean found = false;
            for (int i = 0; i < c.getContainerSize() && !found; i++) {
                ItemStack s = c.getItem(i);
                found = !s.isEmpty() && pred.test(s);
            }
            if (!found) continue;
            double d = slot.pos().distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = slot.pos();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- counters

    /** All village stock: the treasury and every household's home chests. */
    public static List<Slot> villageStock(ServerLevel level, Colony colony) {
        List<Slot> out = slots(level, colony.storages);
        for (Household h : colony.households.values()) {
            out.addAll(slots(level, h.chests));
        }
        return out;
    }

    /** Recounts the village-wide stock counters (treasury and households, whatever the scope). */
    public static void recount(ServerLevel level, Colony colony) {
        int food = 0, logs = 0, stone = 0, saplings = 0, iron = 0, diamonds = 0;
        for (Slot slot : villageStock(level, colony)) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                int n = s.getCount();
                food += foodValue(s) * n;
                if (s.is(ItemTags.LOGS)) logs += n;
                if (s.is(ItemTags.STONE_CRAFTING_MATERIALS)) stone += n;
                if (s.is(ItemTags.SAPLINGS)) saplings += n;
                if (s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON)) iron += n;
                if (s.is(Items.DIAMOND)) diamonds += n;
            }
        }
        colony.cachedFood = food;
        colony.cachedLogs = logs;
        colony.cachedStone = stone;
        colony.cachedSaplings = saplings;
        colony.cachedIron = iron;
        colony.cachedDiamonds = diamonds;
    }

    public static void decrementCounters(Colony colony, ItemStack removed) {
        int n = removed.getCount();
        if (net.betterthanvillage.config.ColonyConfig.get().debug.log_food && foodValue(removed) > 0) {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder where = new StringBuilder();
            for (int i = 2; i < Math.min(st.length, 7); i++) where.append(st[i].getClassName().replaceAll(".*\\.", "")).append('.').append(st[i].getMethodName()).append('<');
            net.betterthanvillage.BetterThanVillage.LOGGER.info("[food] {} x{} scope={} via {}", removed.getItem(), n,
                    net.betterthanvillage.economy.StockScope.household(colony) != null ? "household" : "treasury", where);
        }
        colony.cachedFood = Math.max(0, colony.cachedFood - foodValue(removed) * n);
        if (removed.is(ItemTags.LOGS)) colony.cachedLogs = Math.max(0, colony.cachedLogs - n);
        if (removed.is(ItemTags.STONE_CRAFTING_MATERIALS)) colony.cachedStone = Math.max(0, colony.cachedStone - n);
        if (removed.is(ItemTags.SAPLINGS)) colony.cachedSaplings = Math.max(0, colony.cachedSaplings - n);
        if (removed.is(Items.IRON_INGOT) || removed.is(Items.RAW_IRON)) colony.cachedIron = Math.max(0, colony.cachedIron - n);
        if (removed.is(Items.DIAMOND)) colony.cachedDiamonds = Math.max(0, colony.cachedDiamonds - n);
    }

    /** Food carried by member villagers (same formula as storage food). */
    public static int carriedFood(List<Villager> members) {
        int total = 0;
        for (Villager v : members) {
            total += foodValue(v.getInventory());
        }
        return total;
    }
}
