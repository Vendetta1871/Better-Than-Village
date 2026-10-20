package net.betterthanvillage.food;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.BuildCost;
import net.betterthanvillage.defense.RaidShelter;
import net.betterthanvillage.mixin.food.ItemEntityAccessor;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Role-aware item pickup and inventory dumping (04 §1.6). */
public final class ItemPickup {
    public static final String STAT_PICKUP = "подбор полезных предметов";
    public static final String STATUS_GOING = "идёт подобрать: ";
    /** Persistent-data flag on a player's death drops. */
    private static final String PLAYER_LOOT = "better_than_village_player_loot";

    private ItemPickup() {
    }

    public static void tick(ServerLevel level, Colony colony, Villager v) {
        int phase = v.tickCount + v.getId();
        if (phase % 5 != 0) return;
        if (v.isSleeping() || v.getTradingPlayer() != null || v.isBaby() || RaidShelter.isActive(level, colony)) return;
        ColonyRole role = colony.roleOf(v.getUUID());
        if (role == ColonyRole.WARRIOR && v.getBrain().hasMemoryValue(ModRegistries.THREAT_TARGET.get())) return;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, v.getBoundingBox().inflate(1.8, 0.6, 1.8))) {
            if (eligible(level, colony, v, item, role)) pickup(level, colony, v, item, Integer.MAX_VALUE);
        }
        if (phase % 20 != 0 || VillagerUtil.anyModTaskRunning(v)) return;
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (t < 12000 && VillagerUtil.currentActivity(v) != Activity.REST && role != ColonyRole.FOUNDER) {
            walkToItems(level, colony, v, role);
        }
        dump(level, colony, v, role);
    }

    // ---------------------------------------------------------------- predicates

    private static String path(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static boolean isWeaponOrArmour(ItemStack s) {
        return s.is(ItemTags.SWORDS) || s.is(Items.BOW) || s.is(Items.CROSSBOW) || s.is(Items.SHIELD) || s.is(Items.ARROW)
                || Equipable.get(s) != null;
    }

    public static boolean useful(ItemStack s, ColonyRole role) {
        if (s.isEmpty()) return false;
        String path = path(s);
        if (ColonyStorage.isFood(s) || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS)
                || s.is(ItemTags.SAPLINGS) || s.is(Items.EMERALD) || s.is(Items.DIAMOND) || s.is(Items.COAL) || s.is(Items.CHARCOAL)
                || path.startsWith("raw_") || path.endsWith("_ingot") || path.endsWith("_nugget") || s.is(Items.REDSTONE)
                || s.is(Items.LAPIS_LAZULI) || s.is(Items.QUARTZ) || s.is(Items.STRING) || s.is(Items.LEATHER) || s.is(Items.WHEAT)
                || s.is(Items.BONE_MEAL) || s.is(Items.PUMPKIN) || s.is(Items.CARVED_PUMPKIN) || s.is(Items.HAY_BLOCK)
                || s.is(Items.IRON_BLOCK) || s.is(Items.GOLD_BLOCK) || s.is(Items.DIAMOND_BLOCK) || s.is(Items.TORCH)
                || s.is(ItemTags.WOOL) || isWeaponOrArmour(s)) {
            return true;
        }
        return roleItem(s, role);
    }

    /** Items only useful for the work of this role. */
    private static boolean roleItem(ItemStack s, ColonyRole role) {
        String path = path(s);
        return switch (role) {
            case FARMER -> path.endsWith("_seeds") || s.is(ItemTags.HOES);
            case HERDER -> RancherTask.isProduct(s) || s.is(Items.BUCKET) || s.is(Items.SHEARS) || s.is(Items.FISHING_ROD);
            case MASON -> s.is(Items.STONE) || s.is(Items.STONE_BRICKS) || s.is(Items.GLASS);
            case MINER -> s.is(ItemTags.PICKAXES) || s.is(ItemTags.STONE_CRAFTING_MATERIALS) || path.startsWith("raw_") || s.is(Items.COAL);
            case CARPENTER -> s.is(ItemTags.AXES) || s.is(ItemTags.LOGS) || s.is(ItemTags.SAPLINGS);
            default -> false;
        };
    }

    public static void markPlayerLoot(ItemEntity item) {
        item.getPersistentData().putBoolean(PLAYER_LOOT, true);
    }

    public static boolean eligible(ServerLevel level, Colony colony, Villager v, ItemEntity item, ColonyRole role) {
        ItemEntityAccessor access = (ItemEntityAccessor) item;
        if (item.isRemoved() || item.hasPickUpDelay() || access.btv$owner() != null) return false;
        // Player Q-drops: anything thrown by a player (or by a thrower no longer loaded) is not up for grabs;
        // items thrown by mobs (villager food sharing) stay eligible.
        if (access.btv$thrower() != null && (item.getOwner() == null || item.getOwner() instanceof Player)) return false;
        if (item.getPersistentData().getBoolean(PLAYER_LOOT)) return false;
        ItemStack s = item.getItem();
        if (!useful(s, role)) return false;
        BlockPos pos = item.blockPosition();
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos) || Protection.inPlayerOwnedJob(level, pos)) return false;
        // Next to player builds (hopper lines, farms) only food and the role's own work items are taken.
        if (!ColonyStorage.isFood(s) && !roleItem(s, role) && data.playerBuildNear(pos, 4)) return false;
        if (!v.hasLineOfSight(item)) return false;
        if (s.is(Items.EGG) && FoodUtil.count(v.getInventory(), x -> x.is(Items.EGG)) >= 16) return false;
        return !s.is(Items.MILK_BUCKET) || MilkLedger.colonyMilk(level, colony) < MilkLedger.CAP;
    }

    /** Moves up to max items (egg and milk caps applied) into the inventory. Returns the amount moved. */
    public static int pickup(ServerLevel level, Colony colony, Villager v, ItemEntity item, int max) {
        ItemStack stack = item.getItem();
        int amount = Math.min(max, stack.getCount());
        if (stack.is(Items.EGG)) amount = Math.min(amount, 16 - FoodUtil.count(v.getInventory(), x -> x.is(Items.EGG)));
        if (stack.is(Items.MILK_BUCKET)) amount = Math.min(amount, MilkLedger.CAP - MilkLedger.colonyMilk(level, colony));
        if (amount <= 0) return 0;
        ItemStack rest = v.getInventory().addItem(stack.copyWithCount(amount));
        int moved = amount - rest.getCount();
        if (moved <= 0) return 0;
        v.take(item, moved);
        ItemStack left = stack.copy();
        left.shrink(moved);
        if (left.isEmpty()) item.discard();
        else item.setItem(left);
        WorkTracker.credit(v, STAT_PICKUP, moved);
        return moved;
    }

    // ---------------------------------------------------------------- slow steps

    private static void walkToItems(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        long now = level.getGameTime();
        SimpleContainer inv = v.getInventory();
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, v.getBoundingBox().inflate(8, 2, 8))) {
            double d = v.distanceToSqr(item);
            if (d > 64 || d >= bestD) continue;
            if (!FoodUtil.hasRoom(inv, item.getItem()) || Nav.isBlacklisted(v, item.blockPosition(), now)) continue;
            if (!eligible(level, colony, v, item, role)) continue;
            best = item;
            bestD = d;
        }
        if (best == null) return;
        Nav.approach(level, v, best.blockPosition(), 0.8, Nav.R2_NEAR);
        WorkTracker.status(v, STATUS_GOING + path(best.getItem()));
    }

    /** Items a villager of this role keeps when unloading at a storage. */
    public static boolean keepWhenDumping(ItemStack s, ColonyRole role) {
        if (ColonyStorage.isFood(s) || s.is(Items.TORCH) || s.is(ItemTags.PICKAXES) || s.is(ItemTags.AXES) || s.is(ItemTags.HOES)
                || s.is(Items.FISHING_ROD)) return true;
        return switch (role) {
            case WARRIOR -> isWeaponOrArmour(s);
            case HERDER -> s.is(Items.SHEARS) || s.is(Items.BUCKET) || s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT);
            case FARMER -> s.is(Items.WHEAT) || path(s).endsWith("_seeds") || s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET);
            // Building material fetched for the current house must not go straight back to storage.
            case MASON -> BuildCost.buildingMaterial(s) || s.is(ItemTags.WOOL) || s.is(Items.COAL) || s.is(Items.CHARCOAL)
                    || s.is(Items.IRON_INGOT) || s.is(Items.IRON_NUGGET) || s.is(Items.WATER_BUCKET) || s.is(Items.FLOWER_POT);
            default -> false;
        };
    }

    private static void dump(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        BlockPos storage = ColonyStorage.nearest(level, colony, v.blockPosition());
        if (storage == null || v.blockPosition().distSqr(storage) > 9) return;
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !keepWhenDumping(s, role)) FoodUtil.deposit(level, colony, v, s);
        }
        inv.setChanged();
    }
}
