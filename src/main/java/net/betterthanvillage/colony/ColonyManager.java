package net.betterthanvillage.colony;

import net.betterthanvillage.BetterThanVillage;
import net.betterthanvillage.ai.nav.DoorHandler;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.construction.HousePlanner;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.society.Colonization;
import net.betterthanvillage.society.FilmMode;
import net.betterthanvillage.society.LandNotices;
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

    /** Colony whose horizontal circle contains pos; nearest core wins. Null if none. */
    public static Colony colonyAt(ServerLevel level, BlockPos pos) {
        Colony best = null;
        long bestDist = Long.MAX_VALUE;
        for (Colony colony : data(level).colonies()) {
            long dx = pos.getX() - colony.core().getX();
            long dz = pos.getZ() - colony.core().getZ();
            long d = dx * dx + dz * dz;
            if (d <= (long) colony.radius() * colony.radius() && d < bestDist) {
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

    /** Founder party colony first, else positional colony. */
    public static Colony colonyOf(ServerLevel level, Villager villager) {
        Colony party = foundingPartyColony(level, villager.getUUID());
        return party != null ? party : colonyAt(level, villager.blockPosition());
    }

    public static ColonyRole roleOf(ServerLevel level, Villager villager) {
        Colony colony = colonyOf(level, villager);
        return colony == null ? ColonyRole.CITIZEN : colony.roleOf(villager.getUUID());
    }

    /** Alive villagers in the colony box whose positional colony is this one (babies included). */
    public static List<Villager> members(ServerLevel level, Colony colony) {
        BlockPos c = colony.core();
        int r = colony.radius();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c.getX() - r, level.getMinBuildHeight(), c.getZ() - r,
                c.getX() + r + 1, level.getMaxBuildHeight(), c.getZ() + r + 1);
        List<Villager> out = new ArrayList<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            Colony at = colonyAt(level, v.blockPosition());
            if (at == colony) {
                out.add(v);
            }
        }
        return out;
    }

    public static List<Villager> adultMembers(ServerLevel level, Colony colony) {
        List<Villager> out = new ArrayList<>();
        for (Villager v : members(level, colony)) {
            if (!v.isBaby()) {
                out.add(v);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- registration

    /** Registers a generated village bell or merges it into an existing nearby colony. */
    public static void registerOrMerge(ServerLevel level, BlockPos bell, BoundingBox structureBox) {
        ColonySavedData data = data(level);
        for (Colony colony : data.colonies()) {
            if (colony.core().equals(bell)) {
                expandFromStructure(colony, structureBox);
                data.setDirty();
                return;
            }
        }
        for (Colony colony : data.colonies()) {
            if (structureBox.isInside(colony.core()) || colony.core().distSqr(bell) < MERGE_DISTANCE_SQ) {
                expandFromStructure(colony, structureBox);
                data.setDirty();
                return;
            }
        }
        Colony colony = new Colony(UUID.randomUUID(), bell);
        colony.lastThreatTick = Colony.NEVER;
        colony.simulatedUntil = 0;
        colony.initialDevelopment = true;
        expandFromStructure(colony, structureBox);
        data.addColony(colony);
        BetterThanVillage.LOGGER.info("Registered generated vanilla village at {}", bell);
    }

    public static void expandFromStructure(Colony colony, BoundingBox box) {
        BlockPos core = colony.core();
        int dx = Math.max(Math.abs(box.minX() - core.getX()), Math.abs(box.maxX() - core.getX()));
        int dz = Math.max(Math.abs(box.minZ() - core.getZ()), Math.abs(box.maxZ() - core.getZ()));
        int candidate = Math.max(Colony.MIN_RADIUS, (int) Math.ceil(Math.hypot(dx, dz)) + 16);
        colony.growRadius(Math.min(512, candidate));
    }

    /** Creates a colony at a player/founder-placed bell (not merged). */
    public static Colony createColony(ServerLevel level, BlockPos bell) {
        Colony colony = new Colony(UUID.randomUUID(), bell);
        colony.simulatedUntil = 0;
        data(level).addColony(colony);
        return colony;
    }

    /** Bell right-click: alarm the colony whose territory contains the bell and whose core is within 32 blocks. */
    public static void bellAlarm(ServerLevel level, BlockPos bell) {
        Colony colony = colonyAt(level, bell);
        if (colony != null && colony.core().distSqr(bell) <= 1024) {
            colony.lastThreatTick = level.getGameTime();
            markDirty(level);
        }
    }

    /** Drops colony references to a villager that left the world for good (death, conversion, discard). */
    public static void forgetVillager(ServerLevel level, UUID id) {
        boolean changed = false;
        for (Colony colony : data(level).colonies()) {
            changed |= colony.roles.remove(id) != null;
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

    public static boolean terrainChangesAllowed(ServerLevel level) {
        return ColonyConfig.get().roles.allow_terrain_changes
                && level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
    }

    // ---------------------------------------------------------------- world tick

    public static void tickLevel(ServerLevel level) {
        long now = level.getGameTime();
        ColonySavedData data = data(level);
        if (now % 20 == 0) {
            Protection.sanitizeStorages(level);
        }
        LandNotices.tick(level);
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
    }

    private static void tickColony(ServerLevel level, Colony colony, int[] scanBudget, int[] catchupBudget) {
        if (!isActive(level, colony)) {
            return;
        }
        long now = level.getGameTime();
        BlockPos core = colony.core();
        boolean entityTicking = level.shouldTickBlocksAt(ChunkPos.asLong(core)) && level.isPositionEntityTicking(core)
                || (colony.lastMemberTick != Colony.NEVER && now - colony.lastMemberTick <= 1);
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
        if (colony.deferredCatchupTicks > 0 && ColonyConfig.get().village.offline_development && terrainChangesAllowed(level)) {
            return;
        }
        catchupBudget[0] -= Math.max(0, OfflineSim.catchupStep(level, colony, catchupBudget[0]));
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

    /** Clears every static runtime cache (server shutdown). */
    public static void clearCaches() {
        PathBudget.clear();
        FilmMode.clearCaches();
        ThreatManager.clearCaches();
        OfflineSim.clearCaches();
        DoorHandler.clearCaches();
        net.betterthanvillage.defense.RaidShelter.clearCaches();
        net.betterthanvillage.defense.GuardShield.clearCaches();
        net.betterthanvillage.ai.nav.WaypointNavigator.clearCaches();
        TerrainSurvey.clear();
    }
}
