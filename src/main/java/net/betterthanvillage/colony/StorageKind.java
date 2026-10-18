package net.betterthanvillage.colony;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** Storage categories and the item classification used when depositing. */
public enum StorageKind {
    GENERAL("общий"),
    FOOD("еда и урожай"),
    WOOD("древесина"),
    MINING("камень и руда"),
    ANIMAL("животноводство");

    private static final StorageKind[] HOUSE_CYCLE = {FOOD, WOOD, MINING, ANIMAL, GENERAL};

    public final String displayRu;

    StorageKind(String displayRu) {
        this.displayRu = displayRu;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static StorageKind byName(String name) {
        if (name != null) {
            for (StorageKind kind : values()) {
                if (kind.serializedName().equalsIgnoreCase(name)) {
                    return kind;
                }
            }
        }
        return GENERAL;
    }

    public static StorageKind forHouse(int index) {
        return HOUSE_CYCLE[Math.floorMod(index, HOUSE_CYCLE.length)];
    }

    /** Classifies an item stack, first match wins. */
    public static StorageKind classify(ItemStack stack) {
        if (stack.isEmpty()) {
            return GENERAL;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (stack.is(Items.EGG) || stack.is(Items.MILK_BUCKET) || stack.is(Items.LEATHER) || stack.is(Items.FEATHER)
                || stack.is(Items.RABBIT_HIDE) || path.endsWith("_wool")) {
            return ANIMAL;
        }
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD) || stack.is(Items.WHEAT) || stack.is(Items.HAY_BLOCK) || path.endsWith("_seeds")) {
            return FOOD;
        }
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK)
                || stack.is(Items.CHARCOAL)) {
            return WOOD;
        }
        if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || path.contains("_ore") || path.startsWith("raw_") || path.endsWith("_ingot")
                || path.endsWith("_nugget") || stack.is(Items.COAL) || stack.is(Items.DIAMOND) || stack.is(Items.EMERALD)
                || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.FLINT)) {
            return MINING;
        }
        return GENERAL;
    }

    /** Preference rank of a storage of this kind for an item of the given class. */
    public int rankFor(StorageKind itemKind) {
        if (this == itemKind) {
            return 0;
        }
        return this == GENERAL ? 1 : 2;
    }
}
