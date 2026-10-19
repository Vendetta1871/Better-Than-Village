package net.betterthanvillage.food;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Inventory and storage helpers shared by the food jobs. */
public final class FoodUtil {
    private FoodUtil() {
    }

    /** Total count of matching items in the villager inventory (main hand excluded). */
    public static int count(Container inv, Predicate<ItemStack> pred) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        }
        return n;
    }

    public static boolean carries(Container inv, Predicate<ItemStack> pred) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) return true;
        }
        return false;
    }

    /** Removes one matching item from the first matching inventory slot. */
    public static boolean removeOne(Container inv, Predicate<ItemStack> pred) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) {
                s.shrink(1);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    /** Adds to the inventory, dropping any overflow at the villager. Returns true if everything fit. */
    public static boolean give(ServerLevel level, Villager v, ItemStack stack) {
        if (stack.isEmpty()) return true;
        ItemStack rest = v.getInventory().addItem(stack);
        if (rest.isEmpty()) return true;
        drop(level, v, rest);
        return false;
    }

    public static void drop(ServerLevel level, Villager v, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEntity item = new ItemEntity(level, v.getX(), v.getY() + 0.5, v.getZ(), stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }

    /** Room for the stack: an empty slot or a non-full identical stack. */
    public static boolean hasRoom(SimpleContainer inv, ItemStack stack) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Deposit with statistics ("delivery to storage"). The stack is shrunk in place. */
    public static int deposit(ServerLevel level, Colony colony, Villager v, ItemStack stack) {
        return WorkTracker.deliver(level, colony, v, stack);
    }

    /** Takes up to n matching items from the first matching slot of one container. */
    public static ItemStack takeFirstSlot(ServerLevel level, Colony colony, BlockPos pos, Predicate<ItemStack> pred, int n) {
        if (n <= 0 || !ColonyStorage.isUsable(level, pos)) return ItemStack.EMPTY;
        Container c = ColonyStorage.container(level, pos);
        if (c == null) return ItemStack.EMPTY;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !pred.test(s)) continue;
            ItemStack out = s.split(Math.min(n, s.getCount()));
            c.setChanged();
            ColonyStorage.decrementCounters(colony, out);
            return out;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Takes one matching item from storage into the inventory. Returns false if nothing was taken;
     * an item without room goes back to storage (dropped only if that fails too).
     */
    public static boolean takeOne(ServerLevel level, Colony colony, Villager v, Predicate<ItemStack> pred) {
        ItemStack one = ColonyStorage.withdrawOne(level, colony, pred);
        if (one.isEmpty()) return false;
        ItemStack rest = v.getInventory().addItem(one);
        if (rest.isEmpty()) return true;
        ColonyStorage.deposit(level, colony, rest);
        drop(level, v, rest);
        return false;
    }

    /** Road materials: dirt path and smooth sandstone. */
    public static boolean isRoadBlock(BlockState state) {
        return state.is(Blocks.DIRT_PATH) || state.is(Blocks.SMOOTH_SANDSTONE);
    }
}
