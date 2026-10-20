package net.betterthanvillage.economy;

import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Workshop recipes of the processing trades (docs_v2/02-professions.md): what a carpenter, smith, librarian or
 * healer makes from the inputs in its home chest. A recipe runs while the household holds fewer of its product
 * than the target, so the surplus is what the household sells.
 */
public final class Workshop {
    public record Input(Predicate<ItemStack> pred, int count) {
    }

    /**
     * One recipe; the output is computed from the consumed stacks (planks follow the log's wood type).
     * product tells which held items count against the target.
     */
    public record Recipe(String name, List<Input> inputs, Function<List<ItemStack>, ItemStack> output, Predicate<ItemStack> product, int target) {
    }

    private static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
    private static final Predicate<ItemStack> COBBLE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
    private static final Predicate<ItemStack> FUEL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
    private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);

    private Workshop() {
    }

    private static Input in(Predicate<ItemStack> pred, int count) {
        return new Input(pred, count);
    }

    private static Input in(Item item, int count) {
        return new Input(s -> s.is(item), count);
    }

    private static Function<List<ItemStack>, ItemStack> out(Item item, int count) {
        return used -> new ItemStack(item, count);
    }

    private static Recipe r(String name, Function<List<ItemStack>, ItemStack> output, Predicate<ItemStack> product, int target, Input... inputs) {
        return new Recipe(name, List.of(inputs), output, product, target);
    }

    private static Recipe r(String name, Item item, int count, int target, Input... inputs) {
        return r(name, out(item, count), s -> s.is(item), target, inputs);
    }

    /** Planks of the consumed log's wood ("oak_log" -> "oak_planks"), oak if unknown. */
    private static ItemStack planksFor(List<ItemStack> used) {
        for (ItemStack s : used) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
            String path = key.getPath().replace("stripped_", "").replace("_log", "_planks").replace("_wood", "_planks")
                    .replace("_stem", "_planks").replace("_hyphae", "_planks");
            ResourceLocation planks = ResourceLocation.fromNamespaceAndPath(key.getNamespace(), path);
            if (BuiltInRegistries.ITEM.containsKey(planks)) return new ItemStack(BuiltInRegistries.ITEM.get(planks), 4);
        }
        return new ItemStack(Items.OAK_PLANKS, 4);
    }

    /** Recipes of a trade, in priority order. */
    public static List<Recipe> recipes(ColonyRole role) {
        List<Recipe> list = new ArrayList<>();
        switch (role) {
            case CARPENTER -> {
                list.add(r("planks", Workshop::planksFor, PLANKS, 12, in(LOGS, 1)));
                list.add(r("sticks", Items.STICK, 4, 16, in(PLANKS, 2)));
                list.add(r("fence", Items.OAK_FENCE, 3, 9, in(PLANKS, 4), in(STICK, 2)));
                list.add(r("chest", Items.CHEST, 1, 2, in(PLANKS, 8)));
                list.add(r("bed", Items.WHITE_BED, 1, 2, in(PLANKS, 3), in(s -> s.is(ItemTags.WOOL), 3)));
                list.add(r("bow", Items.BOW, 1, 2, in(STICK, 3), in(Items.STRING, 3)));
                list.add(r("arrows", Items.ARROW, 4, 32, in(Items.FLINT, 1), in(STICK, 1), in(Items.FEATHER, 1)));
            }
            case SMITH -> {
                list.add(r("smelt iron", Items.IRON_INGOT, 4, 24, in(Items.RAW_IRON, 4), in(FUEL, 1)));
                list.add(r("smelt copper", Items.COPPER_INGOT, 4, 16, in(Items.RAW_COPPER, 4), in(FUEL, 1)));
                list.add(r("smelt gold", Items.GOLD_INGOT, 4, 8, in(Items.RAW_GOLD, 4), in(FUEL, 1)));
                list.add(r("glass", Items.GLASS, 4, 16, in(Items.SAND, 4), in(FUEL, 1)));
                list.add(r("stone pickaxe", Items.STONE_PICKAXE, 1, 2, in(COBBLE, 3), in(STICK, 2)));
                list.add(r("stone axe", Items.STONE_AXE, 1, 2, in(COBBLE, 3), in(STICK, 2)));
                list.add(r("stone hoe", Items.STONE_HOE, 1, 1, in(COBBLE, 2), in(STICK, 2)));
                list.add(r("stone sword", Items.STONE_SWORD, 1, 1, in(COBBLE, 2), in(STICK, 1)));
                list.add(r("shears", Items.SHEARS, 1, 1, in(IRON, 2)));
                list.add(r("bucket", Items.BUCKET, 1, 1, in(IRON, 3)));
                list.add(r("iron pickaxe", Items.IRON_PICKAXE, 1, 1, in(IRON, 3), in(STICK, 2)));
                list.add(r("iron axe", Items.IRON_AXE, 1, 1, in(IRON, 3), in(STICK, 2)));
                list.add(r("iron sword", Items.IRON_SWORD, 1, 1, in(IRON, 2), in(STICK, 1)));
            }
            case LIBRARIAN -> {
                list.add(r("paper", Items.PAPER, 3, 24, in(Items.SUGAR_CANE, 3)));
                list.add(r("book", Items.BOOK, 1, 6, in(Items.PAPER, 3), in(Items.LEATHER, 1)));
            }
            case HEALER -> {
                list.add(r("bottles", Items.GLASS_BOTTLE, 3, 6, in(Items.GLASS, 3)));
                list.add(r("nuggets", Items.GOLD_NUGGET, 9, 9, in(Items.GOLD_INGOT, 1)));
                list.add(r("glistering melon", Items.GLISTERING_MELON_SLICE, 1, 2, in(Items.MELON_SLICE, 1), in(Items.GOLD_NUGGET, 8)));
                // Brewed without Nether ingredients (deliberate simplification).
                list.add(r("healing potions", used -> PotionContents.createItemStack(Items.POTION, Potions.HEALING).copyWithCount(3),
                        s -> s.is(Items.POTION), 6, in(Items.GLASS_BOTTLE, 3), in(Items.GLISTERING_MELON_SLICE, 1)));
            }
            default -> {
            }
        }
        return list;
    }

    /** The first recipe the chests can run now (inputs present, product under target), or null. */
    public static Recipe ready(List<Recipe> recipes, List<ColonyStorage.Slot> slots) {
        for (Recipe r : recipes) {
            if (Chests.count(slots, r.product()) >= r.target()) continue;
            boolean ok = true;
            for (Input in : r.inputs()) {
                if (Chests.count(slots, in.pred()) < in.count()) {
                    ok = false;
                    break;
                }
            }
            if (ok) return r;
        }
        return null;
    }

    /** Consumes the inputs and returns the product (empty if an input went missing; nothing is consumed then). */
    public static ItemStack craft(Recipe r, List<ColonyStorage.Slot> slots) {
        for (Input in : r.inputs()) {
            if (Chests.count(slots, in.pred()) < in.count()) return ItemStack.EMPTY;
        }
        List<ItemStack> used = new ArrayList<>();
        for (Input in : r.inputs()) {
            used.addAll(Chests.take(slots, in.pred(), in.count()));
        }
        return r.output().apply(used);
    }
}
