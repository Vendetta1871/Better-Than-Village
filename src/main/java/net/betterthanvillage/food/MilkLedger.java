package net.betterthanvillage.food;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Colony milk accounting with the cap of 4 milk buckets (04 §4.10). The cap only stops milking and
 * pickup; stored or carried milk is never converted or deleted.
 */
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
            if (ColonyManager.colonyOf(level, v) != colony) continue;
            total += FoodUtil.count(v.getInventory(), s -> s.is(Items.MILK_BUCKET));
            if (total >= CAP) return total;
        }
        return total;
    }
}
