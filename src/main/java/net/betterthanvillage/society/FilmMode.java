package net.betterthanvillage.society;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.colony.StoragePlacer;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Filming mode (06 §8): scene preparation commands and the accelerated "max" development run. */
public final class FilmMode {
    public static final long MAX_DURATION = 12000;
    private static final Set<ResourceKey<Level>> ON = new HashSet<>();
    private static final Map<UUID, MaxRun> RUNS = new HashMap<>();

    /** One accelerated development run. */
    static final class MaxRun {
        final ResourceKey<Level> dimension;
        final long start;
        final long end;
        final int target;
        final int baseline;

        MaxRun(ResourceKey<Level> dimension, long start, int target, int baseline) {
            this.dimension = dimension;
            this.start = start;
            this.end = start + MAX_DURATION;
            this.target = target;
            this.baseline = baseline;
        }

        int built(Colony colony) {
            return Math.max(0, colony.completedHouseCount() - baseline);
        }
    }

    private FilmMode() {
    }

    public static boolean isOn(ServerLevel level) {
        return ON.contains(level.dimension());
    }

    static void setOn(ServerLevel level, boolean on) {
        if (on) {
            ON.add(level.dimension());
        } else {
            ON.remove(level.dimension());
            for (Iterator<Map.Entry<UUID, MaxRun>> it = RUNS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, MaxRun> e = it.next();
                if (e.getValue().dimension == level.dimension()) {
                    Colony c = ColonyManager.data(level).colony(e.getKey());
                    if (c != null) finish(c);
                    it.remove();
                }
            }
        }
    }

    public static boolean maxRunning(Colony colony) {
        return RUNS.containsKey(colony.id());
    }

    static MaxRun run(Colony colony) {
        return RUNS.get(colony.id());
    }

    // ---------------------------------------------------------------- max run

    static void startMax(ServerLevel level, Colony colony) {
        int target = Math.min(12, ColonyConfig.get().village.max_constructed_houses);
        RUNS.put(colony.id(), new MaxRun(level.dimension(), level.getGameTime(), target, colony.completedHouseCount()));
        supply(level, colony);
        population(level, colony, Math.min(48, ColonyConfig.get().village.max_population_per_bell));
        colony.filmingDevelopment = true;
        ColonyManager.markDirty(level);
    }

    static boolean stopMax(ServerLevel level, Colony colony) {
        if (RUNS.remove(colony.id()) == null) return false;
        finish(colony);
        ColonyManager.markDirty(level);
        return true;
    }

    private static void finish(Colony colony) {
        colony.filmingDevelopment = false;
        colony.catchupLabor = 0;
        colony.catchupHouses = 0;
        colony.catchupExpires = 0;
        for (ConstructionJob job : colony.jobs) {
            if (job.owner == null) job.catchup = false;
        }
    }

    public static void tick(ServerLevel level) {
        if (RUNS.isEmpty()) return;
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, MaxRun>> it = RUNS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, MaxRun> e = it.next();
            MaxRun run = e.getValue();
            if (run.dimension != level.dimension()) continue;
            Colony colony = ColonyManager.data(level).colony(e.getKey());
            if (colony == null) {
                it.remove();
                continue;
            }
            int remaining = run.target - run.built(colony);
            if (now >= run.end || remaining <= 0) {
                finish(colony);
                it.remove();
                ColonyManager.markDirty(level);
                continue;
            }
            if ((now - run.start) % 20 == 0) {
                colony.catchupHouses = Math.min(4, remaining);
                colony.catchupLabor = 12000;
                colony.catchupExpires = Long.MAX_VALUE;
                colony.filmingDevelopment = true;
                for (ConstructionJob job : colony.jobs) {
                    if (job.owner == null && !job.completedOnce && job.status != JobStatus.COMPLETE) job.catchup = true;
                }
            }
            if ((now - run.start) % 200 == 0 && now != run.start) {
                supply(level, colony);
            }
        }
    }

    public static void clearCaches() {
        ON.clear();
        RUNS.clear();
    }

    // ---------------------------------------------------------------- supply

    /** Result of a supply run: total added and total that did not fit; -1 added when no storage could be made. */
    record Supply(int added, int overflow) {
    }

    static Supply supply(ServerLevel level, Colony colony) {
        if (ColonyStorage.usable(level, colony).isEmpty()) {
            BlockPos site = StoragePlacer.findSite(level, colony);
            if (site == null || !StoragePlacer.placeDoubleChest(level, colony, site, StorageKind.GENERAL)) {
                return new Supply(-1, 0);
            }
            colony.starterWarehousePlaced = true;
        }
        Map<Item, Integer> targets = new LinkedHashMap<>();
        targets.put(Items.BREAD, 256);
        targets.put(OfflineSim.biomeLog(level, colony), 512);
        targets.put(Items.COBBLESTONE, 768);
        targets.put(Items.IRON_INGOT, 192);
        targets.put(Items.COAL, 128);
        targets.put(Items.DIAMOND, 16);
        targets.put(Items.WHEAT_SEEDS, 64);
        targets.put(Items.WHEAT, 64);
        targets.put(Items.CARROT, 32);
        targets.put(Items.POTATO, 32);
        targets.put(Items.PUMPKIN, 32);
        targets.put(Items.PUMPKIN_SEEDS, 32);
        targets.put(Items.EMERALD, 128);
        targets.put(Items.WHITE_WOOL, 64);
        targets.put(Items.GLASS, 128);
        targets.put(Items.SAND, 256);
        targets.put(Items.CLAY_BALL, 128);
        targets.put(Items.TORCH, 64);
        targets.put(Items.STICK, 64);
        targets.put(Items.STRING, 32);
        targets.put(Items.ARROW, 64);
        targets.put(Items.BUCKET, 4);
        targets.put(Items.SHEARS, 2);
        targets.put(OfflineSim.biomeSapling(level, colony), 32);
        int added = 0, overflow = 0;
        for (Map.Entry<Item, Integer> e : targets.entrySet()) {
            Item item = e.getKey();
            int missing = e.getValue() - ColonyStorage.count(level, colony, s -> s.is(item));
            int max = new ItemStack(item).getMaxStackSize();
            while (missing > 0) {
                int n = Math.min(missing, max);
                missing -= n;
                ItemStack stack = new ItemStack(item, n);
                ColonyStorage.deposit(level, colony, stack);
                added += n - stack.getCount();
                if (!stack.isEmpty()) {
                    overflow += stack.getCount() + missing;
                    break;
                }
            }
        }
        ColonyStorage.recount(level, colony);
        ColonyManager.markDirty(level);
        return new Supply(added, overflow);
    }

    // ---------------------------------------------------------------- population

    /** Spawns adult villagers on free spots (rings 2..16, +-4 Y of the core) until the population reaches n. */
    static int population(ServerLevel level, Colony colony, int n) {
        int cap = Math.min(64, ColonyConfig.get().village.max_population_per_bell);
        int want = Math.min(n, cap) - ColonyManager.members(level, colony).size();
        int added = 0;
        BlockPos core = colony.core();
        VillagerType type = VillagerType.byBiome(level.getBiome(core));
        outer:
        for (int r = 2; r <= 16 && added < want; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (added >= want) break outer;
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = core.getX() + dx, z = core.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (Math.abs(p.getY() - core.getY()) > 4 || !Nav.standable(level, p)) continue;
                    if (ColonyManager.data(level).inLand(p) || !level.getEntitiesOfClass(LivingEntity.class, new AABB(p).inflate(0.2)).isEmpty()) continue;
                    Villager v = EntityType.VILLAGER.create(level);
                    if (v == null) continue;
                    v.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360f, 0);
                    v.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.COMMAND, null);
                    v.setVillagerData(v.getVillagerData().setType(type));
                    v.setPersistenceRequired();
                    if (level.addFreshEntity(v)) added++;
                }
            }
        }
        if (added > 0) ColonyManager.markDirty(level);
        return added;
    }

    // ---------------------------------------------------------------- roles

    static boolean specialRole(ColonyRole role) {
        return role == ColonyRole.ELDER || role == ColonyRole.FOUNDER || role == ColonyRole.CITIZEN;
    }

    /** Assigns a role to the n nearest eligible villagers (not elder/founder, not already in the role). */
    static List<Villager> assignRole(ServerLevel level, Colony colony, ColonyRole role, int n, BlockPos from) {
        List<Villager> candidates = new ArrayList<>();
        for (Villager v : ColonyManager.adultMembers(level, colony)) {
            ColonyRole r = colony.roleOf(v.getUUID());
            if (r == ColonyRole.ELDER || r == ColonyRole.FOUNDER || r == role || colony.isFounderParty(v.getUUID())) continue;
            candidates.add(v);
        }
        candidates.sort(Comparator.comparingDouble(v -> v.distanceToSqr(from.getX() + 0.5, from.getY(), from.getZ() + 0.5)));
        List<Villager> out = new ArrayList<>();
        for (Villager v : candidates) {
            if (out.size() >= n) break;
            setRole(level, colony, v, role);
            out.add(v);
        }
        return out;
    }

    static void setRole(ServerLevel level, Colony colony, Villager v, ColonyRole role) {
        VillagerUtil.stopModTasks(level, v);
        Nav.reset(v);
        colony.setRole(v.getUUID(), role, level.getGameTime());
        Professions.applyRole(level, colony, v);
        ColonyManager.markDirty(level);
    }

    /** Removes all food (or logs) from public storages; returns the removed item count. */
    static int shortage(ServerLevel level, Colony colony, boolean food) {
        int removed = 0;
        for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
            for (int i = 0; i < slot.container().getContainerSize(); i++) {
                ItemStack s = slot.container().getItem(i);
                if (s.isEmpty()) continue;
                if (food ? ColonyStorage.isFood(s) : s.is(net.minecraft.tags.ItemTags.LOGS)) {
                    removed += s.getCount();
                    slot.container().setItem(i, ItemStack.EMPTY);
                }
            }
            slot.container().setChanged();
        }
        ColonyStorage.recount(level, colony);
        ColonyManager.markDirty(level);
        return removed;
    }
}
