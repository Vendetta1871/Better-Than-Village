package net.betterthanvillage.colony;

import net.betterthanvillage.BetterThanVillage;
import net.betterthanvillage.ai.nav.DoorHandler;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.construction.HousePlanner;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.economy.Households;
import net.betterthanvillage.economy.StockScope;
import net.betterthanvillage.society.Colonization;
import net.betterthanvillage.society.FilmMode;
import net.betterthanvillage.society.OfflineSim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Colony registry access, membership queries and the main per-world tick. */
public final class ColonyManager {
    public static final int MERGE_DISTANCE_SQ = 128 * 128;

    private ColonyManager() {
    }

    public static ColonySavedData data(ServerLevel level) {
        return ColonySavedData.get(level);
    }

    public static void markDirty(ServerLevel level) {
        data(level).setDirty();
    }

    // ---------------------------------------------------------------- membership

    /** Colony whose territory contains pos; nearest core wins. Null if none. */
    public static Colony colonyAt(ServerLevel level, BlockPos pos) {
        Colony best = null;
        long bestDist = Long.MAX_VALUE;
        for (Colony colony : data(level).colonies()) {
            long dx = pos.getX() - colony.core().getX();
            long dz = pos.getZ() - colony.core().getZ();
            long d = dx * dx + dz * dz;
            if (d < bestDist && colony.containsXZ(pos)) {
                bestDist = d;
                best = colony;
            }
        }
        return best;
    }

    /** First colony whose active founder expedition contains this UUID. */
    public static Colony foundingPartyColony(ServerLevel level, UUID uuid) {
        for (Colony colony : data(level).colonies()) {
            if (colony.isFounderParty(uuid)) {
                return colony;
            }
        }
        return null;
    }

    /** Role holders stay members this far outside their colony's circle. */
    public static final int MEMBER_MARGIN = 16;

    /**
     * Membership-aware colony of a villager: founder party colony first, else the colony holding its
     * role entry while it stays within radius + 16 (XZ), else the positional colony.
     */
    public static Colony colonyOf(ServerLevel level, Villager villager) {
        UUID id = villager.getUUID();
        Colony party = foundingPartyColony(level, id);
        if (party != null) {
            return party;
        }
        Colony caravan = net.betterthanvillage.world.Caravans.homeOf(level, id);
        if (caravan != null) {
            return caravan;
        }
        Colony member = data(level).roleColony(id);
        if (member != null && member.withinXZ(villager.blockPosition(), MEMBER_MARGIN)) {
            return member;
        }
        return colonyAt(level, villager.blockPosition());
    }

    public static ColonyRole roleOf(ServerLevel level, Villager villager) {
        Colony colony = colonyOf(level, villager);
        return colony == null ? ColonyRole.CITIZEN : colony.roleOf(villager.getUUID());
    }

    /** Alive villagers whose colony ({@link #colonyOf}) is this one (babies included). */
    public static List<Villager> members(ServerLevel level, Colony colony) {
        BlockPos c = colony.core();
        int r = colony.radius() + MEMBER_MARGIN;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c.getX() - r, level.getMinBuildHeight(), c.getZ() - r,
                c.getX() + r + 1, level.getMaxBuildHeight(), c.getZ() + r + 1);
        List<Villager> out = new ArrayList<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            if (colonyOf(level, v) == colony) {
                out.add(v);
            }
        }
        return out;
    }

    public static List<Villager> adults(List<Villager> members) {
        List<Villager> out = new ArrayList<>();
        for (Villager v : members) {
            if (!v.isBaby()) {
                out.add(v);
            }
        }
        return out;
    }

    public static List<Villager> adultMembers(ServerLevel level, Colony colony) {
        return adults(members(level, colony));
    }

    // ---------------------------------------------------------------- registration

    /** Registers a generated village bell or merges it into the nearest existing colony in range. */
    public static void registerOrMerge(ServerLevel level, BlockPos bell, BoundingBox structureBox, List<BoundingBox> pieces, ChunkPos start,
                                       boolean abandoned) {
        ColonySavedData data = data(level);
        for (Colony colony : data.colonies()) {
            if (colony.core().equals(bell)) {
                expandFromStructure(colony, pieces);
                setSeat(level, colony, start);
                if (abandoned && !colony.abandonedVillage) {
                    colony.abandonedVillage = true;
                }
                data.setDirty();
                return;
            }
        }
        Colony nearest = null;
        double best = Double.MAX_VALUE;
        for (Colony colony : data.colonies()) {
            double d = colony.core().distSqr(bell);
            if ((structureBox.isInside(colony.core()) || d < MERGE_DISTANCE_SQ) && d < best) {
                best = d;
                nearest = colony;
            }
        }
        if (nearest != null) {
            expandFromStructure(nearest, pieces);
            data.setDirty();
            return;
        }
        Colony colony = new Colony(UUID.randomUUID(), bell);
        setSeat(level, colony, start);
        colony.lastThreatTick = Colony.NEVER;
        colony.simulatedUntil = 0;
        colony.initialDevelopment = true;
        colony.abandonedVillage = abandoned;
        expandFromStructure(colony, pieces);
        data.addColony(colony);
        BetterThanVillage.LOGGER.info("Registered generated vanilla village at {}", bell);
    }

    /** A generated village sits on its structure start; while it is its own country, the country is named after it. */
    private static void setSeat(ServerLevel level, Colony colony, ChunkPos start) {
        if (colony.seat != null) return;
        colony.seat = start;
        if (colony.id().equals(colony.country)) colony.country = Domain.seatCountry(level, start);
    }

    /** The generated village is what it is: its territory holds every piece, past the construction maximum if need be. */
    public static void expandFromStructure(Colony colony, List<BoundingBox> pieces) {
        for (BoundingBox box : pieces) {
            colony.growRadius(Math.min(512, Territory.radiusFor(colony, box, 8)));
        }
    }

    /** Creates a colony at a player/founder-placed bell (not merged). */
    public static Colony createColony(ServerLevel level, BlockPos bell) {
        Colony colony = new Colony(UUID.randomUUID(), bell);
        colony.simulatedUntil = 0;
        data(level).addColony(colony);
        return colony;
    }

    public static final long BELL_ALARM_COOLDOWN = 200;

    /**
     * A player actually rang a bell: alarm the colony whose territory contains the bell and whose core is
     * within 32 blocks, unless the player is a friendly owner of that colony; at most once per 200 ticks.
     */
    public static void bellAlarm(ServerLevel level, BlockPos bell, UUID player) {
        Colony colony = colonyAt(level, bell);
        if (colony == null || colony.core().distSqr(bell) > 1024 || isFriendlyOwner(level, colony, player)) {
            return;
        }
        long now = level.getGameTime();
        if (colony.lastBellAlarmTick != Colony.NEVER && now - colony.lastBellAlarmTick >= 0 && now - colony.lastBellAlarmTick < BELL_ALARM_COOLDOWN) {
            return;
        }
        colony.lastBellAlarmTick = now;
        colony.lastThreatTick = now;
        markDirty(level);
    }

    /** Property owner of the colony, owner of a commissioned house, or owner of a land plot of the colony. */
    public static boolean isFriendlyOwner(ServerLevel level, Colony colony, UUID player) {
        if (player == null) {
            return false;
        }
        if (player.equals(colony.propertyOwner)) {
            return true;
        }
        for (ConstructionJob job : colony.jobs) {
            if (player.equals(job.owner)) {
                return true;
            }
        }
        for (LandPlot plot : data(level).land) {
            if (player.equals(plot.owner()) && colony.id().equals(plot.colony())) {
                return true;
            }
        }
        return false;
    }

    /** Drops colony references to a villager that left the world for good (death, conversion, discard). */
    public static void forgetVillager(ServerLevel level, UUID id) {
        boolean changed = false;
        for (Colony colony : data(level).colonies()) {
            changed |= colony.roles.remove(id) != null;
            changed |= colony.pendingProfessions.remove(id) != null;
            changed |= Households.dissolve(level, colony, id);
            if (id.equals(colony.elder)) {
                colony.elder = null;
                changed = true;
            }
            for (ConstructionJob job : colony.jobs) {
                if (id.equals(job.builder)) {
                    job.releaseBuilder();
                    changed = true;
                }
            }
        }
        if (changed) {
            markDirty(level);
        }
    }

    // ---------------------------------------------------------------- activity

    public static boolean isActive(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        return level.hasChunk(core.getX() >> 4, core.getZ() >> 4) && level.getBlockState(core).is(Blocks.BELL);
    }

    /**
     * Live mode (docs_v2/08-simulation.md §Two modes): the village's core is entity-ticking (a player is near) or a
     * member ticked a moment ago. Otherwise the village is abstract and runs on its ledger.
     */
    public static boolean isLive(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        if (colony.lastMemberTick != Colony.NEVER && level.getGameTime() - colony.lastMemberTick <= 1) return true;
        return level.hasChunk(core.getX() >> 4, core.getZ() >> 4) && level.shouldTickBlocksAt(ChunkPos.asLong(core))
                && level.isPositionEntityTicking(core);
    }

    public static boolean terrainChangesAllowed(ServerLevel level) {
        return ColonyConfig.get().roles.allow_terrain_changes
                && level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
    }

    // ---------------------------------------------------------------- world tick

    public static void tickLevel(ServerLevel level) {
        // Village steps always use the treasury; a villager step that threw must not leak its scope.
        StockScope.exit();
        long now = level.getGameTime();
        ColonySavedData data = data(level);
        if (now % 20 == 0) {
            Protection.sanitizeStorages(level);
        }
        net.betterthanvillage.law.Law.tick(level);
        DoorHandler.processRecorded(level);
        int[] scanBudget = {768};
        int catchupBudget = level.players().isEmpty() ? ColonyConfig.get().village.offline_blocks_per_tick
                : ColonyConfig.get().village.offline_arrival_blocks_per_tick;
        List<Colony> list = new ArrayList<>(data.colonies());
        if (!list.isEmpty()) {
            Collections.rotate(list, -(int) Math.floorMod(now, (long) list.size()));
        }
        int[] catchup = {catchupBudget};
        for (Colony colony : list) {
            tickColony(level, colony, scanBudget, catchup);
        }
        Colonization.tick(level);
        FilmMode.tick(level);
        net.betterthanvillage.world.WorldScheduler.tick(level);
    }

    private static void tickColony(ServerLevel level, Colony colony, int[] scanBudget, int[] catchupBudget) {
        if (!isActive(level, colony)) {
            trackDeadColony(level, colony);
            return;
        }
        if (colony.deadTicks != 0) {
            colony.deadTicks = 0;
            markDirty(level);
        }
        long now = level.getGameTime();
        BlockPos core = colony.core();
        boolean entityTicking = isLive(level, colony);
        if (net.betterthanvillage.world.WorldScheduler.enabled() && colony.householdsSettled) {
            if (!entityTicking) {
                // Going abstract while the chests are still loaded: the ledger starts from their real contents.
                net.betterthanvillage.world.Ledgers.open(level, colony);
            } else if (net.betterthanvillage.world.Ledgers.isOpen(level, colony)) {
                net.betterthanvillage.world.Ledgers.apply(level, colony, 64);
            } else if (Math.floorMod(now + core.asLong(), 200L) == 0) {
                net.betterthanvillage.world.Ledgers.capture(level, colony);
            }
        }
        boolean playerNear = false;
        double view = (level.getServer().getPlayerList().getViewDistance() + 1) * 16.0;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - core.getX();
            double dz = player.getZ() - core.getZ();
            if (dx * dx + dz * dz <= view * view) {
                playerNear = true;
                break;
            }
        }
        if (!entityTicking && !playerNear) {
            return;
        }
        // Census needs real stock numbers (first storage scan done); an empty colony re-checks every 20 ticks, not every tick.
        boolean scanned = colony.lastScanFinish != Colony.NEVER;
        if (colony.lastPopulation == 0 && entityTicking && scanned
                && (colony.lastCensusTick == Colony.NEVER || now - colony.lastCensusTick >= 20)) {
            Census.run(level, colony);
        }
        OfflineSim.bookkeeping(level, colony);
        // A deferred catch-up only postpones the catch-up step; the colony itself keeps running.
        boolean deferred = colony.deferredCatchupTicks > 0 && ColonyConfig.get().village.offline_development && terrainChangesAllowed(level);
        if (!deferred) {
            catchupBudget[0] -= Math.max(0, OfflineSim.catchupStep(level, colony, catchupBudget[0]));
        }
        if (!entityTicking) {
            return;
        }
        if (colony.scanCursor < 0 && (colony.lastScanFinish == Colony.NEVER || now - colony.lastScanFinish >= 100)) {
            StorageScanner.start(colony);
        }
        if (colony.scanCursor >= 0) {
            int budget = Math.min(256, scanBudget[0]);
            scanBudget[0] -= StorageScanner.advance(level, colony, budget);
        }
        long phase = now + core.asLong();
        if (Math.floorMod(phase, 10L) == 0 && ThreatManager.threatNear(level, colony)) {
            colony.lastThreatTick = now;
        }
        if (scanned && (colony.lastCensusTick == Colony.NEVER || now - colony.lastCensusTick >= 100)) {
            Census.run(level, colony);
            Census.afterCensus(level, colony);
        }
        if (now % 200 == 0) {
            markDirty(level);
        }
        if (Math.floorMod(phase, 4L) == 0) {
            HousePlanner.tick(level, colony);
        }
        Fortifications.tick(level, colony);
        net.betterthanvillage.construction.LandWork.tick(level, colony);
        net.betterthanvillage.construction.LampPosts.tick(level, colony);
        if (ColonyConfig.get().debug.render_build_boxes && now % 20 == 0) {
            for (ConstructionJob job : colony.jobs) {
                if (job.status == JobStatus.QUEUED || job.status == JobStatus.BUILDING) {
                    BoundingBox b = job.box();
                    for (int i = 0; i < 8; i++) {
                        double x = (i & 1) == 0 ? b.minX() : b.maxX() + 1;
                        double y = (i & 2) == 0 ? b.minY() : b.maxY() + 1;
                        double z = (i & 4) == 0 ? b.minZ() : b.maxZ() + 1;
                        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    /** Loaded ticks without bell and members after which a colony is dissolved (3 in-game days). */
    public static final long DISSOLVE_TICKS = 72000;

    /** Bell gone: count loaded, member-less time and dissolve the colony once it reaches 3 days. */
    private static void trackDeadColony(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        long now = level.getGameTime();
        if (!level.hasChunk(core.getX() >> 4, core.getZ() >> 4) || Math.floorMod(now + core.asLong(), 100L) != 0) {
            return;
        }
        if (colony.founder != null && !colony.founderLeaderLost) {
            return;
        }
        if (!members(level, colony).isEmpty()) {
            if (colony.deadTicks != 0) {
                colony.deadTicks = 0;
                markDirty(level);
            }
            return;
        }
        colony.deadTicks += 100;
        markDirty(level);
        if (colony.deadTicks >= DISSOLVE_TICKS) {
            dissolve(level, colony);
        }
    }

    /** Removes a colony; its roles, jobs, repairs and storage registrations go with it (chests and items stay). */
    public static void dissolve(ServerLevel level, Colony colony) {
        colony.runtime.clear();
        colony.storages.clear();
        data(level).removeColony(colony);
        ThreatManager.forget(colony.id());
        BetterThanVillage.LOGGER.info("Dissolved colony {} at {}: bell gone and no members", colony.id(), colony.core());
    }

    /** Clears every static runtime cache (server shutdown). */
    public static void clearCaches() {
        PathBudget.clear();
        FilmMode.clearCaches();
        ThreatManager.clearCaches();
        OfflineSim.clearCaches();
        DoorHandler.clearCaches();
        net.betterthanvillage.defense.RaidShelter.clearCaches();
        net.betterthanvillage.defense.GuardShield.clearCaches();
        net.betterthanvillage.defense.GuardPatrol.clearCaches();
        net.betterthanvillage.ai.nav.WaypointNavigator.clearCaches();
        TerrainSurvey.clear();
        Protection.clearCaches();
        net.betterthanvillage.world.WorldScheduler.clearCaches();
        net.betterthanvillage.world.Caravans.clearCaches();
    }
}
