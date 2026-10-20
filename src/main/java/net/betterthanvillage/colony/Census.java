package net.betterthanvillage.colony;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.HouseConstructionTask;
import net.betterthanvillage.defense.GolemManager;
import net.betterthanvillage.economy.Households;
import net.betterthanvillage.society.Economy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/** The 100-tick colony census and the maintenance steps that follow it. */
public final class Census {
    private Census() {
    }

    public static void run(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        List<Villager> members = ColonyManager.members(level, colony);
        if (bootstrap(level, colony, members)) {
            members = ColonyManager.members(level, colony);
        }
        List<Villager> adults = ColonyManager.adults(members);
        colony.lastPopulation = members.size();
        colony.lastBeds = countBeds(level, colony);
        colony.lastCensusTick = now;
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (t >= 9000 && t < 12000) {
            eveningHandout(level, colony, members);
        }
        ColonyStorage.recount(level, colony);
        TerrainSurvey.step(level, colony);
        Professions.assign(level, colony, adults);
        for (Villager v : adults) {
            Professions.applyRole(level, colony, v);
        }
        Households.step(level, colony, adults);
        laborCensus(colony, adults);
        Economy.step(level, colony);
        GolemManager.step(level, colony);
        jobHousekeeping(level, colony, now);
        breed(level, colony, members, adults);
    }

    /** Steps run right after the census: managed pair repair, house storages. */
    public static void afterCensus(ServerLevel level, Colony colony) {
        if (ColonyManager.terrainChangesAllowed(level)) {
            repairManagedPairs(level, colony);
            placeHouseStorage(level, colony);
        }
    }

    // ---------------------------------------------------------------- bootstrap

    /**
     * Initial top-up of a generated village to 8 adults (once). Abandoned (zombie) villages are never topped up;
     * colonies created by colonization/commands start with the flag set. Returns true if villagers were spawned.
     */
    private static boolean bootstrap(ServerLevel level, Colony colony, List<Villager> members) {
        if (colony.professionBootstrapComplete) return false;
        if (colony.abandonedVillage) {
            colony.professionBootstrapComplete = true;
            ColonyManager.markDirty(level);
            return false;
        }
        int adults = 0;
        for (Villager v : members) if (!v.isBaby()) adults++;
        if (adults >= 8) {
            colony.professionBootstrapComplete = true;
            ColonyManager.markDirty(level);
            return false;
        }
        colony.bootstrapAttempts++;
        if (colony.bootstrapAttempts < 2) return false;
        int missing = 8 - adults;
        int spawned = 0;
        for (int i = 0; i < missing; i++) {
            if (SpawnSpots.spawnAdult(level, colony, i) == null) break;
            spawned++;
        }
        if (adults + spawned >= 8) {
            colony.professionBootstrapComplete = true;
        }
        ColonyManager.markDirty(level);
        return spawned > 0;
    }

    // ---------------------------------------------------------------- beds

    private static final String BED_CACHE = "census_beds";
    private static final int BED_DISK_READS = 8;

    /**
     * HOME POIs in this colony's territory (nearest-core circle, so overlapping colonies do not double count).
     * Loaded chunks are counted live; unloaded chunks reuse the count from when they were last seen, and at most
     * 8 never-seen unloaded columns per census are read from disk. While any column is still unknown the
     * previous total is kept as a floor.
     */
    private static int countBeds(ServerLevel level, Colony colony) {
        Long2IntOpenHashMap cache = colony.runtime(BED_CACHE, Long2IntOpenHashMap::new);
        BlockPos core = colony.core();
        int r = colony.radius();
        long r2 = (long) r * r;
        PoiManager poi = level.getPoiManager();
        int reads = 0;
        int total = 0;
        boolean unknown = false;
        for (int cx = (core.getX() - r) >> 4; cx <= (core.getX() + r) >> 4; cx++) {
            for (int cz = (core.getZ() - r) >> 4; cz <= (core.getZ() + r) >> 4; cz++) {
                long nx = Math.max(cx << 4, Math.min(core.getX(), (cx << 4) + 15)) - core.getX();
                long nz = Math.max(cz << 4, Math.min(core.getZ(), (cz << 4) + 15)) - core.getZ();
                if (nx * nx + nz * nz > r2) continue;
                long key = ChunkPos.asLong(cx, cz);
                if (!level.hasChunk(cx, cz)) {
                    if (cache.containsKey(key)) {
                        total += cache.get(key);
                        continue;
                    }
                    if (reads >= BED_DISK_READS) {
                        unknown = true;
                        continue;
                    }
                    reads++;
                }
                int n = (int) poi.getInChunk(h -> h.is(PoiTypes.HOME), new ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                        .filter(rec -> ColonyManager.colonyAt(level, rec.getPos()) == colony)
                        .count();
                cache.put(key, n);
                total += n;
            }
        }
        return unknown ? Math.max(total, colony.lastBeds) : total;
    }

    // ---------------------------------------------------------------- evening food

    private static void eveningHandout(ServerLevel level, Colony colony, List<Villager> members) {
        for (Villager v : members) {
            SimpleContainer inv = v.getInventory();
            boolean hasFood = false;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (ColonyStorage.isFood(inv.getItem(i))) {
                    hasFood = true;
                    break;
                }
            }
            int target = (!v.isBaby() && v.getAge() == 0) ? 12 : hasFood ? 0 : 1;
            int value = ColonyStorage.foodValue(inv);
            for (int k = 0; k < 12 && value < target; k++) {
                ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
                if (food.isEmpty()) break;
                value += ColonyStorage.foodValue(food);
                ItemStack rest = inv.addItem(food);
                if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
            }
            if (!hasFoodNow(inv)) {
                ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
                if (!food.isEmpty()) {
                    ItemStack rest = inv.addItem(food);
                    if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
                }
            }
        }
    }

    private static boolean hasFoodNow(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ColonyStorage.isFood(inv.getItem(i))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- elder

    public static void maintainElder(ServerLevel level, Colony colony) {
        Professions.maintainElder(level, colony, ColonyManager.adultMembers(level, colony));
    }

    // ---------------------------------------------------------------- labor census

    private static void laborCensus(Colony colony, List<Villager> adults) {
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (ColonyRole r : ColonyRole.values()) counts.put(r, 0);
        for (Villager v : adults) {
            if (colony.isFounderParty(v.getUUID())) continue;
            counts.merge(colony.roleOf(v.getUUID()), 1, Integer::sum);
        }
        colony.laborCensus.clear();
        colony.laborCensus.putAll(counts);
    }

    // ---------------------------------------------------------------- jobs

    private static void jobHousekeeping(ServerLevel level, Colony colony, long now) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean day = t >= 1000 && t < 9000;
        for (ConstructionJob job : colony.jobs) {
            if (job.status == JobStatus.BLOCKED && job.retryAfter > 0 && now >= job.retryAfter) {
                job.status = JobStatus.QUEUED;
                job.retryAfter = 0;
                job.builder = null;
            }
            if (job.builder == null) continue;
            net.minecraft.world.entity.Entity e = level.getEntity(job.builder);
            boolean valid = e instanceof Villager v && v.isAlive() && !v.isBaby()
                    && ColonyManager.colonyOf(level, v) == colony && colony.roleOf(v.getUUID()) == ColonyRole.MASON;
            boolean stalled = false;
            if (valid && day && job.status == JobStatus.BUILDING && job.lastProgressTick != Long.MIN_VALUE) {
                boolean running = VillagerUtil.isRunning((Villager) e, HouseConstructionTask.class);
                stalled = now - job.lastProgressTick > (running ? 1800 : 200);
            }
            if (!valid || stalled) {
                job.releaseBuilder();
            }
        }
    }

    // ---------------------------------------------------------------- population

    /** Reasons blocking growth; empty means growth is allowed. */
    public static List<String> populationGate(ServerLevel level, Colony colony) {
        return populationGate(level, colony, ColonyManager.members(level, colony));
    }

    public static List<String> populationGate(ServerLevel level, Colony colony, List<Villager> members) {
        List<String> reasons = new ArrayList<>();
        int pop = colony.lastPopulation;
        int max = ColonyConfig.get().village.max_population_per_bell;
        if (pop >= max) reasons.add("лимит населения " + max);
        if (colony.lastBeds <= pop) reasons.add("нет лишней кровати");
        int food = colony.cachedFood + ColonyStorage.carriedFood(members);
        int need = Math.max(24, 6 * pop);
        if (food < need) reasons.add("еда всего " + food + "/" + need);
        long now = level.getGameTime();
        if (colony.recentThreat(now, 1200)) {
            reasons.add("после угрозы осталось " + ((1200 - (now - colony.lastThreatTick)) / 20) + " с");
        }
        return reasons;
    }

    public static boolean growthAllowed(ServerLevel level, Villager villager) {
        Colony colony = ColonyManager.colonyOf(level, villager);
        return colony == null || populationGate(level, colony).isEmpty();
    }

    private static void breed(ServerLevel level, Colony colony, List<Villager> members, List<Villager> adults) {
        long now = level.getGameTime();
        if (now < colony.nextBreedingTick || colony.cachedFood < 24 || !populationGate(level, colony, members).isEmpty()) return;
        BlockPos core = colony.core();
        List<Villager> parents = new ArrayList<>();
        for (Villager v : adults) {
            if (v.getAge() == 0) parents.add(v);
        }
        if (parents.size() < 2) return;
        parents.sort(Comparator.comparingDouble(v -> v.distanceToSqr(core.getX() + 0.5, core.getY(), core.getZ() + 0.5)));
        BlockPos spot = SpawnSpots.find(level, colony, Math.floorDiv(now, 100L));
        if (spot == null) return;
        Villager baby = SpawnSpots.createVillager(level, spot, MobSpawnType.BREEDING);
        if (baby == null) return;
        baby.setAge(-1800);
        boolean ok = ColonyStorage.transaction(level, colony,
                () -> ColonyStorage.consumeNutrition(level, colony, 24) && level.addFreshEntity(baby), List.of());
        if (!ok) return;
        parents.get(0).setAge(1200);
        parents.get(1).setAge(1200);
        ColonyRole inherited = Professions.inherit(colony, parents.get(0).getUUID(), parents.get(1).getUUID(), level.random);
        if (inherited != null) colony.pendingProfessions.put(baby.getUUID(), inherited);
        colony.nextBreedingTick = now + 300;
        level.sendParticles(ParticleTypes.HEART, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0);
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- managed storages

    private static void repairManagedPairs(ServerLevel level, Colony colony) {
        ColonySavedData data = ColonyManager.data(level);
        for (var entry : new ArrayList<>(colony.managedChestPairs.entrySet())) {
            BlockPos left = entry.getKey();
            BlockPos right = entry.getValue();
            if (!level.isLoaded(left) || !level.isLoaded(right)) continue;
            if (data.isPlayerBlock(left) || data.isPlayerBlock(right)) continue;
            BlockState ls = level.getBlockState(left);
            BlockState rs = level.getBlockState(right);
            if (!(ls.getBlock() instanceof ChestBlock) || !(rs.getBlock() instanceof ChestBlock)) continue;
            Direction facing = ls.getValue(ChestBlock.FACING);
            if ((facing != Direction.NORTH && facing != Direction.SOUTH) || blocked(level, left, facing)) {
                Direction chosen = StoragePlacer.chooseFacing(level, colony, left);
                if (chosen != null) facing = chosen;
            }
            // An x-axis pair can only face north or south; leave it alone rather than write a broken pair.
            if (facing != Direction.NORTH && facing != Direction.SOUTH) continue;
            ChestType expLeft = facing == Direction.NORTH ? ChestType.LEFT : ChestType.RIGHT;
            ChestType expRight = facing == Direction.NORTH ? ChestType.RIGHT : ChestType.LEFT;
            if (ls.getValue(ChestBlock.FACING) != facing || rs.getValue(ChestBlock.FACING) != facing
                    || ls.getValue(ChestBlock.TYPE) != expLeft || rs.getValue(ChestBlock.TYPE) != expRight) {
                StoragePlacer.writeChestPair(level, left, facing);
            }
        }
    }

    private static boolean blocked(ServerLevel level, BlockPos left, Direction side) {
        for (BlockPos cell : new BlockPos[]{left, left.east()}) {
            BlockPos n = cell.relative(side);
            if (!level.isLoaded(n) || !level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    /** At most one house storage per pass, for completed colony-owned houses without one. */
    private static void placeHouseStorage(ServerLevel level, Colony colony) {
        int index = 0;
        for (ConstructionJob job : colony.jobs) {
            if (!job.isComplete() || job.owner != null) continue;
            int houseIndex = index++;
            BoundingBox box = job.box();
            BoundingBox near = new BoundingBox(box.minX() - 2, box.minY() - 1, box.minZ() - 2, box.maxX() + 2, box.maxY() + 2, box.maxZ() + 2);
            boolean has = false;
            for (BlockPos pos : colony.managedStorages.keySet()) {
                if (near.isInside(pos)) {
                    has = true;
                    break;
                }
            }
            if (has) continue;
            BlockPos site = interiorSpot(level, box);
            if (site == null) site = ringSpot(level, colony, box);
            if (site == null) continue;
            if (!ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 4)) return;
            StoragePlacer.placeDoubleChest(level, colony, site, StorageKind.forHouse(houseIndex));
            return;
        }
    }

    private static BlockPos interiorSpot(ServerLevel level, BoundingBox box) {
        for (int y = box.minY() + 1; y <= box.maxY() - 1; y++) {
            for (int x = box.minX(); x <= box.maxX() - 1; x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!box.isInside(pos.east())) continue;
                    if (!StoragePlacer.canPlace(level, pos)) continue;
                    if (adjacentToCollision(level, pos) || adjacentToCollision(level, pos.east())) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static boolean adjacentToCollision(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.isLoaded(n)) continue;
            if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    private static BlockPos ringSpot(ServerLevel level, Colony colony, BoundingBox box) {
        for (int d = 1; d <= 2; d++) {
            for (int x = box.minX() - d; x <= box.maxX() + d; x++) {
                for (int z = box.minZ() - d; z <= box.maxZ() + d; z++) {
                    boolean onRing = x == box.minX() - d || x == box.maxX() + d || z == box.minZ() - d || z == box.maxZ() + d;
                    if (!onRing || !level.hasChunk(x >> 4, z >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (StoragePlacer.validSite(level, colony, pos)) return pos;
                }
            }
        }
        return null;
    }
}
