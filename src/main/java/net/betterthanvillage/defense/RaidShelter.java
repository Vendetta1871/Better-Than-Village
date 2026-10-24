package net.betterthanvillage.defense;

import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.Damage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Raid predicates, enclosure test, shelter search and shelter reservations (02 §6.1, §6.3, §6.4). */
public final class RaidShelter {
    private static final Map<Villager, BlockPos> RESERVATIONS = new WeakHashMap<>();
    private static final int SEARCH_RADIUS = 64;
    private static final int MAX_PATH_TARGETS = 24;

    private RaidShelter() {
    }

    /**
     * A running raid within min(1024, radius + 32) of the core, a player at war inside (docs_v2/05-law.md) or an army
     * storming the village (docs_v2/07-warfare.md).
     */
    public static boolean isActive(ServerLevel level, Colony colony) {
        if (net.betterthanvillage.law.Wars.enemyInside(level, colony)) return true;
        if (net.betterthanvillage.warfare.Campaigns.assaulted(level, colony)) return true;
        int r = Math.min(1024, colony.radius() + 32);
        Raid raid = level.getRaids().getNearbyRaid(colony.core(), r * r);
        return raid != null && !raid.isOver() && !raid.isStopped() && raid.isActive();
    }

    /** Every role except GUARD is a civilian. */
    public static boolean civilian(Colony colony, Villager v) {
        return colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR;
    }

    public static void release(Villager v) {
        RESERVATIONS.remove(v);
    }

    public static void clearCaches() {
        RESERVATIONS.clear();
    }

    // ---------------------------------------------------------------- enclosure

    /** A small fully roofed room (<= 192 floor cells, doors closed) whose floor does not leak to the sky. */
    public static boolean enclosed(ServerLevel level, BlockPos start, Map<BlockPos, Boolean> cache) {
        Boolean cached = cache.get(start);
        if (cached != null) return cached;
        if (!Nav.standable(level, start) || level.canSeeSky(start)) {
            cache.put(start, false);
            return false;
        }
        Set<BlockPos> visited = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        boolean ok = true;
        outer:
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (visited.size() > 192 || Math.abs(cell.getX() - start.getX()) > 16 || Math.abs(cell.getZ() - start.getZ()) > 16
                    || level.canSeeSky(cell)) {
                ok = false;
                break;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = cell.relative(d);
                if (!level.isLoaded(n)) {
                    ok = false;
                    break outer;
                }
                BlockState s = level.getBlockState(n);
                if (s.is(BlockTags.DOORS)) continue;
                boolean thinSnow = s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) < 8;
                boolean passable = (thinSnow || s.getCollisionShape(level, n).isEmpty())
                        && level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty();
                if (passable && visited.add(n)) queue.add(n);
            }
        }
        for (BlockPos p : visited) {
            cache.put(p, ok);
        }
        return ok;
    }

    // ---------------------------------------------------------------- search

    /** Where a villager without a shelter goes: the nearest bed, else the nearest guard, else the core. */
    public static BlockPos fallback(ServerLevel level, Colony colony, Villager v) {
        BlockPos from = v.blockPosition();
        BlockPos bed = level.getPoiManager()
                .findClosest(t -> t.is(PoiTypes.HOME), p -> colony.containsXZ(p) && level.isLoaded(p), from, 48, PoiManager.Occupancy.ANY)
                .orElse(null);
        if (bed != null) return bed;
        Villager guard = null;
        double best = Double.MAX_VALUE;
        for (Villager g : level.getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(48), LivingEntity::isAlive)) {
            if (colony.roleOf(g.getUUID()) != ColonyRole.WARRIOR) continue;
            double d = g.distanceToSqr(v);
            if (d < best) {
                best = d;
                guard = g;
            }
        }
        return guard != null ? guard.blockPosition() : colony.core();
    }

    private static boolean playerProperty(ServerLevel level, BlockPos pos) {
        return ColonyManager.data(level).inLand(pos) || Protection.inPlayerOwnedJob(level, pos);
    }

    private static boolean inUnownedJob(Colony colony, BlockPos pos) {
        for (ConstructionJob job : colony.jobs) {
            if (job.owner == null && job.contains(pos)) return true;
        }
        return false;
    }

    /** Finds and reserves a reachable shelter cell for the villager, or null. */
    public static BlockPos findShelter(ServerLevel level, Colony colony, Villager v) {
        long now = level.getGameTime();
        Map<BlockPos, Boolean> cache = new HashMap<>();
        Set<BlockPos> candidates = new LinkedHashSet<>();
        // POI lookups read unloaded chunk columns from disk: search near the villager, not the whole colony
        List<BlockPos> beds = new ArrayList<>(level.getPoiManager()
                .getInRange(t -> t.is(PoiTypes.HOME), v.blockPosition(), SEARCH_RADIUS, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(p -> colony.containsXZ(p) && level.isLoaded(p)).toList());
        beds.sort(Comparator.comparingDouble(p -> p.distSqr(v.blockPosition())));
        for (BlockPos bed : beds.subList(0, Math.min(32, beds.size()))) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos c = bed.offset(dx, 0, dz);
                    if (!level.isLoaded(c) || !Nav.standable(level, c) || !enclosed(level, c, cache) || playerProperty(level, c)) continue;
                    if (Damage.isProtectedHousePiece(level, c) || inUnownedJob(colony, c)) candidates.add(c.immutable());
                }
            }
        }
        for (ConstructionJob job : colony.jobs) {
            if (job.owner != null || !job.countsAsCompleted()) continue;
            BoundingBox b = job.box();
            int cx = (b.minX() + b.maxX()) / 2;
            int cz = (b.minZ() + b.maxZ()) / 2;
            for (int dx = -4; dx <= 4; dx += 2) {
                for (int dz = -4; dz <= 4; dz += 2) {
                    for (int dy = 1; dy <= 4; dy++) {
                        BlockPos c = new BlockPos(cx + dx, b.minY() + dy, cz + dz);
                        if (!b.isInside(c) || !level.isLoaded(c) || !Nav.standable(level, c) || !enclosed(level, c, cache)
                                || playerProperty(level, c)) {
                            continue;
                        }
                        candidates.add(c);
                    }
                }
            }
        }
        candidates.removeIf(c -> Nav.isBlacklisted(v, c, now));
        Set<BlockPos> spread = new HashSet<>(candidates);
        for (Map.Entry<Villager, BlockPos> e : RESERVATIONS.entrySet()) {
            Villager other = e.getKey();
            if (other == v || !other.isAlive() || other.level() != level) continue;
            spread.removeIf(c -> c.distSqr(e.getValue()) < 2.0);
        }
        if (!spread.isEmpty()) {
            candidates.retainAll(spread);
        }
        if (candidates.isEmpty() || !PathBudget.tryAcquire(level)) return null;
        // the multi-target pathfinder scores every target per node: keep only the nearest ones
        BlockPos from = v.blockPosition();
        Set<BlockPos> targets = new LinkedHashSet<>(candidates.stream()
                .sorted(Comparator.comparingDouble(c -> c.distSqr(from))).limit(MAX_PATH_TARGETS).toList());
        PathNavigation nav = v.getNavigation();
        if (nav instanceof GroundPathNavigation ground) ground.setCanOpenDoors(true);
        AttributeInstance range = v.getAttribute(Attributes.FOLLOW_RANGE);
        double oldRange = range != null ? range.getBaseValue() : 0;
        if (range != null) range.setBaseValue(128);
        nav.setMaxVisitedNodesMultiplier(8f);
        Path path;
        try {
            path = nav.createPath(targets, 0);
        } finally {
            nav.resetMaxVisitedNodesMultiplier();
            if (range != null) range.setBaseValue(oldRange);
        }
        if (path == null || !path.canReach()) return null;
        BlockPos target = path.getTarget();
        RESERVATIONS.put(v, target);
        return target;
    }
}
