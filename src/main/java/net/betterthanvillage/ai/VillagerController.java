package net.betterthanvillage.ai;

import net.betterthanvillage.ai.nav.AntiStuck;
import net.betterthanvillage.ai.nav.DoorHandler;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.RoleTools;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.defense.GuardPatrol;
import net.betterthanvillage.economy.Gathering;
import net.betterthanvillage.economy.StockScope;
import net.betterthanvillage.defense.GuardShield;
import net.betterthanvillage.defense.RaidShelter;
import net.betterthanvillage.defense.RaidShelterTask;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.defense.GuardEquipmentTask;
import net.betterthanvillage.food.ItemPickup;
import net.betterthanvillage.gather.NightLighting;
import net.betterthanvillage.registry.ModRegistries;
import net.betterthanvillage.society.OfflineSim;
import net.betterthanvillage.society.Trading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** The per-tick colony controller for villagers (runs at the end of Villager#customServerAiStep). */
public final class VillagerController {
    private static final int BED_SEARCH_RADIUS = 48;
    private static final Map<Villager, Long> NEXT_BED_SEARCH = new WeakHashMap<>();
    /** Night bed of a villager without a free bed of its own: never written to HOME, holds no POI ticket. */
    private static final Map<Villager, BlockPos> SPARE_BED = new WeakHashMap<>();
    /** Beds a villager could not path to, with the game time until which it ignores them. */
    private static final Map<Villager, Map<BlockPos, Long>> UNREACHABLE_BED = new WeakHashMap<>();
    /** Villagers whose colony task was steering the navigation last tick. */
    private static final Map<Villager, Boolean> TASK_STEERED = new WeakHashMap<>();
    private static final Map<Villager, Long> LOCAL_THREAT = new WeakHashMap<>();
    private static final int CIVILIAN_ALARM_RANGE = 24;

    private VillagerController() {
    }

    /** Head hook: an adult with no profession but a colony role gets its visuals immediately. */
    public static void preTick(ServerLevel level, Villager v) {
        if (v.isBaby() || v.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NONE) {
            return;
        }
        Colony colony = ColonyManager.colonyOf(level, v);
        if (colony != null && colony.roleOf(v.getUUID()) != ColonyRole.CITIZEN) {
            Professions.applyRole(level, colony, v);
        }
    }

    public static void tick(ServerLevel level, Villager v) {
        long now = level.getGameTime();
        Colony colony = ColonyManager.colonyOf(level, v);
        DoorHandler.tick(level, v);
        releaseFinishedTaskWalk(v, now);
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
            StockScope.treasury(() -> OfflineSim.bookkeeping(level, colony));
        }
        ItemPickup.tick(level, colony, v);
        StarterWarehouseTask.clearStaleDisplay(v);
        ColonyRole role = colony.roleOf(v.getUUID());

        if (RaidShelter.isActive(level, colony) && role != ColonyRole.WARRIOR) {
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
        // The shield no longer freezes the guard: GuardCombatTask keeps closing in while it blocks.
        GuardShield.tick(level, v, colony);
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
        LivingEntity threat = role == ColonyRole.WARRIOR ? ThreatManager.findThreat(level, colony) : null;
        // Guards answer the colony-wide alarm; civilians only a raid or a hostile close to themselves.
        boolean alarm = role == ColonyRole.WARRIOR
                ? (colony.lastThreatTick != Colony.NEVER && now - colony.lastThreatTick <= 200) || raidShelter || threat != null
                : raidShelter || threatNearby(level, colony, v, now);

        sleepController(level, colony, v, night, alarm);
        if (v.isSleeping()) {
            v.getNavigation().stop();
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            v.setDeltaMovement(Vec3.ZERO);
            VillagerUtil.doExclusively(v, Activity.REST);
            return;
        }
        boolean eating = foodRoutine(level, colony, v, evening);
        if (!eating && !(role == ColonyRole.WARRIOR && alarm) && Gathering.attend(level, colony, v)) {
            return;
        }
        if (net.betterthanvillage.law.Surrender.elderWalk(level, v, colony)) {
            return;
        }
        StockScope.treasury(() -> NightLighting.tick(level, colony, v));
        // Warriors' gear is treasury property (07 §Warriors); other tools belong to the household.
        if (role == ColonyRole.WARRIOR) StockScope.treasury(() -> RoleTools.tick(level, colony, v, role));
        else RoleTools.tick(level, colony, v, role);
        WorkTracker.watchdog(level, colony, v);
        AntiStuck.monitor(level, colony, v);
        boolean forcedIdle = AntiStuck.isForcedIdle(v, now);
        boolean engaged = false;
        if (role == ColonyRole.WARRIOR) {
            Optional<LivingEntity> mem = v.getBrain().getMemory(ModRegistries.THREAT_TARGET.get());
            engaged = (mem.isPresent() && ThreatManager.keepsTarget(level, colony, mem.get())) || threat != null;
        }
        boolean workNeeded;
        if (role == ColonyRole.WARRIOR && (alarm || engaged)) {
            workNeeded = true;
        } else if (!work) {
            workNeeded = false;
        } else {
            workNeeded = switch (role) {
                case MASON -> colony.needsStarterWarehouse() || colony.storages.isEmpty() || colony.hasActiveConstruction()
                        || !colony.roadQueue.isEmpty() || !colony.repairs.isEmpty() || Fortifications.hasPendingWall(colony);
                case CARPENTER -> true;
                case MINER, FARMER, HERDER, SMITH, HEALER, LIBRARIAN -> colony.hasStorage();
                case WARRIOR -> !colony.storages.isEmpty();
                default -> false;
            };
        }
        // A guard under alarm fights before anything else (the evening food walk would leave the colony undefended).
        boolean guardCombat = role == ColonyRole.WARRIOR && (engaged || alarm) && !(night && !alarm);
        Activity chosen = null;
        if (guardCombat) {
            chosen = Activity.FIGHT;
        } else if (forcedIdle || eating) {
            chosen = Activity.IDLE;
        } else if (night && !alarm) {
            chosen = Activity.REST;
        } else if (role == ColonyRole.WARRIOR && v.getHealth() < v.getMaxHealth() * 0.3f) {
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
        if (role == ColonyRole.WARRIOR && work && !alarm && (now + v.getId()) % 40 == 0 && !VillagerUtil.isRunning(v, GuardEquipmentTask.class)) {
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
            if (home.isPresent() && home.get().dimension() == level.dimension() && usableBed(level, v, home.get().pos())
                    && !unreachableBed(v, home.get().pos(), now)) {
                bed = home.get().pos();
                SPARE_BED.remove(v);
            }
            if (bed == null) {
                BlockPos spare = SPARE_BED.get(v);
                if (spare != null && usableBed(level, v, spare) && !unreachableBed(v, spare, now)) {
                    bed = spare;
                } else {
                    SPARE_BED.remove(v);
                }
            }
            if (bed == null) {
                // The POI search reads POI sections synchronously, so keep it local and retry a miss slowly.
                Long next = NEXT_BED_SEARCH.get(v);
                if (next != null && now < next) return;
                bed = findBed(level, colony, v, home.isEmpty(), now);
                if (bed == null) {
                    NEXT_BED_SEARCH.put(v, now + 200);
                    return;
                }
                NEXT_BED_SEARCH.remove(v);
            }
            if (v.blockPosition().distSqr(bed) <= 4) {
                v.getNavigation().stop();
                v.startSleeping(bed);
            } else if (!Nav.approach(level, v, bed, 0.85, Nav.R2_BED) && Nav.isBlacklisted(v, bed, now)) {
                // No way in (e.g. leaves grown over the doorstep): try another bed for a while.
                UNREACHABLE_BED.computeIfAbsent(v, k -> new HashMap<>()).put(bed.immutable(), now + 2400);
                SPARE_BED.remove(v);
            }
        } else if (v.isSleeping()) {
            v.stopSleeping();
        }
    }

    /**
     * Bed for the night. A villager without HOME claims a free bed the vanilla way (takes the POI ticket and
     * sets HOME, so vanilla releases it on death or when the bed is lost). Otherwise, or if no bed is free, it
     * borrows an unoccupied bed for the night without touching HOME or tickets, so bed ownership and the
     * free-bed count used by breeding always stay consistent.
     */
    private static BlockPos findBed(ServerLevel level, Colony colony, Villager v, boolean mayClaim, long now) {
        PoiManager poi = level.getPoiManager();
        Map<BlockPos, Long> unreachable = UNREACHABLE_BED.get(v);
        if (unreachable != null) {
            unreachable.values().removeIf(t -> t <= now);
            if (unreachable.isEmpty()) UNREACHABLE_BED.remove(v);
        }
        int radius = Math.min(colony.radius(), BED_SEARCH_RADIUS);
        if (mayClaim) {
            BlockPos free = poi.findAllClosestFirstWithType(t -> t.is(PoiTypes.HOME), p -> p.closerThan(colony.core(), colony.radius()) && usableBed(level, v, p)
                                    && !unreachableBed(v, p, now), v.blockPosition(), radius, PoiManager.Occupancy.HAS_SPACE)
                    .map(pair -> pair.getSecond()).findFirst().orElse(null);
            if (free != null && poi.take(t -> t.is(PoiTypes.HOME), (t, p) -> p.equals(free), free, 1).isPresent()) {
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), free));
                return free;
            }
        }
        // One borrower per bed: villagers sharing the nearest bed would all walk there and only one could sleep.
        Set<BlockPos> borrowed = new HashSet<>();
        SPARE_BED.forEach((other, pos) -> {
            if (other != v && other.isAlive()) borrowed.add(pos);
        });
        BlockPos spare = poi.findAllClosestFirstWithType(t -> t.is(PoiTypes.HOME), p -> p.closerThan(colony.core(), colony.radius()) && usableBed(level, v, p)
                                && !borrowed.contains(p) && !unreachableBed(v, p, now), v.blockPosition(), radius, PoiManager.Occupancy.ANY)
                .map(pair -> pair.getSecond()).findFirst().orElse(null);
        if (spare != null) SPARE_BED.put(v, spare);
        return spare;
    }

    /** A colony task stopped while it steered the villager: hand the movement back to vanilla at once. */
    private static void releaseFinishedTaskWalk(Villager v, long now) {
        boolean tracked = TASK_STEERED.containsKey(v);
        if (!tracked && Nav.currentTarget(v, now) == null) return;
        if (VillagerUtil.isRunning(v, ColonyTask.class)) {
            if (Nav.currentTarget(v, now) != null) TASK_STEERED.put(v, Boolean.TRUE);
        } else if (tracked) {
            TASK_STEERED.remove(v);
            Nav.release(v);
        }
    }

    /** A hostile mob or colony threat within 24 blocks of the villager (sampled every 20 ticks, kept 100 ticks). */
    private static boolean threatNearby(ServerLevel level, Colony colony, Villager v, long now) {
        if ((now + v.getId()) % 20 == 0) {
            double r2 = CIVILIAN_ALARM_RANGE * CIVILIAN_ALARM_RANGE;
            LivingEntity colonyThreat = ThreatManager.findThreat(level, colony);
            boolean near = colonyThreat != null && colonyThreat.distanceToSqr(v) <= r2;
            if (!near) {
                near = !level.getEntitiesOfClass(Monster.class, v.getBoundingBox().inflate(CIVILIAN_ALARM_RANGE, 8, CIVILIAN_ALARM_RANGE),
                        m -> ThreatManager.isValidHostile(m) && (!(m instanceof NeutralMob n) || n.isAngry()) && m.distanceToSqr(v) <= r2).isEmpty();
            }
            if (near) LOCAL_THREAT.put(v, now);
        }
        Long last = LOCAL_THREAT.get(v);
        return last != null && now - last >= 0 && now - last <= 100;
    }

    private static boolean unreachableBed(Villager v, BlockPos pos, long now) {
        Map<BlockPos, Long> m = UNREACHABLE_BED.get(v);
        Long until = m == null ? null : m.get(pos);
        return until != null && now < until;
    }

    private static boolean usableBed(ServerLevel level, Villager v, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState s = level.getBlockState(pos);
        if (!s.is(BlockTags.BEDS) || Protection.playerProperty(level, pos)) return false;
        if (s.hasProperty(BedBlock.OCCUPIED) && s.getValue(BedBlock.OCCUPIED)) {
            return v.getSleepingPos().map(pos::equals).orElse(false);
        }
        return true;
    }

    // ---------------------------------------------------------------- food

    private static boolean foodRoutine(ServerLevel level, Colony colony, Villager v, boolean evening) {
        long now = level.getGameTime();
        SimpleContainer inv = v.getInventory();
        boolean hasFood = hasFood(inv);
        // Meals come from the home chest (Gathering); a villager only carries one bite for healing. Babies never fetch.
        boolean needs = evening && !v.isBaby() && !hasFood;
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
        if (!hasFood(inv)) {
            ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
            if (!food.isEmpty()) {
                ItemStack rest = inv.addItem(food);
                if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
            }
        }
        v.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
        return !hasFood(inv);
    }

    private static boolean hasFood(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ColonyStorage.isFood(inv.getItem(i))) return true;
        }
        return false;
    }
}
