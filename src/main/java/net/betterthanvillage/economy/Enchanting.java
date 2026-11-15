package net.betterthanvillage.economy;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Optional;

/**
 * The librarian's enchanting (docs_v2/02-professions.md §Librarian): a book and lapis become an enchanted book with
 * one enchantment of the vanilla librarian pool (the tradeable tag, Mending included) at a random level. Enchanted
 * books are not a market family: each one is sold to the player as it is, at the vanilla librarian price.
 */
public final class Enchanting {
    /** Lapis one enchanting takes (the enchanting table's top slot). */
    public static final int LAPIS = 3;
    /** Enchanted books a librarian keeps in stock; it stops enchanting at this many. */
    public static final int STOCK = 3;

    private Enchanting() {
    }

    /** A new enchanted book: a random enchantment of the librarian pool at a random level (a plain book if the pool is empty). */
    public static ItemStack book(ServerLevel level, RandomSource random) {
        Optional<Holder<Enchantment>> pick = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getRandomElementOf(EnchantmentTags.TRADEABLE, random);
        if (pick.isEmpty()) return new ItemStack(Items.BOOK);
        Enchantment e = pick.get().value();
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(pick.get(), Mth.nextInt(random, e.getMinLevel(), e.getMaxLevel())));
    }

    /** An enchanted book with at least one stored enchantment. */
    public static boolean isBook(ItemStack stack) {
        if (!stack.is(Items.ENCHANTED_BOOK)) return false;
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        return stored != null && !stored.isEmpty();
    }

    /**
     * Value of an enchanted book in emeralds: the mean of the vanilla librarian price per enchantment
     * (2 + nextInt(5 + 10 l) + 3 l, doubled for Mending-like enchantments, at most 64).
     */
    public static double value(ItemStack stack) {
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null) return 0;
        double total = 0;
        for (var entry : stored.entrySet()) {
            int l = entry.getIntValue();
            double v = 2 + (4 + 10.0 * l) / 2 + 3 * l;
            if (entry.getKey().is(EnchantmentTags.DOUBLE_TRADE_PRICE)) v *= 2;
            total += Math.min(64, v);
        }
        return total;
    }

    /** Short description for debug output: "mending 1, unbreaking 3". */
    public static String describe(ItemStack stack) {
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) return "book";
        StringBuilder sb = new StringBuilder();
        for (var entry : stored.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey().unwrapKey().map(k -> k.location().getPath()).orElse("?")).append(' ').append(entry.getIntValue());
        }
        return sb.toString();
    }
}
