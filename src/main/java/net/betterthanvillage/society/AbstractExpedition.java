package net.betterthanvillage.society;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.Domain;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.colony.NbtHelper;
import net.betterthanvillage.colony.Territory;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.warfare.Campaigns;
import net.betterthanvillage.world.Caravans;
import net.betterthanvillage.world.Materializer;
import net.betterthanvillage.world.SiteLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/**
 * The search party of an abstract village (docs_v2/04-land.md §Expansion, docs_v2/08-simulation.md): a crowded
 * village nobody watches picks its settlers and a site from its records and the terrain generator, and the party
 * travels as a record. On arrival the site is loaded for a moment and the settlers walk there as real villagers;
 * the live expedition code founds the village. A party that finds no site turns back as a record.
 */
public final class AbstractExpedition {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Travel speed of a party on foot (blocks per tick, about 150 per in-game hour). */
    private static final double BLOCKS_PER_TICK = 0.15;
    /** Candidate sites checked per scheduler call (each samples the terrain generator a few times). */
    private static final int CANDIDATES_PER_CALL = 12;
    private static final int ANGLES = 48;
    private static final String CURSOR = "society.abstractSiteCursor";
    /** The site (or home) held loaded while the materialized party finishes live. */
    private static final String HELD = "society.abstractSiteHeld";
    /** Arrived and waiting for the site to load since this tick. */
    private static final String WAITING = "society.abstractSiteWaiting";
    private static final long LOAD_PATIENCE = 24000;

    private AbstractExpedition() {
    }

    // ---------------------------------------------------------------- start

    /** Every scheduler pass for an abstract village without a party. */
    static void tryStart(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now < colony.nextFounderTick || !colony.householdsSettled || !crowded(colony)) return;
        List<UUID> settlers = selectSettlers(level, colony);
        if (settlers.size() < Colonization.MIN_SETTLERS) {
            colony.nextFounderTick = now + 24000;
            ColonyManager.markDirty(level);
            return;
        }
        BlockPos site = searchStep(level, colony);
        if (site == null) return;
        Colonization.loadCargo(level, colony, settlers);
        depart(level, colony, settlers, site, now);
    }

    /**
     * Crowded without the loaded world: the territory is at its maximum and an adult has no bed, or a house paid for
     * by parents was finished in the abstract world (it may find no site when the village loads).
     */
    static boolean crowded(Colony colony) {
        if (!Territory.atMax(colony)) return false;
        for (Household h : colony.households.values()) {
            if (h.bed == null && !colony.pendingProfessions.containsKey(h.owner)) return true;
        }
        for (var o : colony.birthOrders) {
            if (o.builtAbstract != 0) return true;
        }
        return false;
    }

    /** The settlers by the rules of the live party (Colonization.selectSettlers), from the village's records. */
    static List<UUID> selectSettlers(ServerLevel level, Colony colony) {
        List<UUID> adults = new ArrayList<>();
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (Household h : colony.households.values()) {
            UUID id = h.owner;
            if (colony.pendingProfessions.containsKey(id)) continue;
            counts.merge(colony.tradeOf(id), 1, Integer::sum);
            ColonyRole role = colony.roleOf(id);
            if (role == ColonyRole.ELDER || role == ColonyRole.FOUNDER || colony.conscripts.containsKey(id)) continue;
            if (Campaigns.away(level, id) || Caravans.away(level, id) || ColonyManager.data(level).fallen.contains(id)) continue;
            adults.add(id);
        }
        List<UUID> out = new ArrayList<>();
        java.util.function.Predicate<UUID> spare = id -> {
            ColonyRole trade = colony.tradeOf(id);
            return !trade.isProfession() || counts.getOrDefault(trade, 0) > 1;
        };
        java.util.function.Consumer<UUID> pick = id -> {
            out.add(id);
            adults.remove(id);
            counts.merge(colony.tradeOf(id), -1, Integer::sum);
        };
        adults.stream().filter(spare).max(Comparator.comparingInt(id -> Colonization.purse(level, colony, id))).ifPresent(pick);
        for (ColonyRole needed : new ColonyRole[]{ColonyRole.WARRIOR, ColonyRole.FARMER}) {
            if (out.stream().anyMatch(id -> colony.roleOf(id) == needed)) continue;
            adults.stream().filter(id -> colony.roleOf(id) == needed && spare.test(id)).findFirst().ifPresent(pick);
        }
        adults.sort(Comparator.comparingInt(id -> {
            Household h = colony.households.get(id);
            return h != null && h.bed == null ? 0 : 1;
        }));
        for (UUID id : new ArrayList<>(adults)) {
            if (out.size() >= Colonization.SETTLERS) break;
            if (spare.test(id)) pick.accept(id);
        }
        return out;
    }

    // ---------------------------------------------------------------- site from the records

    /**
     * Checks the next few candidates of the live search pattern (288..448 blocks, 48 directions). Unloaded land is
     * judged by the terrain generator (dry and flat) and by the records (territories, cores, domains, player builds
     * and plots); loaded land by the live checks. Own domain first, else land nobody owns. Returns a site, or null
     * while the search goes on (after a full pass without one the village waits a day).
     */
    static BlockPos searchStep(ServerLevel level, Colony home) {
        int rings = (Colonization.MAX_SITE_DISTANCE - Colonization.MIN_SITE_DISTANCE) / 32 + 1;
        int total = rings * ANGLES;
        int cursor = (Integer) home.runtime.getOrDefault(CURSOR, 0);
        BlockPos unowned = (BlockPos) home.runtime.get(CURSOR + ".unowned");
        for (int n = 0; n < CANDIDATES_PER_CALL && cursor < total; n++, cursor++) {
            int dist = Colonization.MIN_SITE_DISTANCE + (cursor / ANGLES) * 32;
            double angle = (cursor % ANGLES) * Math.PI * 2 / ANGLES;
            int x = home.core().getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = home.core().getZ() + (int) Math.round(Math.sin(angle) * dist);
            UUID owner = Domain.countryAt(level, new ChunkPos(x >> 4, z >> 4));
            boolean own = home.country != null && home.country.equals(owner);
            if (owner != null && !own) continue;
            if (!own && unowned != null) continue;
            BlockPos pos = check(level, x, z);
            if (pos == null) continue;
            if (own) {
                home.runtime.remove(CURSOR);
                home.runtime.remove(CURSOR + ".unowned");
                return pos;
            }
            unowned = pos;
            home.runtime.put(CURSOR + ".unowned", pos);
        }
        if (cursor < total) {
            home.runtime.put(CURSOR, cursor);
            return null;
        }
        home.runtime.remove(CURSOR);
        home.runtime.remove(CURSOR + ".unowned");
        if (unowned == null) home.nextFounderTick = level.getGameTime() + 24000;
        return unowned;
    }

    /** The site's surface if the place suits a village, else null. */
    private static BlockPos check(ServerLevel level, int x, int z) {
        if (level.hasChunk(x >> 4, z >> 4)) {
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            return Colonization.roomToGrow(level, pos) && Colonization.candidate(level, pos) && Colonization.suitable(level, pos) ? pos : null;
        }
        BlockPos probe = new BlockPos(x, 0, z);
        if (!level.getWorldBorder().isWithinBounds(probe) || !Colonization.roomToGrow(level, probe) || Colonization.coreNear(level, probe, true)) {
            return null;
        }
        ColonySavedData data = ColonyManager.data(level);
        if (data.playerBuildNear(probe, 104)) return null;
        for (LandPlot plot : data.land) {
            if (plot.overlaps(probe, 0, 0, 104)) return null;
        }
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, centre = 0;
        for (int dx = -8; dx <= 8; dx += 8) {
            for (int dz = -8; dz <= 8; dz += 8) {
                int surface = gen.getBaseHeight(x + dx, z + dz, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                int floor = gen.getBaseHeight(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                // Water above the floor: a lake, a river or the sea.
                if (surface != floor || surface <= level.getSeaLevel()) return null;
                min = Math.min(min, surface);
                max = Math.max(max, surface);
                if (dx == 0 && dz == 0) centre = surface;
            }
        }
        return max - min <= 3 ? new BlockPos(x, centre, z) : null;
    }

    // ---------------------------------------------------------------- travel

    private static void depart(ServerLevel level, Colony colony, List<UUID> settlers, BlockPos site, long now) {
        for (UUID id : settlers) {
            ColonyRole trade = colony.roleOf(id);
            if (trade.isProfession()) colony.pendingProfessions.put(id, trade);
            colony.setRole(id, ColonyRole.FOUNDER, now);
        }
        colony.founder = settlers.get(0);
        colony.founderCompanions.clear();
        colony.founderCompanions.addAll(settlers.subList(1, settlers.size()));
        colony.founderTarget = site.immutable();
        colony.founderStartedTick = now;
        colony.founderVisits = 0;
        colony.founderCapital = 0;
        colony.founderLeaderLost = false;
        colony.founderReturning = false;
        colony.founderAbstract = true;
        colony.founderArriveTick = now + travel(colony.core(), site);
        colony.founderData.clear();
        // Members still held in the village's loaded (but not ticking) chunks leave with the party now.
        for (UUID id : settlers) {
            if (level.getEntity(id) instanceof Villager v && v.isAlive()) {
                CompoundTag data = Materializer.stash(v);
                if (data != null) colony.founderData.put(id, data);
            }
        }
        ColonyManager.markDirty(level);
        LOGGER.info("[world] a search party of {} ({} settlers) leaves for {}", Countries.villageName(colony), settlers.size(), site.toShortString());
    }

    private static long travel(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX(), dz = from.getZ() - to.getZ();
        return (long) Math.ceil(Math.sqrt(dx * dx + dz * dz) / BLOCKS_PER_TICK);
    }

    /** A member's entity loaded (its home chunk loaded while the party is away): its data goes with the party. */
    public static boolean onJoin(ServerLevel level, Entity e) {
        if (Materializer.spawning() || !(e instanceof Villager)) return false;
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (!colony.founderAbstract || !colony.isFounderParty(e.getUUID())) continue;
            if (!colony.founderData.containsKey(e.getUUID())) {
                CompoundTag tag = new CompoundTag();
                if (e.saveAsPassenger(tag)) colony.founderData.put(e.getUUID(), tag);
                ColonyManager.markDirty(level);
            }
            return true;
        }
        return false;
    }

    /** The scheduler pass of a party travelling as a record. */
    static void advance(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now < colony.founderArriveTick) return;
        if (colony.founderReturning) {
            comeHome(level, colony, now);
            return;
        }
        BlockPos target = colony.founderTarget;
        if (target == null) {
            turnBack(level, colony, now, "no site");
            return;
        }
        if (!SiteLoader.load(level, target)) {
            long since = (Long) colony.runtime.computeIfAbsent(WAITING, k -> now);
            if (now - since > LOAD_PATIENCE) turnBack(level, colony, now, "the site never loaded");
            return;
        }
        colony.runtime.remove(WAITING);
        BlockPos site = localSite(level, target);
        if (site == null) {
            turnBack(level, colony, now, "the site is unsuitable");
            return;
        }
        colony.founderTarget = site;
        // The party ate and built its camp on the way: the three visits of the live party.
        int visits = 0;
        while (visits < 3 && Colonization.consumeCapital(level, colony, 16)) visits++;
        if (visits < 3) {
            turnBack(level, colony, now, "the cargo ran out");
            return;
        }
        if (!materialize(level, colony, site)) return;
        colony.founderVisits = 3;
        colony.founderCapital = 24;
        colony.founderAbstract = false;
        colony.founderStartedTick = now;
        colony.runtime.put(HELD, site);
        ColonyManager.markDirty(level);
        LOGGER.info("[world] the search party of {} reaches {}", Countries.villageName(colony), site.toShortString());
    }

    /** The target, or the nearest suitable spot within the loaded area around it. */
    private static BlockPos localSite(ServerLevel level, BlockPos target) {
        int reach = SiteLoader.reach() - 8;
        for (int r = 0; r <= reach; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = target.getX() + dx, z = target.getZ() + dz;
                    BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (Colonization.candidate(level, pos) && Colonization.suitable(level, pos) && !Colonization.coreNear(level, pos, true)) return pos;
                }
            }
        }
        return null;
    }

    /**
     * The settlers appear by the site: from the data taken with them, or (a member that never left its unloaded
     * home chunk) as a new villager that takes over its household; the original is dropped when its chunk loads.
     */
    private static boolean materialize(ServerLevel level, Colony colony, BlockPos site) {
        List<UUID> members = new ArrayList<>(colony.founderCompanions);
        members.add(0, colony.founder);
        for (int i = 0; i < members.size(); i++) {
            UUID id = members.get(i);
            if (level.getEntity(id) instanceof Villager v && v.isAlive()) {
                // Already by the site (an earlier attempt); one left behind in a loaded chunk goes with the party.
                if (v.distanceToSqr(site.getX(), site.getY(), site.getZ()) < 64 * 64) continue;
                CompoundTag data = Materializer.stash(v);
                if (data != null) colony.founderData.put(id, data);
            }
            BlockPos spot = Colonization.standNear(level, site.offset(2 + i % 3, 0, 2 + i / 3));
            if (spot == null) spot = Colonization.standNear(level, site);
            if (spot == null) return false;
            CompoundTag data = colony.founderData.remove(id);
            if (data != null) {
                if (Materializer.spawnVillager(level, data, id, spot, colony) == null) return false;
                continue;
            }
            UUID fresh = UUID.randomUUID();
            if (Materializer.spawnVillager(level, null, fresh, spot, colony) == null) return false;
            takeOver(level, colony, id, fresh);
        }
        return true;
    }

    /** The new villager takes over the member's household, trade, purse and place in the party. */
    private static void takeOver(ServerLevel level, Colony colony, UUID old, UUID fresh) {
        Household h = colony.households.remove(old);
        if (h != null) {
            CompoundTag tag = h.save();
            NbtHelper.putUuid(tag, "owner", fresh);
            Household copy = Household.load(tag);
            if (copy != null) colony.households.put(fresh, copy);
        }
        ColonyRole trade = colony.pendingProfessions.remove(old);
        if (trade != null) colony.pendingProfessions.put(fresh, trade);
        Integer purse = colony.founderPurses.remove(old);
        if (purse != null) colony.founderPurses.put(fresh, purse);
        colony.roles.remove(old);
        colony.setRole(fresh, ColonyRole.FOUNDER, level.getGameTime());
        if (old.equals(colony.founder)) colony.founder = fresh;
        int at = colony.founderCompanions.indexOf(old);
        if (at >= 0) colony.founderCompanions.set(at, fresh);
        ColonyManager.data(level).fallen.add(old);
        if (level.getEntity(fresh) instanceof Villager v) net.betterthanvillage.colony.Professions.applyRole(level, colony, v);
    }

    private static void turnBack(ServerLevel level, Colony colony, long now, String why) {
        colony.runtime.remove(WAITING);
        colony.founderReturning = true;
        colony.founderArriveTick = now + travel(colony.founderTarget == null ? colony.core() : colony.founderTarget, colony.core());
        ColonyManager.markDirty(level);
        LOGGER.info("[world] the search party of {} turns back: {}", Countries.villageName(colony), why);
    }

    /** Back home: the cargo returns to the village; members that left the world with the party appear at home. */
    private static void comeHome(ServerLevel level, Colony colony, long now) {
        if (!colony.founderData.isEmpty()) {
            BlockPos core = colony.core();
            if (!SiteLoader.load(level, core)) return;
            for (UUID id : new ArrayList<>(colony.founderData.keySet())) {
                BlockPos spot = Colonization.standNear(level, core);
                if (spot == null) return;
                Materializer.spawnVillager(level, colony.founderData.remove(id), id, spot, colony);
            }
        }
        Colonization.deliverCargo(level, colony, colony, colony.core());
        LOGGER.info("[world] the search party of {} is home", Countries.villageName(colony));
        Colonization.abort(level, colony, true);
    }

    /**
     * While a materialized party founds its village live, the site stays loaded for it. If it gives up there (a
     * monster attack), it goes home as a record again instead of walking out of the loaded area.
     */
    static void hold(ServerLevel level, Colony colony) {
        if (!(colony.runtime.get(HELD) instanceof BlockPos site) || ColonyManager.isLive(level, colony)) return;
        SiteLoader.load(level, site);
        if (!colony.founderReturning) return;
        List<UUID> members = new ArrayList<>(colony.founderCompanions);
        if (!colony.founderLeaderLost && colony.founder != null) members.add(0, colony.founder);
        for (UUID id : members) {
            if (level.getEntity(id) instanceof Villager v && v.isAlive()) {
                CompoundTag data = Materializer.stash(v);
                if (data != null) colony.founderData.put(id, data);
            }
        }
        if (colony.founderLeaderLost && !colony.founderCompanions.isEmpty()) {
            colony.founder = colony.founderCompanions.remove(0);
            colony.founderLeaderLost = false;
        }
        colony.runtime.remove(HELD);
        colony.founderAbstract = true;
        colony.founderArriveTick = level.getGameTime() + travel(site, colony.core());
        ColonyManager.markDirty(level);
        LOGGER.info("[world] the search party of {} gives up at {} and goes home", Countries.villageName(colony), site.toShortString());
    }

    /** Debug line of a village's search party, or null. */
    public static String status(ServerLevel level, Colony colony) {
        if (colony.founder == null) return null;
        Entity leader = level.getEntity(colony.founder);
        long now = level.getGameTime();
        return "search party of " + Countries.villageName(colony) + ": " + (colony.founderAbstract ? "record" : "live")
                + (colony.founderReturning ? ", returning" : "") + ", target " + (colony.founderTarget == null ? "-" : colony.founderTarget.toShortString())
                + (colony.founderAbstract ? ", arrives in " + Math.max(0, colony.founderArriveTick - now) : "")
                + ", leader " + (leader == null ? "-" : leader.blockPosition().toShortString()) + ", companions " + colony.founderCompanions.size()
                + " (" + colony.founderCompanions.stream().filter(id -> level.getEntity(id) != null).count() + " in the world)"
                + ", capital " + colony.founderCapital + ", visits " + colony.founderVisits + ", stored " + colony.founderData.size();
    }

    static void ended(Colony colony) {
        colony.runtime.remove(HELD);
        colony.runtime.remove(WAITING);
    }
}
