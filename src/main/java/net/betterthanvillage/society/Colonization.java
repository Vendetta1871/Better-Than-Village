package net.betterthanvillage.society;

import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.nav.WaypointNavigator;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.colony.Colony;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.betterthanvillage.construction.HousePlanner;
import net.betterthanvillage.economy.Births;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.colony.Domain;
import net.betterthanvillage.colony.Territory;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.economy.Households;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.colony.StoragePlacer;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Founder expeditions (06 §6): start, settler selection, site search, travel, founding and deaths. */
public final class Colonization {
    private static final int SETTLERS = 5;
    /** A party smaller than this does not leave: the future elder, a warrior and one more. */
    private static final int MIN_SETTLERS = 3;
    /** Founding sites lie MIN_SITE_DISTANCE..MAX_SITE_DISTANCE blocks from the home core. */
    static final int MIN_SITE_DISTANCE = 288;
    static final int MAX_SITE_DISTANCE = 448;
    private static final long FOUNDER_MISSING_TICKS = 6000;
    private static final long EXPEDITION_MAX_TICKS = 3 * 24000L;
    private static final String MISSING_KEY = "society.founderMissingSince";
    private static final String COMPANION_MISSING_KEY = "society.companionMissingSince";
    private static final long COMPANION_MISSING_TICKS = 1200;
    private static final String STRAGGLER_KEY = "society.stragglerSince";
    private static final long STRAGGLER_TICKS = 600;
    private static final long CORE_SPACING_SQ = 160L * 160L;
    private static final String BRIDGE_KEY = "society.bridge";
    private static final Predicate<ItemStack> FOOD = ColonyStorage::isFood;
    private static final Predicate<ItemStack> CAPITAL = s -> s.is(ItemTags.LOGS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS) || ColonyStorage.isFood(s);
    private static final int[][] SLOTS = {{2, 0}, {0, 2}, {-2, 0}, {0, -2}};

    private Colonization() {
    }

    // ---------------------------------------------------------------- scheduler

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 20 != 0 || !ColonyManager.terrainChangesAllowed(level) || !ColonyConfig.get().roles.enable_auto_expansion) {
            return;
        }
        for (Colony colony : new ArrayList<>(ColonyManager.data(level).colonies())) {
            if (colony.founder != null) {
                advance(level, colony);
            } else if (level.isPositionEntityTicking(colony.core()) && !FilmMode.isOn(level)) {
                tryStart(level, colony);
            }
        }
    }

    private static boolean raidNear(ServerLevel level, Colony colony) {
        int dist = Math.min(1024, colony.radius() + 32);
        Raid raid = level.getRaids().getNearbyRaid(colony.core(), dist * dist);
        return raid != null && raid.isActive();
    }

    /**
     * docs_v2/04-land.md §Expansion: once the territory is at its maximum and a pair cannot get a plot or adults
     * lack houses, a search party leaves to found a village of the same country.
     */
    private static void tryStart(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now < colony.nextFounderTick || raidNear(level, colony) || !crowded(level, colony)) return;
        List<Villager> settlers = selectSettlers(level, colony);
        BlockPos site = settlers.size() < MIN_SETTLERS ? null : searchSite(level, colony);
        if (site == null) {
            colony.nextFounderTick = now + 1200;
            ColonyManager.markDirty(level);
            return;
        }
        loadCargo(level, colony, settlers);
        depart(level, colony, settlers, site, now);
    }

    /** The territory cannot grow any more, and a pair's house found no site or an adult has no bed. */
    public static boolean crowded(ServerLevel level, Colony colony) {
        if (!Territory.atMax(colony)) return false;
        if (Births.unsited(colony) != null && HousePlanner.siteFailures(colony) >= 2) return true;
        for (Household h : colony.households.values()) {
            if (h.bed == null) return true;
        }
        return false;
    }

    /**
     * About five settlers (04 §Expansion): the future elder first, the wealthiest adult (most emeralds at home);
     * then a warrior and a farmer, then adults without a house; every trade keeps at least one member at home and
     * the elder stays.
     */
    static List<Villager> selectSettlers(ServerLevel level, Colony colony) {
        List<Villager> adults = new ArrayList<>();
        for (Villager v : ColonyManager.adultMembers(level, colony)) {
            ColonyRole role = colony.roleOf(v.getUUID());
            if (v.getTradingPlayer() != null || v.isSleeping() || role == ColonyRole.ELDER || role == ColonyRole.FOUNDER) continue;
            adults.add(v);
        }
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (Villager v : ColonyManager.adultMembers(level, colony)) counts.merge(colony.tradeOf(v.getUUID()), 1, Integer::sum);
        List<Villager> out = new ArrayList<>();
        java.util.function.Predicate<Villager> spare = v -> {
            ColonyRole trade = colony.tradeOf(v.getUUID());
            return !trade.isProfession() || counts.getOrDefault(trade, 0) > 1;
        };
        java.util.function.Consumer<Villager> pick = v -> {
            out.add(v);
            adults.remove(v);
            counts.merge(colony.tradeOf(v.getUUID()), -1, Integer::sum);
        };
        adults.stream().filter(spare).max(Comparator.comparingInt(v -> purse(level, colony, v.getUUID()))).ifPresent(pick);
        for (ColonyRole needed : new ColonyRole[]{ColonyRole.WARRIOR, ColonyRole.FARMER}) {
            if (out.stream().anyMatch(v -> colony.roleOf(v.getUUID()) == needed)) continue;
            adults.stream().filter(v -> colony.roleOf(v.getUUID()) == needed && spare.test(v)).findFirst().ifPresent(pick);
        }
        adults.sort(Comparator.comparingInt(v -> {
            Household h = colony.households.get(v.getUUID());
            return h != null && h.bed == null ? 0 : 1;
        }));
        for (Villager v : new ArrayList<>(adults)) {
            if (out.size() >= SETTLERS) break;
            if (spare.test(v)) pick.accept(v);
        }
        return out;
    }

    /** Emeralds in a villager's home chests. */
    private static int purse(ServerLevel level, Colony colony, UUID villager) {
        Household h = colony.households.get(villager);
        return h == null ? 0 : net.betterthanvillage.economy.Chests.count(ColonyStorage.usable(level, h), st -> st.is(Items.EMERALD));
    }

    /**
     * Cargo: the treasury's starting grant (emeralds) and building materials and food; every settler takes its own
     * emeralds from its home chests (paid out again to its household in the new village, or at home if the party
     * comes back).
     */
    private static void loadCargo(ServerLevel level, Colony colony, List<Villager> settlers) {
        colony.founderCargo.clear();
        take(level, colony, s -> s.is(Items.EMERALD), ColonyConfig.get().land.expansion_grant);
        take(level, colony, s -> s.is(ItemTags.LOGS), 32);
        take(level, colony, s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS), 112);
        take(level, colony, FOOD, 32);
        colony.founderCargoReserved = true;
        colony.founderPurses.clear();
        for (Villager v : settlers) {
            Household h = colony.households.get(v.getUUID());
            if (h == null) continue;
            List<ColonyStorage.Slot> home = ColonyStorage.usable(level, h);
            int held = net.betterthanvillage.economy.Chests.count(home, st -> st.is(Items.EMERALD));
            int n = net.betterthanvillage.economy.Chests.total(net.betterthanvillage.economy.Chests.take(home, st -> st.is(Items.EMERALD), held));
            if (n > 0) colony.founderPurses.put(v.getUUID(), n);
        }
    }

    private static void take(ServerLevel level, Colony colony, Predicate<ItemStack> pred, int max) {
        for (int i = 0; i < max; i++) {
            ItemStack one = ColonyStorage.withdrawOne(level, colony, pred);
            if (one.isEmpty()) break;
            colony.founderCargo.merge(one.getItem(), 1, (a, b) -> Math.min(4096, a + b));
        }
    }

    static void depart(ServerLevel level, Colony colony, List<Villager> settlers, BlockPos site, long start) {
        for (Villager v : settlers) Nav.reset(v);
        Villager leader = settlers.get(0);
        long now = level.getGameTime();
        leader.setPersistenceRequired();
        // Expedition members keep their trade for life (02 §Fixed professions); it is held while they travel.
        for (Villager v : settlers) {
            ColonyRole trade = colony.roleOf(v.getUUID());
            if (trade.isProfession()) colony.pendingProfessions.put(v.getUUID(), trade);
        }
        colony.setRole(leader.getUUID(), ColonyRole.FOUNDER, now);
        colony.founder = leader.getUUID();
        colony.founderTarget = site.immutable();
        colony.founderStartedTick = start;
        colony.founderVisits = 0;
        colony.founderCapital = 0;
        colony.founderLeaderLost = false;
        colony.founderReturning = false;
        colony.founderCompanions.clear();
        Professions.applyRole(level, colony, leader);
        for (int i = 1; i < settlers.size(); i++) {
            Villager v = settlers.get(i);
            v.setPersistenceRequired();
            colony.founderCompanions.add(v.getUUID());
            colony.setRole(v.getUUID(), ColonyRole.FOUNDER, now);
            Professions.applyRole(level, colony, v);
        }
        colony.runtime.remove(BRIDGE_KEY);
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- site search (06 §6.3)

    /**
     * Site of the new village (04 §Expansion): its territory at the maximum must not overlap any village's
     * territory at the maximum. Preferred inside the own country's domain, else on land nobody owns; never inside
     * a foreign domain unless its owner sells the land (docs_v2/06-politics.md §Border changes). Loaded land only.
     */
    static BlockPos searchSite(ServerLevel level, Colony home) {
        BlockPos unowned = null, foreign = null;
        int offset = level.random.nextInt(48);
        for (int dist = MIN_SITE_DISTANCE; dist <= MAX_SITE_DISTANCE; dist += 32) {
            for (int i = 0; i < 48; i++) {
                double angle = (i + offset) * Math.PI * 2 / 48;
                int x = home.core().getX() + (int) Math.round(Math.cos(angle) * dist);
                int z = home.core().getZ() + (int) Math.round(Math.sin(angle) * dist);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                UUID owner = Domain.countryAt(level, new ChunkPos(x >> 4, z >> 4));
                boolean own = home.country != null && home.country.equals(owner);
                boolean abroad = owner != null && !own;
                if (abroad && (foreign != null || unowned != null)) continue;
                if (!own && !abroad && unowned != null) continue;
                BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!roomToGrow(level, pos) || !candidate(level, pos) || !suitable(level, pos)) continue;
                if (own) return pos;
                if (abroad) foreign = pos;
                else unowned = pos;
            }
        }
        if (unowned != null) return unowned;
        // Only land inside a foreign domain is left: the country offers to buy it.
        return foreign != null && net.betterthanvillage.politics.Borders.acquire(level, home, foreign) ? foreign : null;
    }

    /** No village's largest territory comes within the new village's largest territory of the site. */
    private static boolean roomToGrow(ServerLevel level, BlockPos pos) {
        int ownMax = (int) Math.sqrt(ColonyConfig.get().land.territory_max_area / Math.PI);
        for (Colony c : ColonyManager.data(level).colonies()) {
            long reach = Math.max(c.radius(), Territory.maxRadius(c)) + ownMax;
            long dx = c.core().getX() - pos.getX(), dz = c.core().getZ() - pos.getZ();
            if (dx * dx + dz * dz < reach * reach) return false;
        }
        return true;
    }

    /** Dry free standing spot, modifiable, no colony core within 160 blocks. */
    static boolean candidate(ServerLevel level, BlockPos pos) {
        if (!level.getWorldBorder().isWithinBounds(pos) || !level.getFluidState(pos).isEmpty()) return false;
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        if (!Protection.terrainModifiable(level, pos) || Protection.isProtectedBlock(level, pos.below())) return false;
        return !coreNear(level, pos, false);
    }

    private static boolean coreNear(ServerLevel level, BlockPos pos, boolean horizontal) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            long dx = c.core().getX() - pos.getX(), dy = horizontal ? 0 : c.core().getY() - pos.getY(), dz = c.core().getZ() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz < CORE_SPACING_SQ) return true;
        }
        return false;
    }

    /** Site suitability: far from player builds, land, beacons, containers and beds; a flat natural 17x17 square. */
    static boolean suitable(ServerLevel level, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        if (data.playerBuildNear(pos, 104)) return false;
        for (LandPlot plot : data.land) {
            if (plot.overlaps(pos, 0, 0, 104)) return false;
        }
        if (Protection.beaconNear(level, pos)) return false;
        long r2 = 104L * 104L;
        for (int cx = (pos.getX() - 104) >> 4; cx <= (pos.getX() + 104) >> 4; cx++) {
            for (int cz = (pos.getZ() - 104) >> 4; cz <= (pos.getZ() + 104) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof Container) && !(be instanceof BedBlockEntity)) continue;
                    BlockPos b = be.getBlockPos();
                    long dx = b.getX() - pos.getX(), dz = b.getZ() - pos.getZ();
                    if (dx * dx + dz * dz <= r2 && Math.abs(b.getY() - pos.getY()) <= 32) return false;
                }
            }
        }
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = pos.getX() - 8; x <= pos.getX() + 8; x++) {
            for (int z = pos.getZ() - 8; z <= pos.getZ() + 8; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) return false;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos s = new BlockPos(x, surface, z);
                if (!level.getWorldBorder().isWithinBounds(s)) return false;
                for (int k = 0; k <= 2; k++) {
                    if (!Deeds.naturalGround(level.getBlockState(s.below(k)))) return false;
                }
                BlockPos above = s.above();
                if (!level.getFluidState(above).isEmpty() || !level.getBlockState(above).getCollisionShape(level, above).isEmpty()) return false;
                min = Math.min(min, surface);
                max = Math.max(max, surface);
            }
        }
        return max - min <= 3 && Math.abs(pos.getY() - 1 - (min + max) / 2) <= 3;
    }

    // ---------------------------------------------------------------- travel update (06 §6.5)

    private static Villager villager(ServerLevel level, UUID id) {
        Entity e = id == null ? null : level.getEntity(id);
        return e instanceof Villager v ? v : null;
    }

    private static boolean hurtByHostile(LivingEntity e) {
        LivingEntity attacker = e.getLastHurtByMob();
        return attacker != null && net.betterthanvillage.defense.ThreatManager.isValidHostile(attacker)
                && e.tickCount - e.getLastHurtByMobTimestamp() < 100;
    }

    /**
     * Expedition timeout, checked only while the home core ticks (so the cargo can be delivered): the leader has
     * not been found for FOUNDER_MISSING_TICKS, or the expedition is older than EXPEDITION_MAX_TICKS.
     */
    private static boolean timedOut(ServerLevel level, Colony colony, long now) {
        if (!level.isPositionEntityTicking(colony.core())) return false;
        if (now - colony.founderStartedTick > EXPEDITION_MAX_TICKS) return true;
        boolean leaderPresent = colony.founderLeaderLost
                ? colony.founderCompanions.stream().anyMatch(id -> villager(level, id) != null)
                : colony.founder != null && level.getEntity(colony.founder) != null;
        if (leaderPresent) {
            colony.runtime.remove(MISSING_KEY);
            return false;
        }
        long since = (Long) colony.runtime.computeIfAbsent(MISSING_KEY, k -> now);
        return now - since >= FOUNDER_MISSING_TICKS;
    }

    static void advance(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (timedOut(level, colony, now)) {
            deliverCargo(level, colony, colony, colony.core());
            abort(level, colony, true);
            return;
        }
        if (colony.founderLeaderLost) {
            for (Iterator<UUID> it = colony.founderCompanions.iterator(); it.hasNext(); ) {
                UUID id = it.next();
                Villager v = villager(level, id);
                if (v != null && v.isAlive()) {
                    it.remove();
                    colony.founder = id;
                    colony.founderLeaderLost = false;
                    ColonyManager.markDirty(level);
                    break;
                }
            }
            if (colony.founderLeaderLost) {
                if (colony.founderCompanions.isEmpty()) abort(level, colony, true);
                return;
            }
        }
        Entity fe = level.getEntity(colony.founder);
        if (fe == null) return;
        if (!(fe instanceof Villager founder) || !founder.isAlive()) {
            colony.roles.remove(colony.founder);
            colony.founderLeaderLost = true;
            ColonyManager.markDirty(level);
            return;
        }
        boolean hurt = hurtByHostile(founder);
        for (UUID id : colony.founderCompanions) {
            Villager v = villager(level, id);
            if (v != null && v.isAlive() && hurtByHostile(v)) hurt = true;
        }
        if (hurt || colony.founderCompanions.isEmpty()) {
            colony.founderReturning = true;
        }
        if (colony.founderReturning) {
            returnHome(level, colony, founder);
            return;
        }
        BlockPos target = colony.founderTarget;
        if (target == null) {
            colony.founderReturning = true;
            return;
        }
        if (now % 200 == 0 && level.hasChunk(target.getX() >> 4, target.getZ() >> 4)
                && (!suitable(level, target) || coreNear(level, target, true))) {
            BlockPos next = searchSite(level, colony);
            if (next != null) {
                colony.founderTarget = target = next;
                colony.runtime.remove(BRIDGE_KEY);
            } else {
                colony.founderReturning = true;
                return;
            }
            ColonyManager.markDirty(level);
        }
        long elapsed = now - colony.founderStartedTick;
        boolean allArrived = true, straggler = false;
        double founderToTarget = founder.position().distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        boolean founderNear6 = founderToTarget <= 36;
        boolean founderAt = founderToTarget <= 9;
        for (int i = 0; i < colony.founderCompanions.size(); i++) {
            UUID id = colony.founderCompanions.get(i);
            int slot = i % 4;
            Entity e = level.getEntity(id);
            Map<UUID, Long> missing = colony.runtime(COMPANION_MISSING_KEY, java.util.HashMap::new);
            if (e == null) {
                // A companion out of the loaded world for long stays behind (it rejoins its trade at home).
                long since = missing.computeIfAbsent(id, k -> now);
                if (now - since >= COMPANION_MISSING_TICKS) {
                    missing.remove(id);
                    colony.founderCompanions.remove(i--);
                    colony.setRole(id, ColonyRole.CITIZEN, now);
                    Household h = colony.households.get(id);
                    Integer purse = colony.founderPurses.remove(id);
                    if (h != null && purse != null) h.pendingCapital += purse;
                    ColonyManager.markDirty(level);
                    continue;
                }
                allArrived = false;
                straggler = true;
                continue;
            }
            missing.remove(id);
            if (!(e instanceof Villager v) || !v.isAlive()) {
                colony.founderCompanions.remove(i--);
                colony.roles.remove(id);
                ColonyManager.markDirty(level);
                continue;
            }
            if (v.getTradingPlayer() != null || v.isSleeping()) {
                allArrived = false;
                straggler = true;
                continue;
            }
            double toFounder = v.distanceToSqr(founder);
            double toTarget = v.position().distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (toFounder > 16 || (founderNear6 && toTarget > 25)) {
                BlockPos goal = founder.blockPosition();
                if (founderAt) {
                    BlockPos spot = target.offset(SLOTS[slot][0], 0, SLOTS[slot][1]);
                    goal = Nav.standable(level, spot) ? spot : target;
                }
                WaypointNavigator.travel(level, v, goal, 0.95);
            }
            if (toFounder > 144) {
                straggler = true;
                // A companion that cannot catch up (fell into a pit, cut off by water) climbs out to the leader.
                Map<UUID, Long> behind = colony.runtime(STRAGGLER_KEY, java.util.HashMap::new);
                long since = behind.computeIfAbsent(id, k -> now);
                if (now - since >= STRAGGLER_TICKS) {
                    BlockPos spot = standNear(level, founder.blockPosition());
                    if (spot != null) net.betterthanvillage.gather.GatherUtil.teleport(v, spot);
                    behind.remove(id);
                }
            } else {
                Map<UUID, Long> behind = colony.runtime(STRAGGLER_KEY, java.util.HashMap::new);
                behind.remove(id);
            }
            if (toTarget > 36) allArrived = false;
            if (colony.founderCapital >= 24 && founderAt && v.blockPosition().equals(target)) {
                nudge(level, v, target);
                allArrived = false;
            }
        }
        if (elapsed < 0) {
            WaypointNavigator.travel(level, founder, colony.core(), 0.9);
            return;
        }
        while (colony.founderVisits < 3 && elapsed >= (colony.founderVisits + 1) * 1200L) {
            if (!consumeCapital(level, colony, 16)) break;
            colony.founderVisits++;
            colony.founderCapital += 8;
            ColonyManager.markDirty(level);
        }
        if (founder.isSleeping() || founder.getTradingPlayer() != null) return;
        if (straggler) {
            founder.getNavigation().stop();
            return;
        }
        if (founderAt) {
            if (colony.founderCapital >= 24 && allArrived) {
                if (founder.blockPosition().equals(target)) {
                    nudge(level, founder, target);
                } else {
                    founder.getNavigation().stop();
                    found(level, colony, founder);
                }
            }
            return;
        }
        if (!waterCrossing(level, colony, founder, target)) {
            WaypointNavigator.travel(level, founder, target, 0.9);
        }
    }

    /** A standable spot 1..3 blocks from pos, or null. */
    private static BlockPos standNear(ServerLevel level, BlockPos pos) {
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = pos.offset(dx, dy, dz);
                        if (Nav.standable(level, p)) return p;
                    }
                }
            }
        }
        return null;
    }

    /** Moves a villager off the exact target block to a free standable spot 2..3 blocks away. */
    private static void nudge(ServerLevel level, Villager v, BlockPos target) {
        for (int r = 2; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = target.offset(dx, dy, dz);
                        if (Nav.standable(level, p) && SafeEdit.free(level, p, v)) {
                            v.getNavigation().stop();
                            v.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                            return;
                        }
                    }
                }
            }
        }
    }

    /** Consumes n resources: cargo (stone, logs, food) if reserved, else home storage (logs, stone or food). */
    private static boolean consumeCapital(ServerLevel level, Colony colony, int n) {
        if (colony.founderCargoReserved) {
            int total = 0;
            for (int c : colony.founderCargo.values()) total += c;
            if (total < n) return false;
            int remaining = n;
            for (Predicate<ItemStack> kind : List.<Predicate<ItemStack>>of(s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS), s -> s.is(ItemTags.LOGS), FOOD)) {
                for (Iterator<Map.Entry<Item, Integer>> it = colony.founderCargo.entrySet().iterator(); it.hasNext() && remaining > 0; ) {
                    Map.Entry<Item, Integer> e = it.next();
                    if (!kind.test(new ItemStack(e.getKey()))) continue;
                    int k = Math.min(remaining, e.getValue());
                    remaining -= k;
                    if (e.getValue() - k <= 0) it.remove();
                    else e.setValue(e.getValue() - k);
                }
            }
            for (Iterator<Map.Entry<Item, Integer>> it = colony.founderCargo.entrySet().iterator(); it.hasNext() && remaining > 0; ) {
                Map.Entry<Item, Integer> e = it.next();
                int k = Math.min(remaining, e.getValue());
                remaining -= k;
                if (e.getValue() - k <= 0) it.remove();
                else e.setValue(e.getValue() - k);
            }
            return true;
        }
        return ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, CAPITAL, n), List.of());
    }

    private static void returnHome(ServerLevel level, Colony colony, Villager founder) {
        BlockPos core = colony.core();
        boolean allHome = true;
        List<UUID> members = new ArrayList<>(colony.founderCompanions);
        members.add(0, colony.founder);
        for (UUID id : members) {
            Entity e = level.getEntity(id);
            if (e == null) {
                allHome = false;
                continue;
            }
            if (!(e instanceof Villager v) || !v.isAlive()) {
                colony.founderCompanions.remove(id);
                continue;
            }
            if (v.blockPosition().distSqr(core) > 144) {
                allHome = false;
                if (!v.isSleeping() && v.getTradingPlayer() == null) WaypointNavigator.travel(level, v, core, 0.9);
            }
        }
        if (allHome) {
            deliverCargo(level, colony, colony, core);
            abort(level, colony, true);
        }
    }

    /** Puts the whole cargo into the given colony's storages; overflow is dropped at the drop point + 1. */
    private static void deliverCargo(ServerLevel level, Colony owner, Colony into, BlockPos dropAt) {
        for (Map.Entry<Item, Integer> e : owner.founderCargo.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                ItemStack stack = new ItemStack(e.getKey(), Math.min(left, new ItemStack(e.getKey()).getMaxStackSize()));
                left -= stack.getCount();
                ColonyStorage.deposit(level, into, stack);
                if (!stack.isEmpty()) drop(level, dropAt.above(), stack);
            }
        }
        owner.founderCargo.clear();
        owner.founderCargoReserved = false;
        ColonyStorage.recount(level, into);
    }

    private static void drop(ServerLevel level, BlockPos pos, ItemStack stack) {
        level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }

    /** Ends the expedition: cargo cleared, members become citizens, founder fields reset, next try in a day. */
    static void abort(ServerLevel level, Colony colony, boolean applyVisuals) {
        end(level, colony, applyVisuals, false);
    }

    /**
     * Resets the expedition. Members become citizens again, or lose their home role entirely when they moved to a
     * new colony; any leftover FOUNDER role is cleared so future expeditions can select settlers.
     */
    private static void end(ServerLevel level, Colony colony, boolean applyVisuals, boolean moved) {
        long now = level.getGameTime();
        List<UUID> members = new ArrayList<>(colony.founderCompanions);
        if (colony.founder != null) {
            // A lost leader is dead: drop its role entry instead of re-adding it.
            if (colony.founderLeaderLost) colony.roles.remove(colony.founder);
            else members.add(0, colony.founder);
        }
        for (UUID id : members) {
            if (moved) {
                colony.roles.remove(id);
                colony.pendingProfessions.remove(id);
                Households.dissolve(level, colony, id);
            } else {
                // The next census gives the held trade back.
                colony.setRole(id, ColonyRole.CITIZEN, now);
                Household h = colony.households.get(id);
                Integer purse = colony.founderPurses.get(id);
                if (h != null && purse != null) h.pendingCapital += purse;
            }
        }
        colony.founderPurses.clear();
        for (UUID id : new ArrayList<>(colony.roles.keySet())) {
            if (colony.roleOf(id) == ColonyRole.FOUNDER) colony.setRole(id, ColonyRole.CITIZEN, now);
        }
        colony.runtime.remove(MISSING_KEY);
        colony.founderCargo.clear();
        colony.founderCargoReserved = false;
        colony.founder = null;
        colony.founderCompanions.clear();
        colony.founderTarget = null;
        colony.founderStartedTick = 0;
        colony.founderVisits = 0;
        colony.founderCapital = 0;
        colony.founderLeaderLost = false;
        colony.founderReturning = false;
        colony.nextFounderTick = now + 24000;
        colony.runtime.remove(BRIDGE_KEY);
        if (applyVisuals) {
            for (UUID id : members) {
                Villager v = villager(level, id);
                if (v != null && v.isAlive()) {
                    Nav.reset(v);
                    Professions.applyRole(level, ColonyManager.colonyOf(level, v), v);
                }
            }
        }
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- water crossing

    private static final class Bridge {
        final List<BlockPos> water = new ArrayList<>();
        BlockPos start;
    }

    /** Plank bridge over up to 24 source-water blocks in the dominant direction; returns true while it drives the founder. */
    private static boolean waterCrossing(ServerLevel level, Colony colony, Villager founder, BlockPos target) {
        Bridge b = (Bridge) colony.runtime.get(BRIDGE_KEY);
        if (b == null) {
            b = planBridge(level, founder, target);
            if (b == null) return false;
            colony.runtime.put(BRIDGE_KEY, b);
        }
        while (!b.water.isEmpty() && !level.getFluidState(b.water.get(0)).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
            b.water.remove(0);
        }
        if (b.water.isEmpty()) {
            colony.runtime.remove(BRIDGE_KEY);
            return false;
        }
        BlockPos next = b.water.get(0);
        if (founder.blockPosition().distSqr(next.above()) > 4 && founder.blockPosition().distSqr(b.start) > 2) {
            WaypointNavigator.travel(level, founder, b.start, 0.9);
            return true;
        }
        if (!Protection.terrainModifiable(level, next) || !takeLog(level, colony)) {
            colony.runtime.remove(BRIDGE_KEY);
            return false;
        }
        if (!SafeEdit.place(level, next, Blocks.OAK_PLANKS.defaultBlockState(), 3)) {
            colony.runtime.remove(BRIDGE_KEY);
            return false;
        }
        b.water.remove(0);
        founder.getNavigation().moveTo(next.getX() + 0.5, next.getY() + 1, next.getZ() + 0.5, 0.9);
        return true;
    }

    private static Bridge planBridge(ServerLevel level, Villager founder, BlockPos target) {
        BlockPos feet = founder.blockPosition();
        int dx = target.getX() - feet.getX(), dz = target.getZ() - feet.getZ();
        Direction dir = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos first = null;
        for (int k = 1; k <= 10 && first == null; k++) {
            BlockPos p = feet.relative(dir, k);
            if (isSourceWater(level, p.below())) first = p.below();
            else if (isSourceWater(level, p)) first = p;
        }
        if (first == null) return null;
        Bridge b = new Bridge();
        BlockPos p = first;
        while (b.water.size() < 24 && isSourceWater(level, p) && Protection.terrainModifiable(level, p)) {
            b.water.add(p);
            p = p.relative(dir);
        }
        if (b.water.isEmpty()) return null;
        BlockPos end = p.above();
        if (!Nav.standable(level, end) || level.getFluidState(p).is(FluidTags.WATER)) return null;
        BlockPos start = first.relative(dir.getOpposite(), 2).above();
        if (!Nav.standable(level, start)) start = start.above();
        if (!Nav.standable(level, start)) return null;
        b.start = start;
        return b;
    }

    private static boolean isSourceWater(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getFluidState(pos).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER);
    }

    private static boolean takeLog(ServerLevel level, Colony colony) {
        for (Iterator<Map.Entry<Item, Integer>> it = colony.founderCargo.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Item, Integer> e = it.next();
            if (!new ItemStack(e.getKey()).is(ItemTags.LOGS)) continue;
            if (e.getValue() <= 1) it.remove();
            else e.setValue(e.getValue() - 1);
            return true;
        }
        return ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1), List.of());
    }

    // ---------------------------------------------------------------- founding (06 §6.6)

    private static void found(ServerLevel level, Colony home, Villager founder) {
        BlockPos target = home.founderTarget;
        if (!suitable(level, target) || home.founderCompanions.isEmpty()) return;
        if (!Protection.terrainModifiable(level, target) || Protection.isProtectedBlock(level, target.below())
                || !level.getWorldBorder().isWithinBounds(target) || !level.getBlockState(target).isAir()
                || !level.getBlockState(target.below()).isFaceSturdy(level, target.below(), Direction.UP)) return;
        Colony fresh = new Colony(UUID.randomUUID(), target);
        // A new colony has no absence to catch up on.
        fresh.simulatedUntil = level.getGameTime();
        // The founding party is the population; no top-up to the bootstrap minimum.
        fresh.professionBootstrapComplete = true;
        BlockPos chest = StoragePlacer.findSite(level, fresh);
        if (chest == null || !level.getEntitiesOfClass(LivingEntity.class, new AABB(target)).isEmpty()) return;
        if (!SafeEdit.place(level, target, Blocks.BELL.defaultBlockState(), 3)) return;
        if (!StoragePlacer.placeDoubleChest(level, fresh, chest, StorageKind.GENERAL)) {
            SafeEditScope.run(() -> level.setBlock(target, Blocks.AIR.defaultBlockState(), 3));
            return;
        }
        fresh.starterWarehousePlaced = true;
        ColonyManager.data(level).addColony(fresh);
        if (home.founderCargoReserved) {
            deliverCargo(level, home, fresh, target);
        } else {
            moveFromHome(level, home, fresh, s -> s.is(ItemTags.LOGS), 32);
            moveFromHome(level, home, fresh, s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS), 64);
            moveFromHome(level, home, fresh, FOOD, 32);
        }
        long now = level.getGameTime();
        // A village of the same country; on land nobody owned it claims the unowned chunks around it.
        net.betterthanvillage.politics.Countries.join(level, fresh, home.country, ColonyConfig.get().politics.daughter_loyalty);
        if (Domain.countryAt(level, new ChunkPos(target)) == null) Domain.claimAround(level, fresh);
        // Settlers bring their own money: households now, no starting capital out of nothing.
        fresh.householdsSettled = true;
        fresh.setRole(founder.getUUID(), ColonyRole.ELDER, now);
        fresh.elder = founder.getUUID();
        Professions.applyRole(level, fresh, founder);
        List<UUID> companions = new ArrayList<>(home.founderCompanions);
        boolean farmer = false;
        for (UUID id : companions) farmer |= home.pendingProfessions.get(id) == ColonyRole.FARMER;
        List<UUID> party = new ArrayList<>(companions);
        party.add(founder.getUUID());
        for (UUID id : party) {
            Household h = new Household(id);
            h.founded = now;
            h.lastIncomeDay = Math.floorDiv(level.getDayTime(), 24000L);
            h.greed = (level.random.nextFloat() * 2f - 1f) * 0.3f;
            h.pendingCapital = home.founderPurses.getOrDefault(id, 0);
            fresh.households.put(id, h);
        }
        // Trades in the new village: every settler keeps its own; the party has one warrior and one farmer, the
        // farmer taking up farming as first of its line if no farmer came along.
        List<ColonyRole> trades = new ArrayList<>();
        int warriorAt = -1;
        for (int i = 0; i < companions.size(); i++) {
            ColonyRole trade = home.pendingProfessions.get(companions.get(i));
            if (trade == null) trade = ColonyRole.WARRIOR;
            if (trade == ColonyRole.WARRIOR && warriorAt < 0) warriorAt = i;
            trades.add(trade);
        }
        if (warriorAt < 0 && !companions.isEmpty()) {
            warriorAt = 0;
            trades.set(0, ColonyRole.WARRIOR);
        }
        if (!farmer) {
            for (int i = companions.size() - 1; i >= 0; i--) {
                if (i == warriorAt) continue;
                trades.set(i, ColonyRole.FARMER);
                break;
            }
        }
        for (int i = 0; i < companions.size(); i++) {
            fresh.setRole(companions.get(i), trades.get(i), now);
            Villager v = villager(level, companions.get(i));
            if (v != null) {
                Nav.reset(v);
                Professions.applyRole(level, fresh, v);
            }
        }
        Nav.reset(founder);
        end(level, home, false, true);
        ColonyManager.markDirty(level);
    }

    private static void moveFromHome(ServerLevel level, Colony home, Colony fresh, Predicate<ItemStack> pred, int max) {
        for (int i = 0; i < max; i++) {
            ItemStack one = ColonyStorage.withdrawOne(level, home, pred);
            if (one.isEmpty()) return;
            ColonyStorage.deposit(level, fresh, one);
            if (!one.isEmpty()) {
                ColonyStorage.deposit(level, home, one);
                if (!one.isEmpty()) drop(level, home.core().above(), one);
            }
        }
    }

    // ---------------------------------------------------------------- deaths (06 §6.7)

    public static void onVillagerDeath(ServerLevel level, Villager v) {
        UUID id = v.getUUID();
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (!colony.isFounderParty(id)) continue;
            if (id.equals(colony.founder)) {
                colony.founderLeaderLost = true;
            } else {
                colony.founderCompanions.remove(id);
            }
            colony.roles.remove(id);
            colony.founderReturning = true;
            if (colony.founderLeaderLost && colony.founderCompanions.isEmpty()) {
                for (Map.Entry<Item, Integer> e : colony.founderCargo.entrySet()) {
                    int left = e.getValue();
                    int max = new ItemStack(e.getKey()).getMaxStackSize();
                    while (left > 0) {
                        int n = Math.min(left, max);
                        left -= n;
                        drop(level, v.blockPosition(), new ItemStack(e.getKey(), n));
                    }
                }
                colony.founderCargo.clear();
            }
            ColonyManager.markDirty(level);
        }
    }

    // ---------------------------------------------------------------- film expedition (06 §8)

    /** Forced expedition for the film command; returns the feedback message and whether it started. */
    public static Component startForced(ServerLevel level, Colony colony, BlockPos explicit) {
        if (!ColonyConfig.get().roles.enable_auto_expansion || !ColonyManager.terrainChangesAllowed(level)) return Msg.command("film.expedition.disabled");
        if (colony.founder != null || FilmMode.maxRunning(colony)) return Msg.command("film.expedition.busy");
        if (raidNear(level, colony) || colony.recentThreat(level.getGameTime(), 200)) return Msg.command("film.expedition.attack");
        BlockPos site;
        if (explicit == null) {
            site = searchSite(level, colony);
            if (site == null) return Msg.command("film.expedition.no_site");
        } else {
            if (!level.getWorldBorder().isWithinBounds(explicit) || !level.hasChunk(explicit.getX() >> 4, explicit.getZ() >> 4)) {
                return Msg.command("film.expedition.unloaded");
            }
            int x = explicit.getX(), z = explicit.getZ();
            site = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!candidate(level, site) || site.distSqr(colony.core()) > 640L * 640L) return Msg.command("film.expedition.bad_target");
            if (!suitable(level, site)) return Msg.command("film.expedition.unsuitable");
            if (StoragePlacer.findSite(level, new Colony(UUID.randomUUID(), site)) == null) return Msg.command("film.expedition.no_storage");
        }
        List<Villager> settlers = selectSettlers(level, colony);
        if (settlers.size() < SETTLERS) return Msg.command("film.expedition.no_settlers");
        if (!ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, CAPITAL, 48), List.of())) {
            return Msg.command("film.expedition.no_capital");
        }
        colony.founderCargo.clear();
        colony.founderCargoReserved = false;
        depart(level, colony, settlers, site, level.getGameTime() + 200);
        colony.founderVisits = 3;
        colony.founderCapital = 24;
        return Msg.command("film.expedition.started", Msg.xyz(site));
    }

    /** Copy of the cargo map for status output. */
    static Map<Item, Integer> cargo(Colony colony) {
        return new LinkedHashMap<>(colony.founderCargo);
    }
}
