package net.betterthanvillage.economy;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Base values of the goods villagers trade (docs_v2/03-economy.md §Prices), in emeralds per item, and their
 * families. A family groups interchangeable goods: a villager who needs food buys whatever food is cheapest per
 * nutrition, a miner who needs a pickaxe buys any pickaxe. Food is measured in nutrition, everything else in items.
 */
public final class Values {
    /**
     * All base values are multiplied by this: with a few dozen emeralds per household, everyday goods must be worth
     * enough that a day's purchase is a whole emerald or two.
     */
    public static final double SCALE = 3.0;
    /** Emeralds per point of nutrition (before SCALE). */
    public static final double FOOD_UNIT = 0.05;
    /** Edible items villagers never trade as food (valuable or harmful). */
    private static final Set<Item> NOT_FOOD = Set.of(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.ROTTEN_FLESH,
            Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);

    public enum Family {
        FOOD(FOOD_UNIT), WHEAT(0.06), SEEDS(0.02), SUGAR_CANE(0.05), PUMPKIN(0.25), EGG(0.05),
        LOGS(0.125), PLANKS(0.03), STICKS(0.015), SAPLINGS(0.05),
        COBBLE(0.03), STONE(0.04), SAND(0.02), GRAVEL(0.02), FLINT(0.05), GLASS(0.08),
        COAL(0.12), RAW_IRON(0.6), IRON(1.0), RAW_COPPER(0.15), COPPER(0.25), RAW_GOLD(0.8), GOLD(1.0),
        DIAMOND(8.0), LAPIS(0.3), REDSTONE(0.2),
        WOOL(0.25), LEATHER(0.25), FEATHER(0.05), STRING(0.05), BONE_MEAL(0.03),
        TORCH(0.05), ARROW(0.05), PAPER(0.1), BOOK(0.6),
        PICKAXE(0.6), AXE(0.6), HOE(0.6), SHOVEL(0.5), SWORD(0.8), SHEARS(2.2), BUCKET(3.0), FISHING_ROD(0.6), BOW(1.0),
        FENCE(0.05), CHEST(0.3), BED(1.0), GLASS_BOTTLE(0.1), GOLD_NUGGET(0.1), GLISTERING_MELON(1.0), POTION(1.5);

        /** Value of one family unit of the plainest kind (one nutrition point for food). */
        public final double unit;

        Family(double unit) {
            this.unit = unit * SCALE;
        }
    }

    private record Entry(Family family, double value) {
    }

    private static final Map<Item, Entry> TABLE = new HashMap<>();

    static {
        put(Family.WHEAT, 0.06, Items.WHEAT);
        put(Family.SEEDS, 0.02, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.PUMPKIN_SEEDS, Items.MELON_SEEDS);
        put(Family.SUGAR_CANE, 0.05, Items.SUGAR_CANE);
        put(Family.PUMPKIN, 0.25, Items.PUMPKIN, Items.CARVED_PUMPKIN);
        put(Family.EGG, 0.05, Items.EGG);
        put(Family.STICKS, 0.015, Items.STICK);
        put(Family.COBBLE, 0.03, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
        put(Family.STONE, 0.04, Items.STONE, Items.STONE_BRICKS, Items.DEEPSLATE);
        put(Family.SAND, 0.02, Items.SAND);
        put(Family.GRAVEL, 0.02, Items.GRAVEL);
        put(Family.FLINT, 0.05, Items.FLINT);
        put(Family.GLASS, 0.08, Items.GLASS);
        put(Family.COAL, 0.12, Items.COAL, Items.CHARCOAL);
        put(Family.RAW_IRON, 0.6, Items.RAW_IRON);
        put(Family.IRON, 1.0, Items.IRON_INGOT);
        put(Family.RAW_COPPER, 0.15, Items.RAW_COPPER);
        put(Family.COPPER, 0.25, Items.COPPER_INGOT);
        put(Family.RAW_GOLD, 0.8, Items.RAW_GOLD);
        put(Family.GOLD, 1.0, Items.GOLD_INGOT);
        put(Family.DIAMOND, 8.0, Items.DIAMOND);
        put(Family.LAPIS, 0.3, Items.LAPIS_LAZULI);
        put(Family.REDSTONE, 0.2, Items.REDSTONE);
        put(Family.LEATHER, 0.25, Items.LEATHER, Items.RABBIT_HIDE);
        put(Family.FEATHER, 0.05, Items.FEATHER);
        put(Family.STRING, 0.05, Items.STRING);
        put(Family.BONE_MEAL, 0.03, Items.BONE_MEAL);
        put(Family.TORCH, 0.05, Items.TORCH);
        put(Family.ARROW, 0.05, Items.ARROW);
        put(Family.PAPER, 0.1, Items.PAPER);
        put(Family.BOOK, 0.6, Items.BOOK);
        put(Family.PICKAXE, 0.3, Items.WOODEN_PICKAXE);
        put(Family.PICKAXE, 0.6, Items.STONE_PICKAXE);
        put(Family.PICKAXE, 4.0, Items.IRON_PICKAXE);
        put(Family.AXE, 0.3, Items.WOODEN_AXE);
        put(Family.AXE, 0.6, Items.STONE_AXE);
        put(Family.AXE, 4.0, Items.IRON_AXE);
        put(Family.HOE, 0.3, Items.WOODEN_HOE);
        put(Family.HOE, 0.6, Items.STONE_HOE);
        put(Family.HOE, 2.5, Items.IRON_HOE);
        put(Family.SHOVEL, 0.3, Items.WOODEN_SHOVEL);
        put(Family.SHOVEL, 0.5, Items.STONE_SHOVEL);
        put(Family.SHOVEL, 1.5, Items.IRON_SHOVEL);
        put(Family.SWORD, 0.8, Items.STONE_SWORD);
        put(Family.SWORD, 3.0, Items.IRON_SWORD);
        put(Family.SHEARS, 2.2, Items.SHEARS);
        put(Family.BUCKET, 3.0, Items.BUCKET);
        put(Family.FISHING_ROD, 0.6, Items.FISHING_ROD);
        put(Family.BOW, 1.0, Items.BOW);
        put(Family.FENCE, 0.05, Items.OAK_FENCE, Items.SPRUCE_FENCE, Items.BIRCH_FENCE, Items.JUNGLE_FENCE, Items.ACACIA_FENCE,
                Items.DARK_OAK_FENCE, Items.MANGROVE_FENCE, Items.CHERRY_FENCE);
        put(Family.CHEST, 0.3, Items.CHEST);
        put(Family.GLASS_BOTTLE, 0.1, Items.GLASS_BOTTLE);
        put(Family.GOLD_NUGGET, 0.1, Items.GOLD_NUGGET);
        put(Family.GLISTERING_MELON, 1.0, Items.GLISTERING_MELON_SLICE);
        put(Family.POTION, 1.5, Items.POTION, Items.SPLASH_POTION);
    }

    private Values() {
    }

    private static void put(Family family, double value, Item... items) {
        for (Item item : items) {
            TABLE.put(item, new Entry(family, value * SCALE));
        }
    }

    /** Nutrition of a tradable food item, else 0. */
    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null || NOT_FOOD.contains(stack.getItem()) ? 0 : Math.max(1, food.nutrition());
    }

    /** The family of a tradable stack, or null. Damaged tools and items with custom components are not traded. */
    public static Family family(ItemStack stack) {
        if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted() || stack.has(DataComponents.CUSTOM_NAME)) return null;
        Entry e = TABLE.get(stack.getItem());
        if (e != null) return e.family();
        if (nutrition(stack) > 0) return Family.FOOD;
        if (stack.is(ItemTags.LOGS)) return Family.LOGS;
        if (stack.is(ItemTags.PLANKS)) return Family.PLANKS;
        if (stack.is(ItemTags.SAPLINGS)) return Family.SAPLINGS;
        if (stack.is(ItemTags.WOOL)) return Family.WOOL;
        if (stack.is(ItemTags.BEDS)) return Family.BED;
        return null;
    }

    /** Units of its family one item stands for (nutrition for food, else 1). */
    public static int units(ItemStack stack, Family family) {
        return family == Family.FOOD ? nutrition(stack) : 1;
    }

    /** Base value of one item in emeralds (0 if not tradable). */
    public static double base(ItemStack stack) {
        Family family = family(stack);
        if (family == null) return 0;
        Entry e = TABLE.get(stack.getItem());
        if (e != null) return e.value();
        return family.unit * units(stack, family);
    }

    public static boolean tradable(ItemStack stack) {
        return family(stack) != null;
    }

    public static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
