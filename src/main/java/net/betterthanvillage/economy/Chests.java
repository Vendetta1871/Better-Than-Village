package net.betterthanvillage.economy;

import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Item moves between explicit lists of containers (a household's home chests or the treasury). */
public final class Chests {
    private Chests() {
    }

    public static int count(List<ColonyStorage.Slot> slots, Predicate<ItemStack> pred) {
        return ColonyStorage.count(slots, pred);
    }

    /** How many items like this stack the containers can still take. */
    public static int room(List<ColonyStorage.Slot> slots, ItemStack like) {
        int free = 0;
        for (ColonyStorage.Slot slot : slots) {
            Container c = slot.container();
            int max = Math.min(c.getMaxStackSize(like), like.getMaxStackSize());
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) free += max;
                else if (ItemStack.isSameItemSameComponents(s, like)) free += Math.max(0, max - s.getCount());
            }
        }
        return free;
    }

    /** Removes exactly n matching items (as stacks); empty list and no change if fewer exist. */
    public static List<ItemStack> take(List<ColonyStorage.Slot> slots, Predicate<ItemStack> pred, int n) {
        List<ItemStack> out = new ArrayList<>();
        if (n <= 0 || count(slots, pred) < n) return out;
        int remaining = n;
        for (ColonyStorage.Slot slot : slots) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !pred.test(s)) continue;
                int k = Math.min(remaining, s.getCount());
                out.add(s.split(k));
                remaining -= k;
                c.setChanged();
            }
            if (remaining <= 0) break;
        }
        return out;
    }

    /** Inserts the stacks; returns what did not fit (empty when everything fit). */
    public static List<ItemStack> put(List<ColonyStorage.Slot> slots, List<ItemStack> stacks) {
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack s = stack.copy();
            for (ColonyStorage.Slot slot : slots) {
                if (s.isEmpty()) break;
                ColonyStorage.insert(slot.container(), s);
            }
            if (!s.isEmpty()) rest.add(s);
        }
        return rest;
    }

    /** True if every stack fits (stacks of different kinds are checked independently, which is optimistic). */
    public static boolean fits(List<ColonyStorage.Slot> slots, List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (room(slots, s) < s.getCount()) return false;
        }
        return true;
    }

    public static int total(List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack s : stacks) n += s.getCount();
        return n;
    }
}
