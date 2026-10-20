package net.betterthanvillage.ai.work;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.StockScope;
import net.betterthanvillage.food.FisherTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Hands role-specific tools and gear back to the colony storage when a villager changes role,
 * and picks up the new role's main tool if the storage already has one (nothing is crafted here).
 */
public final class RoleTools {
    private static final Map<Villager, ColonyRole> LAST_ROLE = new WeakHashMap<>();

    private static final Predicate<ItemStack> GUARD_GEAR = s -> s.is(ItemTags.SWORDS) || s.is(Items.BOW) || s.is(Items.CROSSBOW)
            || s.is(Items.SHIELD) || s.getItem() instanceof ArmorItem;
    private static final Predicate<ItemStack> PICKAXE = s -> s.is(ItemTags.PICKAXES);
    private static final Predicate<ItemStack> AXE = s -> s.is(ItemTags.AXES);
    private static final Predicate<ItemStack> HOE = s -> s.is(ItemTags.HOES);
    private static final Predicate<ItemStack> ROD = s -> s.is(Items.FISHING_ROD);
    private static final Predicate<ItemStack> SHEARS = s -> s.is(Items.SHEARS);
    private static final Predicate<ItemStack> BUCKET = s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET);

    private RoleTools() {
    }

    /** Called from the villager tick; acts only when the role differs from the last one seen. */
    public static void tick(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        if (LAST_ROLE.get(v) == role) return;
        LAST_ROLE.put(v, role);
        // The first sighting after a load also runs, so gear kept from an earlier role is cleaned up.
        returnUnneeded(level, colony, v, role);
        takeMainTool(level, colony, v, role);
    }

    private static boolean needed(ColonyRole role, ItemStack s) {
        return switch (role) {
            case WARRIOR -> GUARD_GEAR.test(s);
            case MINER -> PICKAXE.test(s);
            case CARPENTER -> AXE.test(s);
            case FARMER -> HOE.test(s) || BUCKET.test(s);
            case HERDER -> SHEARS.test(s) || BUCKET.test(s) || ROD.test(s);
            default -> false;
        };
    }

    private static boolean roleTool(ItemStack s) {
        return GUARD_GEAR.test(s) || PICKAXE.test(s) || AXE.test(s) || HOE.test(s) || ROD.test(s) || SHEARS.test(s) || BUCKET.test(s);
    }

    private static void returnUnneeded(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ANIMAL_ARMOR) continue;
            ItemStack s = v.getItemBySlot(slot);
            if (s.isEmpty() || !roleTool(s) || needed(role, s)) continue;
            ItemStack rest = s.copy();
            deposit(level, colony, rest);
            v.setItemSlot(slot, rest);
        }
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !roleTool(s) || needed(role, s)) continue;
            deposit(level, colony, s);
        }
        inv.setChanged();
    }

    /** Warrior gear belongs to the treasury (07 §Warriors); other tools to the household. */
    private static void deposit(ServerLevel level, Colony colony, ItemStack s) {
        if (GUARD_GEAR.test(s)) StockScope.treasury(() -> ColonyStorage.deposit(level, colony, s));
        else ColonyStorage.deposit(level, colony, s);
    }

    private static void takeMainTool(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        ToolSupply.Kind kind = switch (role) {
            case MINER -> ToolSupply.Kind.PICKAXE;
            case CARPENTER -> ToolSupply.Kind.AXE;
            case FARMER -> ToolSupply.Kind.HOE;
            default -> null;
        };
        if (kind != null) {
            ToolSupply.takeStored(level, colony, v, kind);
        } else if (role == ColonyRole.HERDER && FisherTask.fishing(level, colony) && !v.getMainHandItem().is(Items.FISHING_ROD)) {
            ItemStack rod = ColonyStorage.withdrawOne(level, colony, ROD);
            if (!rod.isEmpty()) {
                ItemStack old = v.getMainHandItem().copy();
                v.setItemSlot(EquipmentSlot.MAINHAND, rod);
                ToolSupply.stash(level, colony, v, old);
            }
        }
    }

    public static void clearCaches() {
        LAST_ROLE.clear();
    }
}
