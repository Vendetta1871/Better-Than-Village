package net.betterthanvillage.society;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.SpawnSpots;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.colony.StoragePlacer;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.CatchupConstruction;
import net.betterthanvillage.construction.HouseConstructionTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline (unloaded-colony) catch-up simulation (06 §7): elapsed-time bookkeeping, the math, gradual application. */
public final class OfflineSim {
    public static final long MIN_GAP = 1200;
    public static final long MAX_DEFERRED = 720000;
    private static final int STOCK_CAP = 4096;

    private OfflineSim() {
    }

    private static boolean enabled(ServerLevel level) {
        return ColonyConfig.get().village.offline_development && ColonyManager.terrainChangesAllowed(level);
    }

    private static boolean coreTicking(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        return level.shouldTickBlocksAt(ChunkPos.asLong(core)) && level.isPositionEntityTicking(core);
    }

    // ---------------------------------------------------------------- bookkeeping (06 §7.1)

    public static void bookkeeping(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (colony.simulatedUntil == now) return;
        boolean initial = colony.initialDevelopment;
        if (colony.population() < 2 && !initial) return;
        boolean ticking = coreTicking(level, colony) || (colony.lastMemberTick != Colony.NEVER && now - colony.lastMemberTick <= 1);
        long old = colony.simulatedUntil;
        boolean set = old != Colony.NEVER;
        long deferred = colony.deferredCatchupTicks;
        if (!(ticking || initial || !set || now <= old || now - old >= MIN_GAP || deferred != 0)) return;
        colony.simulatedUntil = now;
        if (!set || now <= old || !(now - old >= MIN_GAP || initial || deferred != 0) || !enabled(level)) return;
        long elapsed = (now - old < MIN_GAP && !initial && ticking) ? 0 : now - old;
        long ticks = Math.min(elapsed + deferred, ColonyConfig.get().village.offline_max_days * 24000L);
        if (ticks <= 0) return;
        if (!initial && shouldDefer(level, colony, ticks)) {
            colony.deferredCatchupTicks = Math.max(0, Math.min(MAX_DEFERRED, ticks));
            ColonyManager.markDirty(level);
            return;
        }
        simulate(level, colony, ticks);
    }

    /** Production short of need, a storage chunk unloaded and the loaded food cannot cover the gap. */
    private static boolean shouldDefer(ServerLevel level, Colony colony, long ticks) {
        double d = ticks / 24000.0;
        double produced = d * (120 * census(colony, ColonyRole.FARMER, false) + 90 * census(colony, ColonyRole.FISHERMAN, false)
                + 96 * census(colony, ColonyRole.RANCHER, false));
        double needed = d * Math.max(2, colony.population()) * 10;
        if (produced >= needed) return false;
        Set<BlockPos> positions = new LinkedHashSet<>(colony.storages);
        positions.addAll(colony.managedStorages.keySet());
        boolean unloaded = false;
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) {
                unloaded = true;
                break;
            }
        }
        if (!unloaded) return false;
        int loadedFood = 0;
        for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
            loadedFood += ColonyStorage.foodValue(slot.container());
        }
        return loadedFood + produced < needed;
    }

    private static int census(Colony colony, ColonyRole role, boolean initial) {
        int n = colony.laborCensus.getOrDefault(role, 0);
        return initial ? Math.max(1, n) : n;
    }

    // ---------------------------------------------------------------- simulation math (06 §7.2)

    public static void simulate(ServerLevel level, Colony colony, long ticks) {
        double d = ticks / 24000.0;
        colony.deferredCatchupTicks = 0;
        colony.catchupTicks += ticks;
        boolean initial = colony.initialDevelopment;
        if (initial) {
            if (colony.lastPopulation == 0) {
                colony.lastPopulation = 8;
                for (ColonyRole r : List.of(ColonyRole.ELDER, ColonyRole.BUILDER, ColonyRole.FARMER, ColonyRole.LUMBERJACK, ColonyRole.MINER,
                        ColonyRole.FISHERMAN, ColonyRole.RANCHER, ColonyRole.GUARD)) {
                    colony.laborCensus.put(r, 1);
                }
            }
            colony.initialDevelopment = false;
        }
        int p = Math.max(initial ? 8 : 2, colony.lastPopulation);
        int f = census(colony, ColonyRole.FARMER, initial), fi = census(colony, ColonyRole.FISHERMAN, initial),
                r = census(colony, ColonyRole.RANCHER, initial), l = census(colony, ColonyRole.LUMBERJACK, initial),
                m = census(colony, ColonyRole.MINER, initial), b = census(colony, ColonyRole.BUILDER, initial);
        ColonyStorage.recount(level, colony);
        double produced = d * (120 * f + 90 * fi + 96 * r);
        double needed = d * p * 10;
        double s = Prices.clamp((colony.cachedFood + produced) / Math.max(1, needed), 0, 1);
        int deficit = (int) Math.min(colony.cachedFood, Math.ceil(Math.max(0, needed - produced)));
        if (deficit > 0) {
            ColonyStorage.consumeNutrition(level, colony, deficit);
        }
        double sf = Math.max(0, produced - needed) / Math.max(1, produced);
        produce(colony, Items.BREAD, d * f * 24 * sf);
        produce(colony, Items.COOKED_COD, d * fi * 18 * sf);
        produce(colony, Items.COOKED_BEEF, d * r * 12 * sf);
        produce(colony, biomeLog(level, colony), d * s * l * 256);
        produce(colony, Items.COBBLESTONE, d * s * m * 384);
        produce(colony, Items.IRON_INGOT, d * s * m * 12);
        produce(colony, Items.COAL, d * s * m * 16);
        produce(colony, Items.DIAMOND, d * s * m * 0.5);
        if (s >= 0.75 && b > 0 && ColonyConfig.get().village.offline_max_days > 0) {
            double acc = colony.laborCredit + d * s * b * 1000;
            int whole = (int) Math.floor(acc + 1e-9);
            colony.laborCredit = fraction(acc - whole);
            colony.catchupLabor = (int) Math.min(12000, (long) colony.catchupLabor + whole);
            acc = colony.houseCredit + d * s * b;
            whole = (int) Math.floor(acc + 1e-9);
            colony.houseCredit = fraction(acc - whole);
            colony.catchupHouses = (int) Math.max(0, Math.min(4, (long) colony.catchupHouses + whole));
            markCatchupJobs(level, colony, colony.catchupHouses);
        }
        if (s >= 0.75) {
            double acc = colony.birthCredit + d * s * Math.max(1, p / 8);
            int whole = (int) Math.floor(acc + 1e-9);
            colony.birthCredit = fraction(acc - whole);
            colony.catchupBirths = (int) Math.max(0, Math.min(8, (long) colony.catchupBirths + whole));
        }
        colony.catchupExpires = Long.MAX_VALUE;
        ColonyManager.markDirty(level);
    }

    private static double fraction(double v) {
        return Math.max(0, Math.min(0.999999999, v));
    }

    private static void produce(Colony colony, Item item, double amount) {
        if (amount <= 0) return;
        double acc = colony.productionCredit.getOrDefault(item, 0.0) + amount;
        long whole = (long) Math.floor(acc + 1e-9);
        colony.productionCredit.put(item, fraction(acc - whole));
        int add = (int) Math.min(STOCK_CAP, whole);
        if (add > 0) {
            colony.catchupStock.put(item, Math.min(STOCK_CAP, colony.catchupStock.getOrDefault(item, 0) + add));
        }
    }

    static Item biomeLog(ServerLevel level, Colony colony) {
        String path = level.getBiome(colony.core()).unwrapKey().map(k -> k.location().getPath()).orElse("plains");
        if (path.contains("savanna")) return Items.ACACIA_LOG;
        if (path.contains("taiga") || path.contains("snow")) return Items.SPRUCE_LOG;
        return Items.OAK_LOG;
    }

    static Item biomeSapling(ServerLevel level, Colony colony) {
        Item log = biomeLog(level, colony);
        return log == Items.ACACIA_LOG ? Items.ACACIA_SAPLING : log == Items.SPRUCE_LOG ? Items.SPRUCE_SAPLING : Items.OAK_SAPLING;
    }

    /** Eligible for catch-up building: unowned, never completed, not COMPLETE, not BLOCKED without retry. */
    static boolean eligible(ConstructionJob job) {
        return job.owner == null && !job.completedOnce && job.status != JobStatus.COMPLETE
                && !(job.status == JobStatus.BLOCKED && job.retryAfter == 0);
    }

    /** Flags up to n eligible jobs as catch-up jobs; their builders stop and are unassigned. */
    static void markCatchupJobs(ServerLevel level, Colony colony, int n) {
        int marked = 0;
        for (ConstructionJob job : colony.jobs) {
            if (marked >= n) break;
            if (!eligible(job)) continue;
            marked++;
            job.catchup = true;
            if (job.builder != null) {
                Entity e = level.getEntity(job.builder);
                if (e instanceof Villager v) {
                    VillagerUtil.stopRunning(level, v, beh -> beh instanceof HouseConstructionTask);
                }
                job.releaseBuilder();
            }
        }
    }

    private static boolean catchupJobActive(Colony colony) {
        for (ConstructionJob job : colony.jobs) {
            if (job.catchup && job.status != JobStatus.COMPLETE) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- gradual application (06 §7.3)

    /** Applies part of the catch-up; returns the number of blocks used from the budget. */
    public static int catchupStep(ServerLevel level, Colony colony, int budget) {
        if (!enabled(level)) return 0;
        long now = level.getGameTime();
        int used = 0;
        if (!colony.catchupStock.isEmpty() && ColonyStorage.usable(level, colony).isEmpty()) {
            BlockPos site = StoragePlacer.findSite(level, colony);
            if (site != null && StoragePlacer.placeDoubleChest(level, colony, site, StorageKind.GENERAL)) {
                colony.starterWarehousePlaced = true;
            }
        }
        if (colony.catchupHouses > 0 && colony.catchupLabor > 0 && budget > 0) {
            markCatchupJobs(level, colony, colony.catchupHouses);
            autoExpand(level, colony);
        }
        if (now % 5 == 0) {
            depositStock(level, colony);
        }
        if (colony.catchupBirths > 0 && now % 10 == 0 && coreTicking(level, colony)) {
            birth(level, colony);
        }
        used += roads(level, colony, budget - used);
        used += construction(level, colony, budget - used);
        return used;
    }

    private static void autoExpand(ServerLevel level, Colony colony) {
        if (colony.completedHouseCount() >= ColonyConfig.get().village.max_constructed_houses) {
            colony.catchupHouses = 0;
            return;
        }
        if (!colony.hasActiveConstruction()) {
            CatchupConstruction.planCatchupHouse(level, colony);
        }
    }

    private static void depositStock(ServerLevel level, Colony colony) {
        if (colony.catchupStock.isEmpty() || ColonyStorage.usable(level, colony).isEmpty()) return;
        boolean holdBuilding = catchupJobActive(colony);
        int entries = 0;
        Iterator<Map.Entry<Item, Integer>> it = colony.catchupStock.entrySet().iterator();
        while (it.hasNext() && entries < 16) {
            Map.Entry<Item, Integer> e = it.next();
            ItemStack probe = new ItemStack(e.getKey());
            if (holdBuilding && (probe.is(ItemTags.LOGS) || probe.is(ItemTags.STONE_CRAFTING_MATERIALS))) continue;
            entries++;
            int n = Math.min(e.getValue(), probe.getMaxStackSize());
            ItemStack stack = probe.copyWithCount(n);
            ColonyStorage.deposit(level, colony, stack);
            int moved = n - stack.getCount();
            if (moved <= 0) continue;
            if (e.getValue() - moved <= 0) {
                it.remove();
            } else {
                e.setValue(e.getValue() - moved);
            }
        }
        ColonyStorage.recount(level, colony);
        ColonyManager.markDirty(level);
    }

    private static void birth(ServerLevel level, Colony colony) {
        List<Villager> members = ColonyManager.members(level, colony);
        int beds = (int) level.getPoiManager().getCountInRange(t -> t.is(PoiTypes.HOME), colony.core(), colony.radius(), PoiManager.Occupancy.ANY);
        int cap = Math.min(ColonyConfig.get().village.max_population_per_bell, beds);
        if (members.size() < 2 || members.size() >= cap) return;
        BlockPos spot = SpawnSpots.find(level, colony, level.getGameTime() / 10);
        if (spot == null) return;
        Villager baby = SpawnSpots.createVillager(level, spot, MobSpawnType.BREEDING);
        if (baby == null) return;
        baby.setAge(-1800);
        baby.setVillagerData(baby.getVillagerData().setProfession(VillagerProfession.NONE).setType(members.get(0).getVillagerData().getType()));
        boolean ok = ColonyStorage.transaction(level, colony, () -> ColonyStorage.consumeNutrition(level, colony, 24) && level.addFreshEntity(baby), List.of());
        if (!ok) return;
        colony.setRole(baby.getUUID(), ColonyRole.CITIZEN, level.getGameTime());
        colony.catchupBirths--;
        ColonyManager.markDirty(level);
    }

    /** Road queue placements while catch-up houses are pending: one log or stone and one labor per path block. */
    private static int roads(ServerLevel level, Colony colony, int budget) {
        int placed = 0, inspected = 0;
        int limit = Math.min(32, budget);
        while (colony.catchupHouses > 0 && colony.catchupLabor > 0 && !colony.roadQueue.isEmpty() && placed < limit && inspected < 64) {
            inspected++;
            BlockPos pos = colony.roadQueue.get(0);
            if (!level.isLoaded(pos)) break;
            BlockState state = level.getBlockState(pos);
            if (Deeds.isRoadBlock(state) || !validRoad(level, pos, state)) {
                colony.roadQueue.remove(0);
                continue;
            }
            Item material = roadMaterial(level, colony);
            if (material == null) break;
            if (!net.betterthanvillage.ai.work.SafeEdit.place(level, pos, Blocks.DIRT_PATH.defaultBlockState(), 3)) {
                colony.roadQueue.remove(0);
                continue;
            }
            if (!takeMaterial(level, colony, material)) {
                net.betterthanvillage.colony.SafeEditScope.run(() -> level.setBlock(pos, state, 3));
                break;
            }
            colony.roadQueue.remove(0);
            colony.catchupLabor--;
            placed++;
        }
        if (placed > 0) ColonyManager.markDirty(level);
        return placed;
    }

    private static boolean validRoad(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState above = level.getBlockState(pos.above());
        return state.is(BlockTags.DIRT) && level.getBlockEntity(pos) == null && (above.isAir() || above.canBeReplaced())
                && level.getFluidState(pos).isEmpty() && level.getFluidState(pos.above()).isEmpty();
    }

    /** A log or stone item from the virtual stock first, then from storage; null if none. */
    private static Item roadMaterial(ServerLevel level, Colony colony) {
        for (Item item : colony.catchupStock.keySet()) {
            ItemStack s = new ItemStack(item);
            if (s.is(ItemTags.LOGS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS)) return item;
        }
        return ColonyStorage.has(level, colony, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS)) ? Items.AIR : null;
    }

    private static boolean takeMaterial(ServerLevel level, Colony colony, Item item) {
        if (item != Items.AIR) {
            int n = colony.catchupStock.getOrDefault(item, 0);
            if (n > 0) {
                if (n == 1) colony.catchupStock.remove(item);
                else colony.catchupStock.put(item, n - 1);
                return true;
            }
        }
        return !ColonyStorage.withdrawOne(level, colony, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS)).isEmpty();
    }

    /** The first flagged catch-up job: unflag without labor, else let construction place blocks; finish bookkeeping. */
    private static int construction(ServerLevel level, Colony colony, int budget) {
        ConstructionJob job = null;
        for (ConstructionJob j : colony.jobs) {
            if (j.catchup && j.status != JobStatus.COMPLETE) {
                job = j;
                break;
            }
        }
        if (job == null || budget <= 0) return 0;
        if (colony.catchupLabor <= 0) {
            job.catchup = false;
            return 0;
        }
        int placed = Math.max(0, CatchupConstruction.step(level, colony, budget));
        if (job.status == JobStatus.COMPLETE) {
            job.catchup = false;
            colony.catchupHouses = Math.max(0, colony.catchupHouses - 1);
            ColonyManager.markDirty(level);
        }
        return placed;
    }

    // ---------------------------------------------------------------- status

    /** Catch-up summary line (status, film status). */
    public static String summary(ServerLevel level, Colony colony) {
        int stock = 0;
        for (int n : colony.catchupStock.values()) stock += n;
        String first = "уровень=" + level(colony) + ", учтено отсутствия=" + colony.catchupTicks / 20 + " с, запас на выдачу=" + stock
                + ", труд=" + colony.catchupLabor + ", домов к достройке=" + colony.catchupHouses + ", прирост=" + colony.catchupBirths;
        int active = 0;
        for (ConstructionJob job : colony.jobs) if (job.catchup && job.status != JobStatus.COMPLETE) active++;
        return first + "; склады=" + ColonyStorage.usable(level, colony).size() + ", свободных слотов=" + ColonyStorage.emptySlots(level, colony)
                + "; еда/брёвна/камень=" + colony.cachedFood + "/" + colony.cachedLogs + "/" + colony.cachedStone
                + "; стройка=" + active + "; учтено до тика=" + (colony.simulatedUntil == Colony.NEVER ? "-" : String.valueOf(colony.simulatedUntil))
                + "; ожидает данных склада=" + colony.deferredCatchupTicks / 20 + " с";
    }

    private static String level(Colony colony) {
        if (colony.catchupLabor > 0 || colony.catchupHouses > 0 || !colony.catchupStock.isEmpty() || colony.catchupBirths > 0) return "активно";
        return colony.catchupTicks > 0 ? "выполнено" : "нет";
    }

    public static void clearCaches() {
    }
}
