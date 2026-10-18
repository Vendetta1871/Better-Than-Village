package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.UUID;

/** Automatic house planning, offline house planning and player commissions (05 §2.2). */
public final class HousePlanner {
    public static final int COMMISSION_SEARCH_CALLS = 32;

    private HousePlanner() {
    }

    /** Called every 4 ticks per active colony. */
    public static void tick(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (colony.catchupHouses > 0 && colony.catchupLabor > 0) return;
        if (now < colony.nextHousePlanTick || !colony.roadQueue.isEmpty()) return;
        ColonyConfig cfg = ColonyConfig.get();
        if (!cfg.roles.enable_auto_expansion) return;
        int population = colony.population();
        if (population == 0) return;
        int completed = completeCount(colony);
        boolean commission = colony.propertyOwner != null;
        int freeBeds = Math.max(0, colony.beds() - population);
        int wantedFreeBeds = Mth.clamp((population + 3) / 4, 4, 16);
        if (!(freeBeds < wantedFreeBeds || completed < maxHouses() || commission)) return;
        if (hasActiveProject(colony)) return;
        String biome = biomeKey(level, colony.core());
        HouseCatalog.Entry entry = commission ? HouseCatalog.commission(level, biome, colony.propertyLevel) : HouseCatalog.home(level, biome, completed);
        if (entry == null) return;
        if (colony.houseSiteCursor < 0) colony.houseSiteCursor = 0;
        Vec3i size = entry.size();
        BlockPos site = SiteSearch.step(level, colony, size.getX(), size.getZ(), size.getY(), false);
        if (site != null) {
            ConstructionJob job = newJob(entry, site);
            job.owner = colony.propertyOwner;
            job.propertyLevel = colony.propertyLevel;
            if (job.owner == null && colony.catchupHouses > 0 && colony.catchupLabor > 0 && now < colony.catchupExpires) {
                job.catchup = true;
            }
            if (job.owner != null) {
                colony.propertyOwner = null;
                colony.propertyLevel = 0;
            }
            colony.jobs.add(job);
            int reach = (int) Math.ceil(Math.sqrt(colony.core().distSqr(site))) + Math.max(size.getX(), size.getZ()) + 4;
            colony.growRadius(Math.min(cfg.village.expansion_radius_max, reach));
            ColonyManager.markDirty(level);
        }
        if (colony.houseSiteCursor < 0 && !hasActiveProject(colony)) {
            colony.growRadius(Math.min(cfg.village.expansion_radius_max, colony.radius() + 16));
            colony.nextHousePlanTick = now + 200;
        }
    }

    /**
     * Offline variant used by the catch-up simulation (05 §2.2): plans one unowned catch-up plot
     * without the reachability test. Returns true when a plot was queued.
     */
    public static boolean planOffline(ServerLevel level, Colony colony) {
        ColonyConfig cfg = ColonyConfig.get();
        long now = level.getGameTime();
        if (!cfg.roles.enable_auto_expansion || hasActiveProject(colony) || now < colony.nextHousePlanTick) return false;
        int unowned = 0;
        for (ConstructionJob job : colony.jobs) {
            if (job.owner == null && job.countsAsCompleted()) unowned++;
        }
        if (unowned >= maxHouses()) {
            colony.catchupHouses = 0;
            return false;
        }
        HouseCatalog.Entry entry = HouseCatalog.home(level, biomeKey(level, colony.core()), completeCount(colony));
        if (entry == null) return false;
        if (colony.houseSiteCursor < 0) colony.houseSiteCursor = 0;
        Vec3i size = entry.size();
        BlockPos site = SiteSearch.step(level, colony, size.getX(), size.getZ(), size.getY(), true);
        boolean queued = false;
        if (site != null) {
            ConstructionJob job = newJob(entry, site);
            job.catchup = true;
            colony.jobs.add(job);
            queued = true;
            ColonyManager.markDirty(level);
        }
        if (colony.houseSiteCursor < 0 && !hasActiveProject(colony)) {
            colony.growRadius(Math.min(cfg.village.expansion_radius_max, colony.radius() + 16));
            colony.nextHousePlanTick = now + 200;
        }
        return queued;
    }

    /**
     * Commissioned house (05 §2.2, called by the property-deed code): tier 1..3, no pending
     * commission, empty road queue, terrain changes allowed and no unfinished plot owned by the
     * player. Up to 32 site-search calls; on success an owned plot is created immediately and
     * returned, else null.
     */
    public static ConstructionJob placeOrder(ServerLevel level, Colony colony, ServerPlayer player, int tier) {
        return placeOrder(level, colony, player.getUUID(), tier);
    }

    public static ConstructionJob placeOrder(ServerLevel level, Colony colony, UUID player, int tier) {
        if (tier < 1 || tier > 3 || colony.propertyOwner != null || !colony.roadQueue.isEmpty() || !ColonyManager.terrainChangesAllowed(level)) {
            return null;
        }
        for (ConstructionJob job : colony.jobs) {
            if (player.equals(job.owner) && job.status != JobStatus.COMPLETE) return null;
        }
        HouseCatalog.Entry entry = HouseCatalog.commission(level, biomeKey(level, colony.core()), tier);
        if (entry == null) return null;
        if (colony.houseSiteCursor < 0) colony.houseSiteCursor = 0;
        Vec3i size = entry.size();
        BlockPos site = null;
        for (int i = 0; i < COMMISSION_SEARCH_CALLS && site == null && colony.houseSiteCursor >= 0; i++) {
            site = SiteSearch.step(level, colony, size.getX(), size.getZ(), size.getY(), false);
        }
        if (site == null) return null;
        ConstructionJob job = newJob(entry, site);
        job.owner = player;
        job.propertyLevel = tier;
        colony.jobs.add(job);
        ColonyManager.markDirty(level);
        return job;
    }

    private static ConstructionJob newJob(HouseCatalog.Entry entry, BlockPos site) {
        ConstructionJob job = new ConstructionJob();
        job.template = entry.id();
        job.origin = site.immutable();
        job.sizeX = Math.max(1, entry.size().getX());
        job.sizeY = Math.max(1, entry.size().getY());
        job.sizeZ = Math.max(1, entry.size().getZ());
        job.planVersion = BuildPlan.PLAN_VERSION;
        return job;
    }

    // ---------------------------------------------------------------- helpers

    public static int maxHouses() {
        return Mth.clamp(ColonyConfig.get().village.max_constructed_houses, 4, 200);
    }

    public static int completeCount(Colony colony) {
        int n = 0;
        for (ConstructionJob job : colony.jobs) {
            if (job.status == JobStatus.COMPLETE) n++;
        }
        return n;
    }

    /** Any plot QUEUED, BUILDING, or BLOCKED with a pending retry. */
    public static boolean hasActiveProject(Colony colony) {
        return colony.hasActiveConstruction();
    }

    /** "Active plot": not COMPLETE and not abandoned (BLOCKED with retry 0). */
    public static boolean activePlot(ConstructionJob job) {
        return job.status != JobStatus.COMPLETE && !(job.status == JobStatus.BLOCKED && job.retryAfter == 0);
    }

    /** Abandons a plot (BLOCKED, retry 0), idles the site search and allows the next expansion now. */
    public static void abandon(ServerLevel level, Colony colony, ConstructionJob job) {
        job.status = JobStatus.BLOCKED;
        job.builder = null;
        job.retryAfter = 0;
        colony.houseSiteCursor = -1;
        colony.nextHousePlanTick = level.getGameTime();
        ColonyManager.markDirty(level);
    }

    public static void block(ConstructionJob job, long until) {
        job.status = JobStatus.BLOCKED;
        job.builder = null;
        job.retryAfter = until;
    }

    public static void complete(ServerLevel level, ConstructionJob job) {
        job.status = JobStatus.COMPLETE;
        job.completedOnce = true;
        job.builder = null;
        ColonyManager.markDirty(level);
    }

    /** setPlan (05 §2.1). */
    public static void setPlan(ConstructionJob job, int steps, long now) {
        if (job.planVersion != BuildPlan.PLAN_VERSION) {
            job.nextBlock = 0;
            job.lastProgressTick = now;
            job.planVersion = BuildPlan.PLAN_VERSION;
        }
        if (job.totalBlocks == 0) job.nextBlock = 0;
        job.totalBlocks = steps;
        if (job.lastProgressTick == Long.MIN_VALUE) job.lastProgressTick = now;
    }

    /** Biome key at a position mapped to a catalog biome (05 §2.2). */
    public static String biomeKey(ServerLevel level, BlockPos pos) {
        String path = level.getBiome(pos).unwrapKey().map(k -> k.location().getPath()).orElse("plains");
        if (path.contains("desert")) return "desert";
        if (path.contains("savanna")) return "savanna";
        if (path.contains("snow") || path.contains("frozen")) return "snowy";
        if (path.contains("taiga")) return "taiga";
        return "plains";
    }

    /** Pen overlap with margin m (pen center +- (5+m)). */
    public static boolean overlapsPen(Colony colony, ConstructionJob job, int margin) {
        BlockPos pen = colony.livestockPen;
        if (pen == null) return false;
        int r = 5 + margin;
        var b = job.box();
        return b.minX() <= pen.getX() + r && b.maxX() >= pen.getX() - r && b.minZ() <= pen.getZ() + r && b.maxZ() >= pen.getZ() - r;
    }

    /** protectPen (05 §2.5): returns false (and blocks/abandons the plot) when it overlaps the pen. */
    public static boolean protectPen(ServerLevel level, Colony colony, ConstructionJob job) {
        if (!overlapsPen(colony, job, 1) || job.countsAsCompleted()) return true;
        if (job.owner != null) {
            block(job, level.getGameTime() + 1200);
        } else {
            abandon(level, colony, job);
        }
        ColonyManager.markDirty(level);
        return false;
    }
}
