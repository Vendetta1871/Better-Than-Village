package net.betterthanvillage.ai.work;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Tool acquisition from storage or crafting from storage materials, and storage smelting. */
public final class ToolSupply {
    public enum Kind {
        PICKAXE(ItemTags.PICKAXES, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE, 3),
        AXE(ItemTags.AXES, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE, 3),
        HOE(ItemTags.HOES, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE, 2);

        public final TagKey<Item> tag;
        public final Item wood, stone, iron, diamond, netherite;
        public final int cost;

        Kind(TagKey<Item> tag, Item wood, Item stone, Item iron, Item diamond, Item netherite, int cost) {
            this.tag = tag;
            this.wood = wood;
            this.stone = stone;
            this.iron = iron;
            this.diamond = diamond;
            this.netherite = netherite;
            this.cost = cost;
        }

        public int score(ItemStack stack) {
            if (stack.is(diamond) || stack.is(netherite)) return 4;
            if (stack.is(iron)) return 3;
            if (stack.is(stone)) return 2;
            if (stack.is(wood)) return 1;
            return 0;
        }
    }

    private ToolSupply() {
    }

    /** Upgrades the held tool if possible. Returns true when a new tool was equipped. */
    public static boolean ensureTool(ServerLevel level, Colony colony, Villager v, Kind kind) {
        ItemStack held = v.getMainHandItem();
        int score = kind.score(held);
        if (score >= 4) return false;
        ItemStack tool = ColonyStorage.withdrawOne(level, colony, s -> s.is(kind.tag) && kind.score(s) > score);
        if (tool.isEmpty()) {
            tool = craft(level, colony, kind, score);
        }
        if (tool.isEmpty()) return false;
        ItemStack old = v.getMainHandItem().copy();
        v.setItemSlot(EquipmentSlot.MAINHAND, tool);
        stash(level, colony, v, old);
        return true;
    }

    private static ItemStack craft(ServerLevel level, Colony colony, Kind kind, int score) {
        int cost = kind.cost;
        if (ColonyStorage.count(level, colony, s -> s.is(Items.DIAMOND)) >= cost && ColonyStorage.count(level, colony, s -> s.is(ItemTags.LOGS)) >= 1) {
            if (ColonyStorage.consume(level, colony, s -> s.is(Items.DIAMOND), cost) && ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1)) {
                return new ItemStack(kind.diamond);
            }
        }
        if (score < 3) {
            smeltTo(level, colony, cost);
            if (ColonyStorage.count(level, colony, s -> s.is(Items.IRON_INGOT)) >= cost && ColonyStorage.count(level, colony, s -> s.is(ItemTags.LOGS)) >= 1) {
                if (ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), cost) && ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1)) {
                    return new ItemStack(kind.iron);
                }
            }
        }
        if (score < 2 && ColonyStorage.count(level, colony, s -> s.is(ItemTags.LOGS)) >= 1 && ColonyStorage.count(level, colony, s -> s.is(Items.COBBLESTONE)) >= 3) {
            if (ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1) && ColonyStorage.consume(level, colony, s -> s.is(Items.COBBLESTONE), 3)) {
                return new ItemStack(kind.stone);
            }
        }
        if (score < 1 && ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 2)) {
            return new ItemStack(kind.wood);
        }
        return ItemStack.EMPTY;
    }

    /** Deposits into storage, else the villager inventory, else drops it. */
    public static void stash(ServerLevel level, Colony colony, Villager v, ItemStack stack) {
        if (stack.isEmpty()) return;
        ColonyStorage.deposit(level, colony, stack);
        if (!stack.isEmpty()) {
            ItemStack rest = v.getInventory().addItem(stack);
            if (!rest.isEmpty()) {
                level.addFreshEntity(new ItemEntity(level, v.getX(), v.getY() + 0.5, v.getZ(), rest));
            }
        }
    }

    private static boolean isFuel(ItemStack s) {
        return s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.LOGS);
    }

    /** Converts raw iron in storage to ingots so that ingots reach target (bookkeeping only). */
    public static void smeltTo(ServerLevel level, Colony colony, int target) {
        smelt(level, colony, Items.RAW_IRON, Items.IRON_INGOT, target);
    }

    /** Smelts raw items into the result until the result count reaches target (Integer.MAX_VALUE = all). */
    public static void smelt(ServerLevel level, Colony colony, Item raw, Item result, int target) {
        int have = ColonyStorage.count(level, colony, s -> s.is(result));
        int rawCount = ColonyStorage.count(level, colony, s -> s.is(raw));
        int fuel = ColonyStorage.count(level, colony, ToolSupply::isFuel);
        long want = target == Integer.MAX_VALUE ? Long.MAX_VALUE : (long) target - have;
        int k = (int) Math.min(Math.max(0, want), Math.min(rawCount, (long) fuel * 8));
        if (k <= 0) return;
        int fuelUsed = (k + 7) / 8;
        ColonyStorage.transaction(level, colony,
                () -> ColonyStorage.consume(level, colony, s -> s.is(raw), k) && ColonyStorage.consume(level, colony, ToolSupply::isFuel, fuelUsed),
                List.of(new ItemStack(result, k)));
    }

    /** After a miner delivery: smelt all raw iron, copper and gold. */
    public static void smeltAll(ServerLevel level, Colony colony) {
        smelt(level, colony, Items.RAW_IRON, Items.IRON_INGOT, Integer.MAX_VALUE);
        smelt(level, colony, Items.RAW_COPPER, Items.COPPER_INGOT, Integer.MAX_VALUE);
        smelt(level, colony, Items.RAW_GOLD, Items.GOLD_INGOT, Integer.MAX_VALUE);
    }

    /** Keeps a held bucket/water bucket; otherwise takes one from storage or makes one from 3 iron. */
    public static boolean ensureBucket(ServerLevel level, Colony colony, Villager v) {
        var inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) return true;
        }
        if (v.getMainHandItem().is(Items.BUCKET) || v.getMainHandItem().is(Items.WATER_BUCKET)) return true;
        if (!inv.canAddItem(new ItemStack(Items.BUCKET))) return false;
        ItemStack bucket = ColonyStorage.withdrawOne(level, colony, s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET));
        if (bucket.isEmpty()) {
            smeltTo(level, colony, 3);
            if (ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), 3)) {
                bucket = new ItemStack(Items.BUCKET);
            }
        }
        if (bucket.isEmpty()) return false;
        ItemStack rest = inv.addItem(bucket);
        stash(level, colony, v, rest);
        return true;
    }
}
