package net.betterthanvillage.ai;

import net.betterthanvillage.ai.nav.AntiStuck;
import net.betterthanvillage.ai.nav.DoorHandler;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.RoleBalancer;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.defense.GuardPatrol;
import net.betterthanvillage.defense.GuardShield;
import net.betterthanvillage.defense.RaidShelter;
import net.betterthanvillage.defense.RaidShelterTask;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.defense.GuardEquipmentTask;
import net.betterthanvillage.food.ItemPickup;
import net.betterthanvillage.gather.NightLighting;
import net.betterthanvillage.registry.ModRegistries;
import net.betterthanvillage.society.LandNotices;
import net.betterthanvillage.society.OfflineSim;
import net.betterthanvillage.society.Trading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** The per-tick colony controller for villagers (runs at the end of Villager#customServerAiStep). */
public final class VillagerController {
    private VillagerController() {
    }

    /** Head hook: an adult with no profession but a colony role gets its visuals immediately. */
    public static void preTick(ServerLevel level, Villager v) {
        if (v.isBaby() || v.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NONE) {
            return;
        }
        Colony colony = ColonyManager.colonyAt(level, v.blockPosition());
        if (colony != null && colony.roleOf(v.getUUID()) != ColonyRole.CITIZEN) {
            RoleBalancer.applyRole(level, colony, v);
        }
    }

    public static void tick(ServerLevel level, Villager v) {
        long now = level.getGameTime();
        Colony colony = ColonyManager.foundingPartyColony(level, v.getUUID());
        if (colony == null) {
            colony = ColonyManager.colonyAt(level, v.blockPosition());
        }
        DoorHandler.tick(level, v);
        if (colony == null) {
            v.getBrain().eraseMemory(ModRegistries.COLONY_ROLE.get());
            Activity a = VillagerUtil.currentActivity(v);
            if (a == ModRegistries.COLONY_WORK.get() || a == ModRegistries.COLONY_TRAVEL.get() || a == ModRegistries.COLONY_SHELTER.get() || a == Activity.FIGHT) {
                VillagerUtil.refreshActivities(level, v);
            }
            return;
        }
        Colony positional = ColonyManager.colonyAt(level, v.blockPosition());
        if (positional != null && positional.id().equals(colony.id())) {
            colony.lastMemberTick = now;
            OfflineSim.bookkeeping(level, colony);
        }
        ItemPickup.tick(level, colony, v);
        ColonyRole role = colony.roleOf(v.getUUID());

        if (RaidShelter.isActive(level, colony) && role != ColonyRole.GUARD) {
            v.setTradingPlayer(null);
            if (v.isSleeping()) v.stopSleeping();
            if (VillagerUtil.currentActivity(v) != ModRegistries.COLONY_SHELTER.get()) {
                VillagerUtil.stopRunning(level, v, b -> !(b instanceof RaidShelterTask));
                Nav.reset(v);
            }
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            VillagerUtil.doExclusively(v, ModRegistries.COLONY_SHELTER.get());
            return;
        }
        if (VillagerUtil.currentActivity(v) == ModRegistries.COLONY_SHELTER.get()) {
            VillagerUtil.refreshActivities(level, v);
        }
        if (GuardShield.tick(level, v, colony)) {
            return;
        }
        if (role == ColonyRole.FOUNDER) {
            v.getBrain().setMemory(ModRegistries.COLONY_ROLE.get(), role);
            if (VillagerUtil.currentActivity(v) != ModRegistries.COLONY_TRAVEL.get()) {
                VillagerUtil.stopAllTasks(level, v);
                Nav.reset(v);
            }
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            VillagerUtil.doExclusively(v, ModRegistries.COLONY_TRAVEL.get());
            return;
        }
        if (v.getTradingPlayer() != null) {
            Trading.freeze(v);
            return;
        }
        if ((now + v.getId()) % 100 == 0) {
            v.getBrain().setMemory(ModRegistries.COLONY_ROLE.get(), role);
        }
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean work = t >= 1000 && t < 9000;
        boolean evening = t >= 9000 && t < 12000;
        boolean night = t >= 12000;
        boolean raidShelter = RaidShelter.isActive(level, colony);
        LivingEntity threat = role == ColonyRole.GUARD ? ThreatManager.findThreat(level, colony) : null;
        boolean alarm = (colony.lastThreatTick != Colony.NEVER && now - colony.lastThreatTick <= 200) || raidShelter || threat != null;

        sleepController(level, colony, v, night, alarm);
        if (v.isSleeping()) {
            v.getNavigation().stop();
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            v.setDeltaMovement(Vec3.ZERO);
            VillagerUtil.doExclusively(v, Activity.REST);
            return;
        }
        boolean eating = foodRoutine(level, colony, v, evening);
        if (LandNotices.elderWalk(level, v, colony)) {
            return;
        }
        NightLighting.tick(level, colony, v);
        if (role != ColonyRole.GUARD && (now + v.getId()) % 100 == 0) returnGuardGear(level, colony, v);
        WorkTracker.watchdog(level, colony, v);
        AntiStuck.monitor(level, colony, v);
        boolean forcedIdle = AntiStuck.isForcedIdle(v, now);
        boolean engaged = false;
        if (role == ColonyRole.GUARD) {
            Optional<LivingEntity> mem = v.getBrain().getMemory(ModRegistries.THREAT_TARGET.get());
            engaged = (mem.isPresent() && ThreatManager.isValidHostile(mem.get()) && ThreatManager.inColony(colony, mem.get())) || threat != null;
        }
        boolean workNeeded;
        if (role == ColonyRole.GUARD && (alarm || engaged)) {
            workNeeded = true;
        } else if (!work) {
            workNeeded = false;
        } else {
            workNeeded = switch (role) {
                case BUILDER -> colony.needsStarterWarehouse() || colony.storages.isEmpty() || colony.hasActiveConstruction()
                        || !colony.roadQueue.isEmpty() || !colony.repairs.isEmpty() || Fortifications.hasPendingWall(colony);
                case LUMBERJACK -> true;
                case MINER, FARMER, FISHERMAN, RANCHER, GUARD -> !colony.storages.isEmpty();
                default -> false;
            };
        }
        Activity chosen = null;
        if (forcedIdle || eating) {
            chosen = Activity.IDLE;
        } else if (night && !alarm) {
            chosen = Activity.REST;
        } else if (role == ColonyRole.GUARD && (engaged || alarm || v.getHealth() < v.getMaxHealth() * 0.3f)) {
            chosen = Activity.FIGHT;
        } else if (workNeeded) {
            chosen = ModRegistries.COLONY_WORK.get();
        }
        if (chosen != null) {
            VillagerUtil.doExclusively(v, chosen);
        } else {
            Activity a = VillagerUtil.currentActivity(v);
            if (a == Activity.FIGHT || a == ModRegistries.COLONY_WORK.get() || a == ModRegistries.COLONY_TRAVEL.get()) {
                VillagerUtil.refreshActivities(level, v);
            }
        }
        if (role == ColonyRole.GUARD && work && !alarm && (now + v.getId()) % 40 == 0 && !VillagerUtil.isRunning(v, GuardEquipmentTask.class)) {
            GuardPatrol.patrol(level, colony, v);
        }
    }

    // ---------------------------------------------------------------- sleep

    private static void sleepController(ServerLevel level, Colony colony, Villager v, boolean night, boolean alarm) {
        long now = level.getGameTime();
        if (night && !alarm) {
            VillagerUtil.stopRunning(level, v, b -> VillagerUtil.isModTask(b) || VillagerUtil.isVanillaFarmerTask(b));
            if (v.isSleeping()) {
                v.getNavigation().stop();
                v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                return;
            }
            if ((now + v.getId()) % 20 != 0) return;
            BlockPos bed = null;
            Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
            if (home.isPresent() && home.get().dimension() == level.dimension() && usableBed(level, v, home.get().pos())) {
                bed = home.get().pos();
            }
            if (bed == null) {
                bed = level.getPoiManager().findAllClosestFirstWithType(t -> t.is(PoiTypes.HOME), p -> p.closerThan(colony.core(), colony.radius()) && usableBed(level, v, p),
                                v.blockPosition(), colony.radius() * 2, PoiManager.Occupancy.ANY)
                        .map(pair -> pair.getSecond()).findFirst().orElse(null);
            }
            if (bed == null) return;
            v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
            if (v.blockPosition().distSqr(bed) <= 4) {
                v.getNavigation().stop();
                v.startSleeping(bed);
            } else {
                Nav.approach(level, v, bed, 0.85, Nav.R2_BED);
            }
        } else if (v.isSleeping()) {
            v.stopSleeping();
        }
    }

    private static boolean usableBed(ServerLevel level, Villager v, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState s = level.getBlockState(pos);
        if (!s.is(BlockTags.BEDS)) return false;
        if (s.hasProperty(BedBlock.OCCUPIED) && s.getValue(BedBlock.OCCUPIED)) {
            return v.getSleepingPos().map(pos::equals).orElse(false);
        }
        return true;
    }

    // ---------------------------------------------------------------- food

    private static boolean foodRoutine(ServerLevel level, Colony colony, Villager v, boolean evening) {
        long now = level.getGameTime();
        SimpleContainer inv = v.getInventory();
        boolean breedReady = !v.isBaby() && v.getAge() == 0;
        boolean hasFood = hasFood(inv);
        int foodValue = ColonyStorage.foodValue(inv);
        boolean needs = evening && (!hasFood || (breedReady && foodValue < 12));
        if ((now + v.getId()) % 20 != 0) {
            return needs;
        }
        if (v.getHealth() < v.getMaxHealth() && (now + v.getId()) % 40 == 0) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                FoodProperties food = s.get(DataComponents.FOOD);
                if (food != null) {
                    s.shrink(1);
                    v.heal(Math.max(2f, food.nutrition() * 1.5f));
                    v.swing(InteractionHand.MAIN_HAND);
                    return needs;
                }
            }
            BlockPos storage = ColonyStorage.nearest(level, colony, v.blockPosition());
            if (storage != null && v.blockPosition().distSqr(storage) <= 9 && colony.cachedFood > colony.lastPopulation * 12) {
                ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
                if (!food.isEmpty()) {
                    FoodProperties fp = food.get(DataComponents.FOOD);
                    v.heal(fp != null ? Math.max(2f, fp.nutrition() * 1.5f) : 2f);
                    v.swing(InteractionHand.MAIN_HAND);
                }
            }
        }
        if (!needs) {
            return false;
        }
        BlockPos storage = ColonyStorage.nearest(level, colony, v.blockPosition());
        if (storage == null) {
            return true;
        }
        if (v.blockPosition().distSqr(storage) > 9) {
            v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), storage);
            Nav.approach(level, v, storage, 0.85);
            return true;
        }
        int target = breedReady ? 12 : hasFood ? 0 : 1;
        for (int k = 0; k < 12 && foodValue < target; k++) {
            ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
            if (food.isEmpty()) break;
            foodValue += ColonyStorage.foodValue(food);
            ItemStack rest = inv.addItem(food);
            if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
        }
        if (!hasFood(inv)) {
            ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
            if (!food.isEmpty()) {
                ItemStack rest = inv.addItem(food);
                if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
            }
        }
        v.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
        return !hasFood(inv) || (breedReady && ColonyStorage.foodValue(inv) < 12);
    }

    private static boolean hasFood(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ColonyStorage.isFood(inv.getItem(i))) return true;
        }
        return false;
    }

    private static final EquipmentSlot[] GUARD_GEAR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

    /** A villager that left the militia hands its armor and shield back to the colony storage. */
    private static void returnGuardGear(ServerLevel level, Colony colony, Villager v) {
        for (EquipmentSlot slot : GUARD_GEAR_SLOTS) {
            ItemStack gear = v.getItemBySlot(slot);
            if (gear.isEmpty()) continue;
            if (slot == EquipmentSlot.OFFHAND && !gear.is(Items.SHIELD)) continue;
            ItemStack rest = gear.copy();
            ColonyStorage.deposit(level, colony, rest);
            // Whatever did not fit stays equipped until there is room.
            v.setItemSlot(slot, rest);
        }
    }
}
