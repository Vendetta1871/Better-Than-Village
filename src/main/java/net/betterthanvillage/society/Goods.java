package net.betterthanvillage.society;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/** Item predicates shared by the market, player trading and catch-up code. */
final class Goods {
    static final Predicate<ItemStack> EMERALD = s -> s.is(Items.EMERALD);
    static final Predicate<ItemStack> COBBLE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
    static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    static final Predicate<ItemStack> STONE = s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS);
    static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    static final Predicate<ItemStack> SAPLINGS = s -> s.is(ItemTags.SAPLINGS);
    static final Predicate<ItemStack> ROOTS = s -> s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT);
    static final Predicate<ItemStack> WOOL = s -> path(s.getItem()).endsWith("_wool");
    static final Predicate<ItemStack> SEEDS = s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.PUMPKIN_SEEDS)
            || s.is(Items.CARROT) || s.is(Items.POTATO);
    static final Predicate<ItemStack> FUEL_COAL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
    static final Predicate<ItemStack> PUMPKINS = s -> s.is(Items.PUMPKIN) || s.is(Items.CARVED_PUMPKIN);
    static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON);

    private Goods() {
    }

    static Predicate<ItemStack> item(Item item) {
        return s -> s.is(item);
    }

    static Predicate<ItemStack> anyOf(Item... items) {
        return s -> {
            for (Item item : items) {
                if (s.is(item)) return true;
            }
            return false;
        };
    }

    /** Exact item with default components (no patch). */
    static Predicate<ItemStack> exact(Item item) {
        return s -> s.is(item) && s.getComponentsPatch().isEmpty();
    }

    static String path(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
