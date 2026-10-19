package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/** Colony guard gear stock for guard trading (02 §3.7, 06 §1.5). */
public final class GuardGear {
    private static final List<Entry> TABLE = List.of(
            new Entry(Items.WOODEN_SWORD, 2, Items.AIR, 0, 1),
            new Entry(Items.STONE_SWORD, 4, Items.COBBLESTONE, 2, 1),
            new Entry(Items.IRON_SWORD, 8, Items.IRON_INGOT, 2, 1),
            new Entry(Items.DIAMOND_SWORD, 18, Items.DIAMOND, 2, 1),
            new Entry(Items.BOW, 6, Items.STRING, 3, 1),
            new Entry(Items.SHIELD, 6, Items.IRON_INGOT, 1, 2),
            new Entry(Items.IRON_HELMET, 10, Items.IRON_INGOT, 5, 0),
            new Entry(Items.IRON_CHESTPLATE, 16, Items.IRON_INGOT, 8, 0),
            new Entry(Items.IRON_LEGGINGS, 14, Items.IRON_INGOT, 7, 0),
            new Entry(Items.IRON_BOOTS, 8, Items.IRON_INGOT, 4, 0),
            new Entry(Items.DIAMOND_HELMET, 24, Items.DIAMOND, 5, 0),
            new Entry(Items.DIAMOND_CHESTPLATE, 40, Items.DIAMOND, 8, 0),
            new Entry(Items.DIAMOND_LEGGINGS, 34, Items.DIAMOND, 7, 0),
            new Entry(Items.DIAMOND_BOOTS, 20, Items.DIAMOND, 4, 0));

    private GuardGear() {
    }

    /** One equipment entry: item, base sell price, material (AIR = none), material count, logs count. */
    public record Entry(Item item, int price, Item material, int materialCount, int logs) {
    }

    /** The equipment table in offer order. */
    public static List<Entry> table() {
        return TABLE;
    }

    /** Tops each equipment item up to 2 copies in storage when materials allow (before guard offers are built). */
    public static void ensureStock(ServerLevel level, Colony colony) {
        int golemsNear = golemsNear(level, colony);
        Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
        for (Entry e : TABLE) {
            ItemStack plain = new ItemStack(e.item());
            int missing = 2 - ColonyStorage.count(level, colony, s -> ItemStack.isSameItemSameComponents(s, plain));
            if (missing <= 0) continue;
            int matNeed = e.materialCount() * missing;
            int logNeed = e.logs() * missing;
            Predicate<ItemStack> mat = s -> s.is(e.material());
            if (e.materialCount() != 0 && ColonyStorage.count(level, colony, mat) < reserve(e.material(), golemsNear) + matNeed) continue;
            if (e.logs() != 0 && ColonyStorage.count(level, colony, logs) < 48 + logNeed) continue;
            ColonyStorage.transaction(level, colony,
                    () -> ColonyStorage.consume(level, colony, mat, matNeed) && ColonyStorage.consume(level, colony, logs, logNeed),
                    List.of(new ItemStack(e.item(), missing)));
        }
    }

    private static int golemsNear(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        double r2 = (double) colony.radius() * colony.radius();
        return level.getEntitiesOfClass(IronGolem.class, new AABB(core).inflate(colony.radius()),
                g -> g.isAlive() && g.blockPosition().distSqr(core) <= r2).size();
    }

    /** Iron ingots kept back for golem building (guard gear never dips below it). */
    public static int ironReserve(ServerLevel level, Colony colony) {
        return reserve(Items.IRON_INGOT, golemsNear(level, colony));
    }

    private static int reserve(Item material, int golemsNear) {
        if (material == Items.IRON_INGOT) return Math.max(36, (4 - golemsNear) * 36);
        if (material == Items.DIAMOND) return 4;
        if (material == Items.COBBLESTONE) return 64;
        return 2;
    }
}
