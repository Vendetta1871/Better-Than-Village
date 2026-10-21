package net.betterthanvillage.colony;

import net.betterthanvillage.api.VillageCapability;
import net.betterthanvillage.economy.BirthOrder;
import net.betterthanvillage.economy.Household;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One colony: a generated village anchored on its bell ("core").
 * Holds both persisted state (see {@link #save}) and runtime-only caches.
 */
public final class Colony implements VillageCapability {
    public static final long NEVER = Long.MIN_VALUE;
    public static final long ROLE_CHANGED_DEFAULT = -4611686018427387904L;

    // ---- identity ----
    private UUID id;
    private BlockPos core;
    private int radius = net.betterthanvillage.config.ColonyConfig.get().land.territory_start_radius;
    /** The country the village belongs to (docs_v2/06-politics.md); a generated village is its own country. */
    public UUID country;
    /** Start chunk of the generated village structure; null for a founded village ({@link Domain}). */
    public net.minecraft.world.level.ChunkPos seat;

    // ---- threat / offline ----
    public long lastThreatTick = NEVER;
    public long simulatedUntil = NEVER;
    public long catchupTicks;
    public long deferredCatchupTicks;
    public int catchupLabor;
    public int catchupBirths;
    public int catchupHouses;
    public boolean filmingDevelopment;
    public long catchupExpires;
    public boolean initialDevelopment;
    public double houseCredit;
    public double birthCredit;
    public double laborCredit;
    public final Map<Item, Double> productionCredit = new LinkedHashMap<>();
    public int lastPopulation;
    public int lastBeds;
    public final EnumMap<ColonyRole, Integer> laborCensus = new EnumMap<>(ColonyRole.class);
    public final Map<Item, Integer> catchupStock = new LinkedHashMap<>();

    // ---- construction / defense ----
    public final FortificationState fortifications = new FortificationState();
    public UUID assaultAttacker;
    public long assaultUntil;
    /** Repair queue: position -> desired state. */
    public final Map<BlockPos, BlockState> repairs = new LinkedHashMap<>();
    public final Set<BlockPos> farmland = new LinkedHashSet<>();
    public final List<BlockPos> roadQueue = new ArrayList<>();
    public BlockPos roadAttemptHead;
    public int roadStallTicks;
    public int skippedRoadSegments;
    public final List<BlockPos> mineRoute = new ArrayList<>();
    public int mineDirection;
    public long nextGolemTick;
    public long nextEconomyTick;
    public int economyCursor;
    public int soldTrades;
    public int boughtTrades;
    public int deepMiningTrips;
    public int deepMiningYield;
    public int mineStallTrips;
    public int lastMineRouteSize;

    // ---- society ----
    public UUID elder;
    public UUID founder;
    public boolean founderLeaderLost;
    public boolean founderReturning;
    public boolean founderCargoReserved;
    public final Map<Item, Integer> founderCargo = new LinkedHashMap<>();
    public final List<UUID> founderCompanions = new ArrayList<>();
    /** Emeralds each search party member took from its home chests (docs_v2/04-land.md §Expansion). */
    public final Map<UUID, Integer> founderPurses = new LinkedHashMap<>();
    public BlockPos founderTarget;
    public long founderStartedTick;
    public int founderVisits;
    public int founderCapital;
    public long nextFounderTick;
    public UUID propertyOwner;
    public int propertyLevel;
    public int stolenItems;
    public long nextBreedingTick;
    public BlockPos livestockPen;
    public boolean professionBootstrapComplete;
    public boolean starterWarehousePlaced;
    /** Generated as an abandoned (zombie) village variant: no initial villager bootstrap. */
    public boolean abandonedVillage;
    /** Loaded ticks spent without a bell and without members (dissolution timer). */
    public long deadTicks;
    public long lastProfessionRebalanceTick = NEVER;

    // ---- storage ----
    /** Storages created by the mod: position -> kind (insertion ordered). */
    public final Map<BlockPos, StorageKind> managedStorages = new LinkedHashMap<>();
    /** Double-chest pairs of managed storages: left -> right (right == left.east()). */
    public final Map<BlockPos, BlockPos> managedChestPairs = new LinkedHashMap<>();

    // ---- roles ----
    /** Bumped whenever a villager gains a role entry in any colony (membership index invalidation). */
    static int roleVersion;
    public final Map<UUID, RoleEntry> roles = new LinkedHashMap<>() {
        @Override
        public RoleEntry put(UUID key, RoleEntry value) {
            RoleEntry old = super.put(key, value);
            if (old == null) {
                roleVersion++;
            }
            return old;
        }
    };

    /**
     * Professions held for villagers without an active one: children (inherited at birth, given when they grow up,
     * 02 §Inheritance) and expedition members (given back when they settle or return).
     */
    public final Map<UUID, ColonyRole> pendingProfessions = new LinkedHashMap<>();

    // ---- households ----
    /** Households by owner (docs_v2/03-economy.md §Households). */
    public final Map<UUID, Household> households = new LinkedHashMap<>();
    /** Paid-for births waiting for their house (docs_v2/03-economy.md §Births). */
    public final List<BirthOrder> birthOrders = new ArrayList<>();
    /** Conscripted villagers and the day their service began (02 §Conscription). */
    public final Map<UUID, Long> conscripts = new LinkedHashMap<>();
    /** Set once the generated village got its households and starting capital. */
    public boolean householdsSettled;
    /** The bed the treasury hall is built around, and the elder it belonged to (the hall moves only with a new elder). */
    public BlockPos hallBed;
    public UUID hallElder;
    /** Set once the contents of loose village chests were taken into the treasury. */
    public boolean looseAbsorbed;
    /** Window index (day * 2 + window) of the last cleared gathering. */
    public long lastGathering = Long.MIN_VALUE;
    /** Market index per goods family (docs_v2/03-economy.md §Prices). */
    public final Map<String, Double> marketIndex = new LinkedHashMap<>();

    // ---- construction jobs ----
    public final List<ConstructionJob> jobs = new ArrayList<>();

    // ---- runtime only ----
    /** The treasury: the village's own chests (managed storages), rebuilt by the scan. */
    public final List<BlockPos> storages = new ArrayList<>();
    /** Village chests found by the scan that are neither treasury nor home chests (house loot, market stalls). */
    public final List<BlockPos> looseChests = new ArrayList<>();
    public int scanCursor = -1;
    public final List<BlockPos> scanPending = new ArrayList<>();
    public long lastScanFinish = NEVER;
    public long lastCensusTick = NEVER;
    public int cachedFood;
    public int cachedLogs;
    public int cachedStone;
    public int cachedSaplings;
    public int cachedIron;
    public int cachedDiamonds;
    public int bootstrapAttempts;
    public long nextHousePlanTick;
    public int houseSiteCursor;
    public long lastMemberTick = NEVER;
    public long lastBellAlarmTick = NEVER;
    /** Free-form runtime state attached by subsystems (not persisted). */
    public final Map<String, Object> runtime = new HashMap<>();

    public record RoleEntry(ColonyRole role, long changedAt) {
    }

    public Colony(UUID id, BlockPos core) {
        this.id = id;
        this.core = core.immutable();
        this.country = id;
    }

    // ---------------------------------------------------------------- VillageCapability

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public BlockPos core() {
        return core;
    }

    @Override
    public int radius() {
        return radius;
    }

    @Override
    public int population() {
        return lastPopulation;
    }

    @Override
    public int beds() {
        return lastBeds;
    }

    @Override
    public int cachedFood() {
        return cachedFood;
    }

    @Override
    public int cachedLogs() {
        return cachedLogs;
    }

    @Override
    public int cachedStone() {
        return cachedStone;
    }

    @Override
    /** The villager's duty: its profession, or warrior while conscripted (02 §Conscription). */
    public ColonyRole roleOf(UUID villager) {
        if (conscripts.containsKey(villager)) {
            return ColonyRole.WARRIOR;
        }
        return tradeOf(villager);
    }

    /** The villager's profession for life, whatever its current duty. */
    public ColonyRole tradeOf(UUID villager) {
        RoleEntry entry = roles.get(villager);
        return entry == null ? ColonyRole.CITIZEN : entry.role();
    }

    // ---------------------------------------------------------------- helpers

    public long roleChangedAt(UUID villager) {
        RoleEntry entry = roles.get(villager);
        return entry == null ? ROLE_CHANGED_DEFAULT : entry.changedAt();
    }

    public void setRole(UUID villager, ColonyRole role, long now) {
        roles.put(villager, new RoleEntry(role, now));
    }

    /** Radius never shrinks. */
    public void growRadius(int candidate) {
        radius = Math.max(radius, candidate);
    }

    /** True if the stock in scope (the scoped household's home chests, else the treasury) has a storage. */
    public boolean hasStorage() {
        return ColonyStorage.hasStock(this);
    }

    public boolean recentThreat(long now, long window) {
        return lastThreatTick != NEVER && now - lastThreatTick >= 0 && now - lastThreatTick < window;
    }

    public boolean hasActiveConstruction() {
        for (ConstructionJob job : jobs) {
            if (job.isActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean needsStarterWarehouse() {
        return !starterWarehousePlaced && managedStorages.isEmpty();
    }

    public boolean isFounderParty(UUID uuid) {
        return uuid != null && (uuid.equals(founder) || founderCompanions.contains(uuid));
    }

    public int completedHouseCount() {
        int n = 0;
        for (ConstructionJob job : jobs) {
            if (job.isComplete() && job.owner == null) {
                n++;
            }
        }
        return n;
    }

    /** True within the territory widened by margin blocks (XZ, {@link Territory}). */
    public boolean withinXZ(BlockPos pos, int margin) {
        return Territory.contains(this, pos, margin);
    }

    /** True inside the territory (XZ, {@link Territory}). */
    public boolean containsXZ(BlockPos pos) {
        return Territory.contains(this, pos, 0);
    }

    /** True inside the axis-aligned colony box (full height). */
    public boolean inBox(BlockPos pos) {
        return pos.getX() >= core.getX() - radius && pos.getX() <= core.getX() + radius
                && pos.getZ() >= core.getZ() - radius && pos.getZ() <= core.getZ() + radius;
    }

    @SuppressWarnings("unchecked")
    public <T> T runtime(String key, java.util.function.Supplier<T> factory) {
        return (T) runtime.computeIfAbsent(key, k -> factory.get());
    }

    // ---------------------------------------------------------------- persistence

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putLong("core", core.asLong());
        tag.putInt("radius", radius);
        tag.putLong("last_threat_tick", lastThreatTick);
        tag.putLong("simulated_until", simulatedUntil);
        tag.putLong("catchup_ticks", catchupTicks);
        tag.putLong("deferred_catchup_ticks", deferredCatchupTicks);
        tag.putInt("catchup_labor", catchupLabor);
        tag.putInt("catchup_births", catchupBirths);
        tag.putInt("catchup_houses", catchupHouses);
        tag.putBoolean("filming_development", filmingDevelopment);
        tag.putLong("catchup_expires", catchupExpires);
        tag.putBoolean("initial_development", initialDevelopment);
        tag.putDouble("house_credit", houseCredit);
        tag.putDouble("birth_credit", birthCredit);
        tag.putDouble("labor_credit", laborCredit);
        CompoundTag production = new CompoundTag();
        productionCredit.forEach((item, v) -> production.putDouble(itemKey(item), v));
        tag.put("production_credit", production);
        tag.putInt("last_population", lastPopulation);
        tag.putInt("last_beds", lastBeds);
        CompoundTag labor = new CompoundTag();
        laborCensus.forEach((role, n) -> labor.putInt(role.serializedName(), n));
        tag.put("labor_census", labor);
        ListTag stock = new ListTag();
        catchupStock.forEach((item, count) -> {
            CompoundTag e = new CompoundTag();
            e.putString("item", itemKey(item));
            e.putInt("count", count);
            stock.add(e);
        });
        tag.put("catchup_stock", stock);
        tag.put("fortifications", fortifications.save());
        NbtHelper.putUuid(tag, "assault_attacker", assaultAttacker);
        tag.putLong("assault_until", assaultUntil);
        ListTag repairList = new ListTag();
        repairs.forEach((pos, state) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("pos", pos.asLong());
            e.put("state", NbtUtils.writeBlockState(state));
            repairList.add(e);
        });
        tag.put("repairs", repairList);
        tag.putLongArray("farmland", NbtHelper.packPositions(farmland));
        tag.putLongArray("road_queue", NbtHelper.packPositions(roadQueue));
        if (roadAttemptHead != null) {
            tag.putLong("road_attempt_head", roadAttemptHead.asLong());
        }
        tag.putInt("road_stall_ticks", roadStallTicks);
        tag.putInt("skipped_road_segments", skippedRoadSegments);
        tag.putLongArray("mine_route", NbtHelper.packPositions(mineRoute));
        tag.putInt("mine_direction", mineDirection);
        tag.putLong("next_golem_tick", nextGolemTick);
        tag.putLong("next_economy_tick", nextEconomyTick);
        tag.putInt("economy_cursor", economyCursor);
        tag.putInt("sold_trades", soldTrades);
        tag.putInt("bought_trades", boughtTrades);
        tag.putInt("deep_mining_trips", deepMiningTrips);
        tag.putInt("deep_mining_yield", deepMiningYield);
        tag.putInt("mine_stall_trips", mineStallTrips);
        tag.putInt("last_mine_route_size", lastMineRouteSize);
        NbtHelper.putUuid(tag, "country", country);
        if (seat != null) tag.putLong("seat", seat.toLong());
        NbtHelper.putUuid(tag, "elder", elder);
        NbtHelper.putUuid(tag, "founder", founder);
        tag.putBoolean("founder_leader_lost", founderLeaderLost);
        tag.putBoolean("founder_returning", founderReturning);
        CompoundTag purses = new CompoundTag();
        founderPurses.forEach((id, n) -> purses.putInt(id.toString(), n));
        tag.put("founder_purses", purses);
        tag.putBoolean("founder_cargo_reserved", founderCargoReserved);
        CompoundTag cargo = new CompoundTag();
        founderCargo.forEach((item, n) -> cargo.putInt(itemKey(item), n));
        tag.put("founder_cargo", cargo);
        ListTag companions = new ListTag();
        founderCompanions.forEach(u -> companions.add(StringTag.valueOf(u.toString())));
        tag.put("founder_companions", companions);
        if (founderTarget != null) {
            tag.putLong("founder_target", founderTarget.asLong());
        }
        tag.putLong("founder_started_tick", founderStartedTick);
        tag.putInt("founder_visits", founderVisits);
        tag.putInt("founder_capital", founderCapital);
        tag.putLong("next_founder_tick", nextFounderTick);
        NbtHelper.putUuid(tag, "property_owner", propertyOwner);
        tag.putInt("property_level", propertyLevel);
        tag.putInt("stolen_items", stolenItems);
        tag.putLong("next_breeding_tick", nextBreedingTick);
        tag.putBoolean("has_livestock_pen", livestockPen != null);
        if (livestockPen != null) {
            tag.putLong("livestock_pen", livestockPen.asLong());
        }
        tag.putBoolean("profession_bootstrap_complete", professionBootstrapComplete);
        tag.putBoolean("starter_warehouse_placed", starterWarehousePlaced || !managedStorages.isEmpty());
        tag.putBoolean("abandoned_village", abandonedVillage);
        tag.putLong("dead_ticks", deadTicks);
        tag.putLong("last_profession_rebalance_tick", lastProfessionRebalanceTick);
        ListTag managed = new ListTag();
        managedStorages.forEach((pos, kind) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("pos", pos.asLong());
            e.putString("kind", kind.serializedName());
            managed.add(e);
        });
        tag.put("managed_storages", managed);
        ListTag pairs = new ListTag();
        managedChestPairs.forEach((left, right) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("left", left.asLong());
            e.putLong("right", right.asLong());
            pairs.add(e);
        });
        tag.put("managed_chest_pairs", pairs);
        ListTag roleList = new ListTag();
        roles.forEach((uuid, entry) -> {
            CompoundTag e = new CompoundTag();
            e.putString("villager", uuid.toString());
            e.putString("role", entry.role().serializedName());
            e.putLong("changed_at", entry.changedAt());
            roleList.add(e);
        });
        tag.put("roles", roleList);
        ListTag inherited = new ListTag();
        pendingProfessions.forEach((uuid, role) -> {
            CompoundTag e = new CompoundTag();
            e.putString("villager", uuid.toString());
            e.putString("role", role.serializedName());
            inherited.add(e);
        });
        tag.put("pending_professions", inherited);
        ListTag householdList = new ListTag();
        households.values().forEach(h -> householdList.add(h.save()));
        tag.put("households", householdList);
        tag.putBoolean("households_settled", householdsSettled);
        tag.putBoolean("loose_absorbed", looseAbsorbed);
        if (hallBed != null) tag.putLong("hall_bed", hallBed.asLong());
        NbtHelper.putUuid(tag, "hall_elder", hallElder);
        tag.putLong("last_gathering", lastGathering);
        CompoundTag conscriptTag = new CompoundTag();
        conscripts.forEach((id, since) -> conscriptTag.putLong(id.toString(), since));
        tag.put("conscripts", conscriptTag);
        ListTag orders = new ListTag();
        birthOrders.forEach(o -> orders.add(o.save()));
        tag.put("birth_orders", orders);
        CompoundTag index = new CompoundTag();
        marketIndex.forEach(index::putDouble);
        tag.put("market_index", index);
        ListTag jobList = new ListTag();
        jobs.forEach(job -> jobList.add(job.save()));
        tag.put("construction_jobs", jobList);
        return tag;
    }

    public static Colony load(CompoundTag tag, HolderGetter<Block> blocks) {
        UUID id = NbtHelper.uuid(tag, "id");
        Colony c = new Colony(id != null ? id : UUID.randomUUID(), BlockPos.of(tag.getLong("core")));
        if (tag.contains("radius")) c.radius = Math.max(32, tag.getInt("radius"));
        UUID country = NbtHelper.uuid(tag, "country");
        if (country != null) c.country = country;
        if (tag.contains("seat")) c.seat = new net.minecraft.world.level.ChunkPos(tag.getLong("seat"));
        c.lastThreatTick = tag.contains("last_threat_tick") ? tag.getLong("last_threat_tick") : NEVER;
        c.simulatedUntil = tag.contains("simulated_until") ? tag.getLong("simulated_until") : NEVER;
        c.catchupTicks = Math.max(0, tag.getLong("catchup_ticks"));
        c.deferredCatchupTicks = NbtHelper.clamp(tag.getLong("deferred_catchup_ticks"), 0, 720000);
        c.catchupLabor = NbtHelper.clamp(tag.getInt("catchup_labor"), 0, 12000);
        c.catchupBirths = NbtHelper.clamp(tag.getInt("catchup_births"), 0, 8);
        c.catchupHouses = NbtHelper.clamp(tag.getInt("catchup_houses"), 0, 4);
        c.filmingDevelopment = tag.getBoolean("filming_development");
        c.catchupExpires = tag.getLong("catchup_expires");
        c.initialDevelopment = tag.getBoolean("initial_development");
        c.houseCredit = NbtHelper.fraction(tag.getDouble("house_credit"));
        c.birthCredit = NbtHelper.fraction(tag.getDouble("birth_credit"));
        c.laborCredit = NbtHelper.fraction(tag.getDouble("labor_credit"));
        CompoundTag production = tag.getCompound("production_credit");
        for (String key : production.getAllKeys()) {
            Item item = item(key);
            if (item != null) {
                c.productionCredit.put(item, NbtHelper.fraction(production.getDouble(key)));
            }
        }
        c.lastPopulation = NbtHelper.clamp(tag.getInt("last_population"), 0, 200);
        c.lastBeds = NbtHelper.clamp(tag.getInt("last_beds"), 0, 1000);
        CompoundTag labor = tag.getCompound("labor_census");
        for (ColonyRole role : ColonyRole.values()) {
            c.laborCensus.put(role, NbtHelper.clamp(labor.getInt(role.serializedName()), 0, 200));
        }
        ListTag stock = tag.getList("catchup_stock", Tag.TAG_COMPOUND);
        for (int i = 0; i < stock.size(); i++) {
            CompoundTag e = stock.getCompound(i);
            Item item = item(e.getString("item"));
            if (item != null) {
                c.catchupStock.merge(item, NbtHelper.clamp(e.getInt("count"), 0, 4096), Integer::sum);
            }
        }
        c.fortifications.load(tag.getCompound("fortifications"), blocks);
        c.assaultAttacker = NbtHelper.uuid(tag, "assault_attacker");
        c.assaultUntil = tag.getLong("assault_until");
        ListTag repairList = tag.getList("repairs", Tag.TAG_COMPOUND);
        for (int i = 0; i < repairList.size(); i++) {
            CompoundTag e = repairList.getCompound(i);
            BlockState state = NbtUtils.readBlockState(blocks, e.getCompound("state"));
            if (!state.isAir()) {
                c.repairs.put(BlockPos.of(e.getLong("pos")), state);
            }
        }
        for (BlockPos pos : NbtHelper.unpackPositions(tag.getLongArray("farmland"))) {
            if (c.farmland.size() >= 4096) break;
            if (c.inBox(pos)) {
                c.farmland.add(pos);
            }
        }
        c.roadQueue.addAll(NbtHelper.unpackPositions(tag.getLongArray("road_queue")));
        if (!c.roadQueue.isEmpty()) {
            BlockPos head = tag.contains("road_attempt_head") ? BlockPos.of(tag.getLong("road_attempt_head")) : null;
            c.roadAttemptHead = c.roadQueue.get(0).equals(head) ? head : c.roadQueue.get(0);
            c.roadStallTicks = NbtHelper.clamp(tag.getInt("road_stall_ticks"), 0, 1200);
        }
        c.skippedRoadSegments = Math.max(0, tag.getInt("skipped_road_segments"));
        c.mineRoute.addAll(NbtHelper.unpackPositions(tag.getLongArray("mine_route")));
        c.mineDirection = Math.floorMod(tag.getInt("mine_direction"), 4);
        // Golem cooldown counts server ticks (MinecraftServer#getTickCount), which restart at 0 with the server.
        c.nextGolemTick = 0;
        c.nextEconomyTick = tag.getLong("next_economy_tick");
        c.economyCursor = Math.max(0, tag.getInt("economy_cursor"));
        c.soldTrades = Math.max(0, tag.getInt("sold_trades"));
        c.boughtTrades = Math.max(0, tag.getInt("bought_trades"));
        c.deepMiningTrips = Math.max(0, tag.getInt("deep_mining_trips"));
        c.deepMiningYield = Math.max(0, tag.getInt("deep_mining_yield"));
        c.mineStallTrips = Math.max(0, tag.getInt("mine_stall_trips"));
        c.lastMineRouteSize = tag.contains("last_mine_route_size") ? tag.getInt("last_mine_route_size") : c.mineRoute.size();
        c.elder = NbtHelper.uuid(tag, "elder");
        c.founder = NbtHelper.uuid(tag, "founder");
        c.founderLeaderLost = tag.getBoolean("founder_leader_lost");
        c.founderReturning = tag.getBoolean("founder_returning");
        CompoundTag purses = tag.getCompound("founder_purses");
        for (String key : purses.getAllKeys()) {
            try {
                c.founderPurses.put(UUID.fromString(key), Math.max(0, purses.getInt(key)));
            } catch (IllegalArgumentException ignored) {
                // A malformed entry is dropped.
            }
        }
        c.founderCargoReserved = tag.getBoolean("founder_cargo_reserved");
        CompoundTag cargo = tag.getCompound("founder_cargo");
        for (String key : cargo.getAllKeys()) {
            Item item = item(key);
            int n = cargo.getInt(key);
            if (item != null && n > 0) {
                c.founderCargo.put(item, Math.min(4096, n));
            }
        }
        ListTag companions = tag.getList("founder_companions", Tag.TAG_STRING);
        for (int i = 0; i < companions.size(); i++) {
            try {
                c.founderCompanions.add(UUID.fromString(companions.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        c.founderTarget = tag.contains("founder_target") ? BlockPos.of(tag.getLong("founder_target")) : null;
        c.founderStartedTick = tag.getLong("founder_started_tick");
        c.founderVisits = Math.max(0, tag.getInt("founder_visits"));
        c.founderCapital = Math.max(0, tag.getInt("founder_capital"));
        c.nextFounderTick = tag.getLong("next_founder_tick");
        c.propertyOwner = NbtHelper.uuid(tag, "property_owner");
        c.propertyLevel = NbtHelper.clamp(tag.getInt("property_level"), 0, 3);
        c.stolenItems = Math.max(0, tag.getInt("stolen_items"));
        c.nextBreedingTick = tag.getLong("next_breeding_tick");
        c.livestockPen = tag.getBoolean("has_livestock_pen") ? BlockPos.of(tag.getLong("livestock_pen")) : null;
        c.professionBootstrapComplete = tag.getBoolean("profession_bootstrap_complete");
        c.starterWarehousePlaced = tag.getBoolean("starter_warehouse_placed");
        c.abandonedVillage = tag.getBoolean("abandoned_village");
        c.deadTicks = Math.max(0, tag.getLong("dead_ticks"));
        c.lastProfessionRebalanceTick = tag.contains("last_profession_rebalance_tick") ? tag.getLong("last_profession_rebalance_tick") : NEVER;
        ListTag managed = tag.getList("managed_storages", Tag.TAG_COMPOUND);
        for (int i = 0; i < managed.size(); i++) {
            CompoundTag e = managed.getCompound(i);
            c.managedStorages.put(BlockPos.of(e.getLong("pos")), StorageKind.byName(e.getString("kind")));
        }
        ListTag pairs = tag.getList("managed_chest_pairs", Tag.TAG_COMPOUND);
        for (int i = 0; i < pairs.size(); i++) {
            CompoundTag e = pairs.getCompound(i);
            BlockPos left = BlockPos.of(e.getLong("left"));
            BlockPos right = BlockPos.of(e.getLong("right"));
            if (right.equals(left.east()) && c.managedStorages.containsKey(left) && c.managedStorages.get(left) == c.managedStorages.get(right)) {
                c.managedChestPairs.put(left, right);
            }
        }
        c.autoPairManaged();
        // Runtime storage list starts with the managed storages so stock is visible before the first scan finishes.
        c.storages.addAll(c.managedStorages.keySet());
        ListTag roleList = tag.getList("roles", Tag.TAG_COMPOUND);
        for (int i = 0; i < roleList.size(); i++) {
            CompoundTag e = roleList.getCompound(i);
            UUID villager = NbtHelper.uuid(e, "villager");
            if (villager == null) continue;
            c.roles.put(villager, new RoleEntry(ColonyRole.byName(e.getString("role")),
                    e.contains("changed_at") ? e.getLong("changed_at") : ROLE_CHANGED_DEFAULT));
        }
        ListTag inherited = tag.getList("pending_professions", Tag.TAG_COMPOUND);
        for (int i = 0; i < inherited.size(); i++) {
            CompoundTag e = inherited.getCompound(i);
            UUID villager = NbtHelper.uuid(e, "villager");
            ColonyRole role = ColonyRole.byName(e.getString("role"));
            if (villager != null && role.isProfession()) c.pendingProfessions.put(villager, role);
        }
        ListTag householdList = tag.getList("households", Tag.TAG_COMPOUND);
        for (int i = 0; i < householdList.size(); i++) {
            Household h = Household.load(householdList.getCompound(i));
            if (h != null) c.households.put(h.owner, h);
        }
        c.householdsSettled = tag.getBoolean("households_settled");
        c.looseAbsorbed = tag.getBoolean("loose_absorbed");
        c.hallBed = tag.contains("hall_bed") ? BlockPos.of(tag.getLong("hall_bed")) : null;
        c.hallElder = NbtHelper.uuid(tag, "hall_elder");
        c.lastGathering = tag.contains("last_gathering") ? tag.getLong("last_gathering") : Long.MIN_VALUE;
        ListTag orders = tag.getList("birth_orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orders.size(); i++) {
            BirthOrder o = BirthOrder.load(orders.getCompound(i));
            if (o != null) c.birthOrders.add(o);
        }
        CompoundTag conscriptTag = tag.getCompound("conscripts");
        for (String key : conscriptTag.getAllKeys()) {
            try {
                c.conscripts.put(UUID.fromString(key), conscriptTag.getLong(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag index = tag.getCompound("market_index");
        for (String key : index.getAllKeys()) {
            double v = index.getDouble(key);
            if (Double.isFinite(v)) c.marketIndex.put(key, Math.max(0.25, Math.min(4.0, v)));
        }
        ListTag jobList = tag.getList("construction_jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobList.size(); i++) {
            c.jobs.add(ConstructionJob.load(jobList.getCompound(i)));
        }
        if (c.filmingDevelopment) {
            c.catchupLabor = 0;
            c.catchupHouses = 0;
            c.catchupExpires = 0;
            for (ConstructionJob job : c.jobs) {
                if (job.owner == null) {
                    job.catchup = false;
                }
            }
        }
        return c;
    }

    /** Pairs every managed position whose east neighbour is managed with the same kind. */
    public void autoPairManaged() {
        List<BlockPos> sorted = new ArrayList<>(managedStorages.keySet());
        sorted.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                : a.getZ() != b.getZ() ? Integer.compare(a.getZ(), b.getZ()) : Integer.compare(a.getX(), b.getX()));
        Set<BlockPos> paired = new java.util.HashSet<>();
        managedChestPairs.forEach((l, r) -> {
            paired.add(l);
            paired.add(r);
        });
        for (BlockPos pos : sorted) {
            BlockPos east = pos.east();
            if (paired.contains(pos) || paired.contains(east)) continue;
            StorageKind kind = managedStorages.get(east);
            if (kind != null && kind == managedStorages.get(pos)) {
                managedChestPairs.put(pos, east);
                paired.add(pos);
                paired.add(east);
            }
        }
    }

    public void removeManagedStorage(BlockPos pos) {
        managedStorages.remove(pos);
        managedChestPairs.remove(pos);
        managedChestPairs.values().removeIf(pos::equals);
        storages.remove(pos);
    }

    private static String itemKey(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Item item(String key) {
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(rl);
    }
}
