package net.betterthanvillage.food;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Colony milk accounting with the cap of 4 milk buckets (04 §4.10). */
public final class MilkLedger {
    public static final int CAP = 4;

    private MilkLedger() {
    }

    public static int storageMilk(ServerLevel level, Colony colony) {
        return ColonyStorage.count(level, colony, s -> s.is(Items.MILK_BUCKET));
    }

    /** Storage milk plus milk carried by member villagers in the colony box, early-out at the cap. */
    public static int colonyMilk(ServerLevel level, Colony colony) {
        int total = storageMilk(level, colony);
        if (total >= CAP) return total;
        BlockPos c = colony.core();
        int r = colony.radius();
        AABB box = new AABB(c.getX() - r, level.getMinBuildHeight(), c.getZ() - r, c.getX() + r + 1, level.getMaxBuildHeight(), c.getZ() + r + 1);
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            if (ColonyManager.colonyAt(level, v.blockPosition()) != colony) continue;
            total += FoodUtil.count(v.getInventory(), s -> s.is(Items.MILK_BUCKET));
            if (total >= CAP) return total;
        }
        return total;
    }

    /** Keeps the first {@code keep} milk buckets; later excess becomes empty buckets as far as there is room. */
    public static void convert(Container inv, int keep) {
        int kept = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.MILK_BUCKET)) continue;
            int keepHere = Math.min(Math.max(0, keep - kept), s.getCount());
            kept += keepHere;
            int excess = s.getCount() - keepHere;
            if (excess <= 0) continue;
            int room = 0;
            int max = Items.BUCKET.getDefaultMaxStackSize();
            for (int j = 0; j < inv.getContainerSize(); j++) {
                ItemStack t = inv.getItem(j);
                if ((j == i && keepHere == 0) || t.isEmpty()) room += max;
                else if (t.is(Items.BUCKET)) room += Math.max(0, max - t.getCount());
            }
            int n = Math.min(excess, room);
            if (n <= 0) continue;
            s.shrink(n);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            for (int j = 0; j < inv.getContainerSize() && n > 0; j++) {
                ItemStack t = inv.getItem(j);
                if (t.is(Items.BUCKET) && t.getCount() < max) {
                    int k = Math.min(n, max - t.getCount());
                    t.grow(k);
                    n -= k;
                }
            }
            for (int j = 0; j < inv.getContainerSize() && n > 0; j++) {
                if (inv.getItem(j).isEmpty()) {
                    int k = Math.min(n, max);
                    inv.setItem(j, new ItemStack(Items.BUCKET, k));
                    n -= k;
                }
            }
            inv.setChanged();
        }
    }

    /** Storage trim: each inventory converted with the remaining allowance, storage-list order. */
    public static void trimStorage(ServerLevel level, Colony colony) {
        int allowance = CAP;
        for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
            convert(slot.container(), allowance);
            int left = 0;
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(Items.MILK_BUCKET)) left += c.getItem(i).getCount();
            }
            allowance = Math.max(0, allowance - left);
        }
    }
}
