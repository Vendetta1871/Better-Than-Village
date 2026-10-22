package net.betterthanvillage.defense;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.ToolSupply;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/** Guard equipment and golem duty, COLONY_WORK priority 1 (02 §3.4). */
public final class GuardEquipmentTask extends ColonyTask {
    /** Armor slot record: slot, diamond piece, iron piece, diamond/iron cost. */
    public record ArmorSlot(EquipmentSlot slot, Item diamond, Item iron, int cost) {
    }

    /** Upgrade order used by the armor step. */
    public static final ArmorSlot[] ARMOR = {
            new ArmorSlot(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE, 8),
            new ArmorSlot(EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.IRON_LEGGINGS, 7),
            new ArmorSlot(EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.IRON_HELMET, 5),
            new ArmorSlot(EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.IRON_BOOTS, 4)};

    // axes stay with the lumberjacks: guards fight with swords and bows only
    private static final Predicate<ItemStack> MELEE = s -> s.is(ItemTags.SWORDS);
    private static final Predicate<ItemStack> GOOD_SWORD = s -> s.is(Items.DIAMOND_SWORD) || s.is(Items.IRON_SWORD);
    private static final Predicate<ItemStack> BOW = s -> s.is(Items.BOW);
    private static final Predicate<ItemStack> WEAPON = s -> BOW.test(s) || MELEE.test(s);
    private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);

    private final GolemManager.Duty golem = new GolemManager.Duty();
    private long nextRunAt;
    private Colony colony;
    private BlockPos storage;
    private boolean done;

    public GuardEquipmentTask() {
        super(600);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || v.isTrading() || level.getGameTime() < nextRunAt || colony.storages.isEmpty()
                || colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR) {
            return false;
        }
        if (!needsEquipment(level, v, colony) && !GolemManager.workNeeded(level, colony)) {
            // the golem check scans a large box: do not repeat it every tick
            nextRunAt = level.getGameTime() + 100;
            return false;
        }
        this.colony = colony;
        storage = ColonyStorage.nearest(level, colony, v.blockPosition());
        done = false;
        golem.reset();
        return storage != null;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), storage);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        // running behaviors keep ticking after an activity switch: yield as soon as the guard has to fight
        return !v.isSleeping() && !v.isTrading() && colony != null && colony.roleOf(v.getUUID()) == ColonyRole.WARRIOR
                && storage != null && !done && VillagerUtil.currentActivity(v) == ModRegistries.COLONY_WORK.get();
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (golem.active()) {
            done = golem.step(level, colony, v);
            if (done) {
                golem.reset();
                nextRunAt = now + 40;
            }
            return;
        }
        if (v.distanceToSqr(storage.getX() + 0.5, storage.getY() + 0.5, storage.getZ() + 0.5) > 9) {
            Nav.approach(level, v, storage, 0.65);
            return;
        }
        if (golem.plan(level, colony, v)) return;
        boolean equipped = colony.roleOf(v.getUUID()) == ColonyRole.WARRIOR
                && (weaponsAndShield(level, colony, v) || armor(level, colony, v));
        if (equipped) v.swing(InteractionHand.MAIN_HAND);
        done = true;
        nextRunAt = now + (equipped ? 40 : 200);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        // interrupted or timed out (unreachable storage, golem or stand spot): do not retry at once
        if (!done) nextRunAt = now + 400;
        v.getNavigation().stop();
        colony = null;
        storage = null;
        golem.reset();
    }

    // ---------------------------------------------------------------- needs

    /** True when a missing or upgradable piece can actually be obtained from storage (stocked or craftable). */
    public static boolean needsEquipment(ServerLevel level, Villager v, Colony colony) {
        int logs = colony.cachedLogs;
        int iron = colony.cachedIron;
        int diamonds = colony.cachedDiamonds;
        boolean ranged = ranged(colony, v);
        // Gear follows the class (docs_v2/07-warfare.md §Classes): a warrior that is no archer returns its bow.
        if (!ranged && carries(v, BOW)) return true;
        if (ranged && !carries(v, BOW) && (logs >= 4 || ColonyStorage.has(level, colony, BOW)
                || (logs >= 1 && ColonyStorage.count(level, colony, s -> s.is(Items.STRING)) >= 3))) {
            return true;
        }
        if (!carries(v, MELEE) && (((diamonds >= 2 || iron >= 2) && logs >= 1) || ColonyStorage.has(level, colony, GOOD_SWORD))) {
            return true;
        }
        if (!v.getOffhandItem().is(Items.SHIELD) && ((iron >= 1 && logs >= 6) || ColonyStorage.has(level, colony, s -> s.is(Items.SHIELD)))) {
            return true;
        }
        for (ArmorSlot a : ARMOR) {
            ItemStack worn = v.getItemBySlot(a.slot());
            if (topTier(worn)) continue;
            if (diamonds >= a.cost() || ColonyStorage.has(level, colony, s -> s.is(a.diamond()))) return true;
            if (worn.isEmpty() && (ColonyStorage.has(level, colony, s -> s.is(a.iron()))
                    || (iron >= a.cost() && iron >= GuardGear.ironReserve(level, colony) + a.cost()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean topTier(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem armor
                && (armor.getMaterial().is(ArmorMaterials.DIAMOND) || armor.getMaterial().is(ArmorMaterials.NETHERITE));
    }

    /** Main hand, off hand or inventory holds a matching item. */
    private static boolean carries(Villager v, Predicate<ItemStack> pred) {
        if (pred.test(v.getMainHandItem()) || pred.test(v.getOffhandItem())) return true;
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (pred.test(inv.getItem(i))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- steps

    private static boolean ranged(Colony colony, Villager v) {
        return net.betterthanvillage.warfare.Army.classOf(colony, v.getUUID()) == net.betterthanvillage.warfare.WarriorClass.RANGED;
    }

    /** A bow carried by a warrior that is no archer goes back to the treasury. */
    private static boolean returnBow(ServerLevel level, Colony colony, Villager v) {
        if (BOW.test(v.getMainHandItem())) {
            depositFrom(level, colony, v, v.getMainHandItem());
            return true;
        }
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (BOW.test(inv.getItem(i))) {
                ItemStack bow = inv.removeItemNoUpdate(i);
                inv.setChanged();
                ColonyStorage.deposit(level, colony, bow);
                return true;
            }
        }
        return false;
    }

    private static boolean weaponsAndShield(ServerLevel level, Colony colony, Villager v) {
        boolean ranged = ranged(colony, v);
        if (!ranged && returnBow(level, colony, v)) return true;
        if (ranged && !carries(v, BOW)) {
            ItemStack bow = ColonyStorage.withdrawOne(level, colony, BOW);
            if (!bow.isEmpty()) return give(level, colony, v, bow);
            if (ColonyStorage.count(level, colony, s -> s.is(Items.STRING)) >= 3 && ColonyStorage.count(level, colony, LOG) >= 1) {
                ColonyStorage.consume(level, colony, s -> s.is(Items.STRING), 3);
                ColonyStorage.consume(level, colony, LOG, 1);
                return give(level, colony, v, new ItemStack(Items.BOW));
            }
            if (ColonyStorage.count(level, colony, LOG) >= 4) {
                ColonyStorage.consume(level, colony, LOG, 4);
                return give(level, colony, v, new ItemStack(Items.BOW));
            }
        }
        if (!carries(v, MELEE)) {
            ItemStack sword = ColonyStorage.withdrawOne(level, colony, GOOD_SWORD);
            if (!sword.isEmpty()) return give(level, colony, v, sword);
            if (ColonyStorage.count(level, colony, s -> s.is(Items.DIAMOND)) >= 2 && ColonyStorage.count(level, colony, LOG) >= 1) {
                ColonyStorage.consume(level, colony, s -> s.is(Items.DIAMOND), 2);
                ColonyStorage.consume(level, colony, LOG, 1);
                return give(level, colony, v, new ItemStack(Items.DIAMOND_SWORD));
            }
            ToolSupply.smeltTo(level, colony, 2);
            if (ColonyStorage.count(level, colony, s -> s.is(Items.IRON_INGOT)) >= 2 && ColonyStorage.count(level, colony, LOG) >= 1) {
                ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), 2);
                ColonyStorage.consume(level, colony, LOG, 1);
                return give(level, colony, v, new ItemStack(Items.IRON_SWORD));
            }
        }
        if (!v.getOffhandItem().is(Items.SHIELD)) {
            ItemStack shield = ColonyStorage.withdrawOne(level, colony, s -> s.is(Items.SHIELD));
            if (!shield.isEmpty()) {
                depositFrom(level, colony, v, v.getOffhandItem());
                v.setItemSlot(EquipmentSlot.OFFHAND, shield);
                return true;
            }
            ToolSupply.smeltTo(level, colony, 1);
            if (ColonyStorage.count(level, colony, s -> s.is(Items.IRON_INGOT)) >= 1 && ColonyStorage.count(level, colony, LOG) >= 6) {
                ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), 1);
                ColonyStorage.consume(level, colony, LOG, 6);
                depositFrom(level, colony, v, v.getOffhandItem());
                v.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                return true;
            }
        }
        return false;
    }

    /** Equips the item in the main hand unless a weapon is already there; otherwise stores it in the inventory. */
    private static boolean give(ServerLevel level, Colony colony, Villager v, ItemStack item) {
        if (!WEAPON.test(v.getMainHandItem())) {
            depositFrom(level, colony, v, v.getMainHandItem());
            v.setItemSlot(EquipmentSlot.MAINHAND, item);
        } else {
            ItemStack rest = v.getInventory().addItem(item);
            if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
        }
        return true;
    }

    private static boolean armor(ServerLevel level, Colony colony, Villager v) {
        for (ArmorSlot a : ARMOR) {
            ItemStack worn = v.getItemBySlot(a.slot());
            if (topTier(worn)) continue;
            boolean empty = worn.isEmpty();
            ItemStack piece = ColonyStorage.withdrawOne(level, colony, s -> s.is(a.diamond()) || (empty && s.is(a.iron())));
            if (!piece.isEmpty()) {
                depositFrom(level, colony, v, worn);
                v.setItemSlot(a.slot(), piece);
                return true;
            }
            if (ColonyStorage.consume(level, colony, s -> s.is(Items.DIAMOND), a.cost())) {
                depositFrom(level, colony, v, worn);
                v.setItemSlot(a.slot(), new ItemStack(a.diamond()));
                return true;
            }
            if (empty) {
                // armor never eats the iron kept for golems (same reserve as the guard gear stock)
                int reserve = GuardGear.ironReserve(level, colony);
                ToolSupply.smeltTo(level, colony, reserve + a.cost());
                if (ColonyStorage.count(level, colony, s -> s.is(Items.IRON_INGOT)) >= reserve + a.cost()
                        && ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), a.cost())) {
                    v.setItemSlot(a.slot(), new ItemStack(a.iron()));
                    return true;
                }
            }
        }
        return false;
    }

    /** Deposit into storage; else into the guard inventory; else drop. The original stack is emptied. */
    private static void depositFrom(ServerLevel level, Colony colony, Villager v, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack copy = stack.copy();
        stack.setCount(0);
        ToolSupply.stash(level, colony, v, copy);
    }
}
