package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.FortBlock;
import net.betterthanvillage.colony.FortificationState;
import net.betterthanvillage.colony.Territory;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.defense.RaidShelter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Walls and gates: planning tick, ring layout, wall builder selection, job queue and gate control (05 §4). */
public final class Fortifications {
    public static final int FAILURE_RETRY = 1200;
    /** Rings stand this far inside the territory border. */
    static final int RING_INSET = 2;
    /** A new ring is added once the settlement has grown this much past the current ring. */
    static final int RING_GAP = 16;
    /** Blocks between the outermost house (field, storage, pen, player plot) and the ring. */
    static final int SETTLED_MARGIN = 6;
    /** The smallest ring (territory radius units). */
    static final int MIN_RING = 24;
    /** A gate every this many columns of a ring (fields and woods lie outside; nobody walks far to a gate). */
    static final int GATE_SPACING = 24;
    /** The wall line runs this many blocks outside the outermost houses (storages, pen, plots). */
    public static final int HOUSE_MARGIN = 5;
    /** Directions a ring profile is sampled in. */
    static final int PROFILE_BINS = 256;
    /** Gaps between houses up to this many directions (a quarter turn) are bridged by a straight run of wall. */
    private static final int HULL_BINS = PROFILE_BINS / 4;
    /** The wall line keeps at least this far from the bell. */
    private static final int MIN_WALL = 12;

    private Fortifications() {
    }

    /** Runtime-only wall state of a colony (retry map, failure counts, caches). */
    static final class Runtime {
        final Map<BlockPos, Long> retry = new HashMap<>();
        final Map<BlockPos, Integer> failures = new HashMap<>();
        final Map<BlockState, Integer> stockCache = new HashMap<>();
        long stockTime = Long.MIN_VALUE;
        long builderTime = Long.MIN_VALUE;
        UUID builder;
        boolean builderHasJob;
        /** Wall gates the gate controller opened (only those are closed again). */
        final java.util.Set<BlockPos> opened = new java.util.HashSet<>();
    }

    static Runtime runtime(Colony colony) {
        return colony.runtime("btv_walls", Runtime::new);
    }

    // ---------------------------------------------------------------- queries

    public static boolean hasPendingWall(Colony colony) {
        return !colony.fortifications.pending.isEmpty();
    }

    /** Registered (built) wall block of any colony. */
    public static boolean isWallBlock(ServerLevel level, BlockPos pos) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (!c.fortifications.managed.isEmpty() && c.fortifications.managed.containsKey(pos)) return true;
        }
        return false;
    }

    /** A fence gate that is a built wall gate of any colony (05 §4.6). */
    public static boolean isWallGate(ServerLevel level, BlockPos pos) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            FortBlock b = c.fortifications.managed.get(pos);
            if (b != null && b.state().getBlock() instanceof FenceGateBlock) return true;
        }
        return false;
    }

    /** A wall gate of a village the mob's army is at war with: closed, it is a wall to the mob (it may open it). */
    public static boolean enemyGate(ServerLevel level, Mob mob, BlockPos pos) {
        Colony owner = wallGateOwner(level, pos);
        return owner != null && net.betterthanvillage.warfare.Campaigns.enemyOf(level, owner, mob);
    }

    /** The village whose built wall gate stands at the position, or null. */
    public static Colony wallGateOwner(ServerLevel level, BlockPos pos) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            FortBlock b = c.fortifications.managed.get(pos);
            if (b != null && b.state().getBlock() instanceof FenceGateBlock) return c;
        }
        return null;
    }

    /** Within margin + 2 of a wall ring of the colony (any ring, docs_v2/04-land.md §Layered walls). */
    public static boolean isNearWall(Colony colony, BlockPos pos, int margin) {
        FortificationState f = colony.fortifications;
        if (f.radius == 0) return false;
        double dx = pos.getX() - colony.core().getX(), dz = pos.getZ() - colony.core().getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        for (int r : rings(f)) {
            if (Math.abs(d - ringDist(colony, r, dx, dz)) <= margin + 2) return true;
        }
        return false;
    }

    /** Territory radii of the village's rings, outermost first. */
    public static List<Integer> ringRadii(Colony colony) {
        List<Integer> out = rings(colony.fortifications);
        out.sort(java.util.Comparator.reverseOrder());
        return out;
    }

    /** Built rings (cached by the number of built wall blocks). */
    private record BuiltRings(int managed, List<Integer> rings) {
    }

    /**
     * Rings that actually stand, outermost first: a ring counts once a third of its columns are built (sieges and
     * the strength model never count a wall that is only planned).
     */
    public static List<Integer> builtRings(Colony colony) {
        FortificationState f = colony.fortifications;
        if (colony.runtime.get("btv_built_rings") instanceof BuiltRings b && b.managed() == f.managed.size()) return b.rings();
        Map<Integer, java.util.Set<Long>> columns = new HashMap<>();
        for (BlockPos p : f.managed.keySet()) {
            int r = ringOf(colony, p);
            if (r >= 0) columns.computeIfAbsent(r, k -> new java.util.HashSet<>()).add(((long) p.getX() << 32) | (p.getZ() & 0xFFFFFFFFL));
        }
        List<Integer> out = new ArrayList<>();
        for (int r : ringRadii(colony)) {
            double planned = perimeter(colony, r);
            if (columns.getOrDefault(r, java.util.Set.of()).size() >= planned / 3) out.add(r);
        }
        colony.runtime.put("btv_built_rings", new BuiltRings(f.managed.size(), out));
        return out;
    }

    /** The ring (territory radius) a wall block at the position belongs to, or -1. */
    public static int ringOf(Colony colony, BlockPos pos) {
        double dx = pos.getX() - colony.core().getX(), dz = pos.getZ() - colony.core().getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        int best = -1;
        double bestErr = 3;
        for (int r : rings(colony.fortifications)) {
            double err = Math.abs(d - ringDist(colony, r, dx, dz));
            if (err <= bestErr) {
                bestErr = err;
                best = r;
            }
        }
        return best;
    }

    /** Distance of a ring's wall from the core in the direction (dx, dz). */
    public static double wallDistance(Colony colony, int r, double dx, double dz) {
        return ringDist(colony, r, dx, dz);
    }

    /** True if a built ring of the colony stands between the two points: one is inside it, the other outside. */
    public static boolean acrossWall(Colony colony, double ax, double az, double bx, double bz) {
        double cx = colony.core().getX() + 0.5, cz = colony.core().getZ() + 0.5;
        for (int r : builtRings(colony)) {
            if (insideRing(colony, r, ax - cx, az - cz) != insideRing(colony, r, bx - cx, bz - cz)) return true;
        }
        return false;
    }

    private static boolean insideRing(Colony colony, int r, double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz) < ringDist(colony, r, dx, dz);
    }

    /** Every ring of the colony: the old ones and the current (outermost) one. */
    static List<Integer> rings(FortificationState f) {
        List<Integer> out = new ArrayList<>(f.oldRings);
        if (f.radius > 0) out.add(f.radius);
        return out;
    }

    /** Distance from the core of the ring r in a direction: its profile, or the territory border for an older ring. */
    static double ringDist(Colony colony, int r, double dx, double dz) {
        float[] p = colony.fortifications.profiles.get(r);
        if (p == null) return r * Territory.factor(colony, dx, dz) - RING_INSET;
        double pos = binOf(dx, dz, p.length);
        int i = (int) Math.floor(pos);
        double t = pos - i;
        return p[Math.floorMod(i, p.length)] * (1 - t) + p[Math.floorMod(i + 1, p.length)] * t;
    }

    /** Fractional profile index of a direction (index i points at angle i * 2pi / bins). */
    private static double binOf(double dx, double dz, int bins) {
        double a = Math.atan2(dz, dx);
        if (a < 0) a += Math.PI * 2;
        return a / (Math.PI * 2) * bins;
    }

    private static double binAngle(int i) {
        return i * Math.PI * 2 / PROFILE_BINS;
    }

    /** The ring's distance per direction (an older ring's is sampled from the territory border). */
    private static float[] profileOf(Colony colony, int r) {
        float[] p = colony.fortifications.profiles.get(r);
        if (p != null) return p;
        float[] out = new float[PROFILE_BINS];
        for (int i = 0; i < PROFILE_BINS; i++) out[i] = (float) ringDist(colony, r, Math.cos(binAngle(i)), Math.sin(binAngle(i)));
        return out;
    }

    /** How far a ring may reach in a direction: the territory border, moved in by RING_INSET. */
    private static double limit(Colony colony, int i) {
        return colony.radius() * Territory.factor(colony, Math.cos(binAngle(i)), Math.sin(binAngle(i))) - RING_INSET;
    }

    /**
     * The wall line around the settlement (docs_v2/04-land.md §Layered walls) as a distance from the bell per
     * direction: the houses, storages, pen and plots with {@link #HOUSE_MARGIN} blocks around them, the gaps between
     * them bridged by straight runs of wall (a convex hull over at most a quarter turn), within the territory. So the
     * wall follows the outline of the village instead of a circle.
     */
    static float[] settlementProfile(ServerLevel level, Colony colony) {
        int cx = colony.core().getX(), cz = colony.core().getZ();
        float[] raw = new float[PROFILE_BINS];
        for (BoundingBox b : settlement(level, colony)) {
            int x0 = b.minX() - HOUSE_MARGIN - cx, x1 = b.maxX() + HOUSE_MARGIN - cx;
            int z0 = b.minZ() - HOUSE_MARGIN - cz, z1 = b.maxZ() + HOUSE_MARGIN - cz;
            // The farthest point of a box in a direction lies on its outline.
            for (int x = x0; x <= x1; x++) {
                envelope(raw, x, z0);
                envelope(raw, x, z1);
            }
            for (int z = z0; z <= z1; z++) {
                envelope(raw, x0, z);
                envelope(raw, x1, z);
            }
        }
        float[] out = new float[PROFILE_BINS];
        for (int i = 0; i < PROFILE_BINS; i++) {
            double best = hull(raw, i, HULL_BINS);
            // A gap wider than a quarter turn: bridged across half a turn (the run then passes nearer the bell).
            if (best <= 0) best = hull(raw, i, PROFILE_BINS / 2);
            out[i] = (float) Math.min(Math.max(best, MIN_WALL), limit(colony, i));
        }
        return out;
    }

    private static void envelope(float[] raw, int dx, int dz) {
        int i = Math.floorMod((int) Math.round(binOf(dx, dz, raw.length)), raw.length);
        raw[i] = Math.max(raw[i], (float) Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    /** The farthest of the raw distance at i and every straight run between points within span on either side. */
    private static double hull(float[] raw, int i, int span) {
        double best = raw[i];
        double ux = Math.cos(binAngle(i)), uz = Math.sin(binAngle(i));
        for (int j = i - span; j < i; j++) {
            float pj = raw[Math.floorMod(j, PROFILE_BINS)];
            if (pj <= 0) continue;
            double ax = pj * Math.cos(binAngle(j)), az = pj * Math.sin(binAngle(j));
            for (int k = i + 1; k <= i + span; k++) {
                float pk = raw[Math.floorMod(k, PROFILE_BINS)];
                if (pk <= 0) continue;
                double bx = pk * Math.cos(binAngle(k)), bz = pk * Math.sin(binAngle(k));
                // Where the ray from the bell at angle i crosses the segment a-b.
                double den = ux * (bz - az) - uz * (bx - ax);
                if (Math.abs(den) < 1e-9) continue;
                double t = (ax * bz - az * bx) / den;
                if (t > best) best = t;
            }
        }
        return best;
    }

    /** By how much the wanted line passes outside the ring somewhere. */
    private static double outgrown(float[] ring, float[] wanted) {
        double most = 0;
        for (int i = 0; i < PROFILE_BINS; i++) most = Math.max(most, wanted[i] - ring[i]);
        return most;
    }

    /**
     * Lays the current ring out along the profile: as a new ring outside the current one, which is kept (layered
     * walls), or in its place, its blocks taken down.
     */
    private static void setRing(ServerLevel level, Colony colony, float[] profile, boolean keepCurrent) {
        FortificationState f = colony.fortifications;
        int id = (int) Math.ceil(max(profile));
        for (int r : rings(f)) id = Math.max(id, r + 1);
        if (f.radius > 0) {
            if (keepCurrent) {
                f.oldRings.add(f.radius);
            } else {
                int old = f.radius;
                for (FortBlock b : f.managed.values()) {
                    if (ringOf(colony, b.pos()) == old) f.pending.put(b.pos(), new FortBlock(b.pos(), b.state(), true));
                }
                f.pending.values().removeIf(b -> !b.remove());
                f.profiles.remove(old);
                f.surveyed.clear();
                f.abandoned.clear();
                runtime(colony).retry.clear();
                runtime(colony).failures.clear();
            }
        }
        f.profiles.put(id, profile);
        f.radius = id;
        colony.runtime.remove("btv_built_rings");
        planRing(level, colony);
        ColonyManager.markDirty(level);
    }

    private static double max(float[] p) {
        double m = 0;
        for (float v : p) m = Math.max(m, v);
        return m;
    }

    /** Wall columns a ring has when complete (its outline length). */
    private static double perimeter(Colony colony, int r) {
        float[] p = colony.fortifications.profiles.get(r);
        if (p == null) return Math.PI * 2 * Math.max(8, r - RING_INSET);
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            int j = (i + 1) % p.length;
            double ax = p[i] * Math.cos(binAngle(i)), az = p[i] * Math.sin(binAngle(i));
            double bx = p[j] * Math.cos(binAngle(j)), bz = p[j] * Math.sin(binAngle(j));
            sum += Math.hypot(bx - ax, bz - az);
        }
        return sum;
    }

    /** Pathfinding: wall gates for villagers/golems; any non-pen fence gate for colony villagers (05 §4.6). */
    public static boolean gateWalkable(Mob mob, PathfindingContext context, int x, int y, int z) {
        if (!(mob.level() instanceof ServerLevel level) || !(mob instanceof Villager || mob instanceof IronGolem)) return false;
        BlockPos p = new BlockPos(x, y, z);
        if (!(context.getBlockState(p).getBlock() instanceof FenceGateBlock)) return false;
        Colony owner = wallGateOwner(level, p);
        // Wall gates never let an enemy army through (docs_v2/07-warfare.md §Sieges).
        if (owner != null) return !net.betterthanvillage.warfare.Campaigns.enemyOf(level, owner, mob);
        if (!(mob instanceof Villager v)) return false;
        if (ColonyManager.colonyAt(level, v.blockPosition()) == null && ColonyManager.foundingPartyColony(level, v.getUUID()) == null) return false;
        Colony at = ColonyManager.colonyAt(level, p);
        return at == null || at.livestockPen == null || !at.livestockPen.north(5).equals(p);
    }

    /** Raid active at the colony (raid shelter test, or an unfinished active raid near the core). */
    public static boolean raidActive(ServerLevel level, Colony colony) {
        if (RaidShelter.isActive(level, colony)) return true;
        Raid raid = level.getRaidAt(colony.core());
        if (raid == null || raid.isOver() || raid.isStopped() || !raid.isActive()) return false;
        double max = Math.min(1024, colony.radius() + 32);
        return raid.getCenter().distSqr(colony.core()) <= max * max;
    }

    // ---------------------------------------------------------------- planning tick

    /** Called every tick per active colony. */
    public static void tick(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now % 5 == 0) {
            gateControl(level, colony);
        }
        FortificationState f = colony.fortifications;
        ColonyConfig cfg = ColonyConfig.get();
        if (!cfg.roles.enable_village_walls || now < f.nextPlan || !HousePlanner.established(level, colony) || !colony.hasStorage() || raidActive(level, colony)) return;
        f.nextPlan = now + 1200;
        holdVillage(level, colony);
        boolean layered = cfg.land.layered_walls;
        // The ring encloses the settlement along its houses, not the whole territory (fields and woods stay outside).
        float[] wanted = settlementProfile(level, colony);
        if (f.radius == 0 || (!f.profiles.containsKey(f.radius) && !builtRings(colony).contains(f.radius))) {
            // The first ring, or one laid out along the territory border before and hardly built: along the houses.
            setRing(level, colony, wanted, false);
        } else {
            float[] current = profileOf(colony, f.radius);
            if (outgrown(current, wanted) >= RING_GAP) {
                // The settlement grew past the ring: a new ring outside, clear of the old one, which is kept (or
                // taken down without layers).
                if (layered) {
                    for (int i = 0; i < PROFILE_BINS; i++) {
                        wanted[i] = (float) Math.min(Math.max(wanted[i], current[i] + RING_GAP / 2.0), Math.max(limit(colony, i), current[i]));
                    }
                }
                setRing(level, colony, wanted, layered);
            } else {
                planRing(level, colony);
            }
        }
        // Every ring is maintained: missing blocks of built walls are put back.
        for (FortBlock b : f.managed.values()) {
            if (level.isLoaded(b.pos()) && level.getBlockState(b.pos()).isAir()) {
                f.pending.putIfAbsent(b.pos(), new FortBlock(b.pos(), b.state(), false));
            }
        }
        ColonyManager.markDirty(level);
    }

    /** Grows the territory (up to its maximum) to hold the settlement with the ring's margin, and the fields. */
    static void holdVillage(ServerLevel level, Colony colony) {
        int need = 0;
        for (BoundingBox b : settlement(level, colony)) need = Math.max(need, Territory.radiusFor(colony, b, RING_INSET + SETTLED_MARGIN));
        for (BlockPos p : colony.farmland) need = Math.max(need, Territory.radiusFor(colony, new BoundingBox(p), RING_INSET + 4));
        colony.growRadius(Math.min(need, Territory.maxRadius(colony)));
    }

    /**
     * The radius of the ring around the settlement: the houses (generated and built), storages, the pen and players'
     * plots with {@link #SETTLED_MARGIN} blocks around them, within the territory. Fields lie outside the walls.
     */
    static int settledRadius(ServerLevel level, Colony colony) {
        int need = MIN_RING;
        for (BoundingBox b : settlement(level, colony)) need = Math.max(need, Territory.radiusFor(colony, b, RING_INSET + SETTLED_MARGIN));
        return Math.min(need, colony.radius());
    }

    private record SettlementCache(long tick, List<BoundingBox> boxes) {
    }

    private record LineCache(long tick, float[] profile) {
    }

    /**
     * Inside the village: within the outermost wall ring, or without walls within the line a ring would take
     * (cached for a minute). The houses' surroundings, the streets and squares between them all count.
     */
    public static boolean insideWallLine(ServerLevel level, Colony colony, int x, int z) {
        double dx = x - colony.core().getX(), dz = z - colony.core().getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        FortificationState f = colony.fortifications;
        if (f.radius > 0) return d < ringDist(colony, f.radius, dx, dz) + 2;
        long now = level.getGameTime();
        float[] p;
        if (colony.runtime.get("btv_wall_line") instanceof LineCache c && now - c.tick() >= 0 && now - c.tick() < 1200) {
            p = c.profile();
        } else {
            p = settlementProfile(level, colony);
            colony.runtime.put("btv_wall_line", new LineCache(now, p));
        }
        double pos = binOf(dx, dz, p.length);
        int i = (int) Math.floor(pos);
        double t = pos - i;
        return d < p[Math.floorMod(i, p.length)] * (1 - t) + p[Math.floorMod(i + 1, p.length)] * t + 2;
    }

    /** Within margin blocks (x/z) of a house, storage, the pen or a plot of the village (boxes cached for 100 ticks). */
    public static boolean insideSettlement(ServerLevel level, Colony colony, int x, int z, int margin) {
        long now = level.getGameTime();
        List<BoundingBox> boxes;
        if (colony.runtime.get("btv_settlement") instanceof SettlementCache c && now - c.tick() >= 0 && now - c.tick() < 100) {
            boxes = c.boxes();
        } else {
            boxes = settlement(level, colony);
            colony.runtime.put("btv_settlement", new SettlementCache(now, boxes));
        }
        for (BoundingBox b : boxes) {
            if (x >= b.minX() - margin && x <= b.maxX() + margin && z >= b.minZ() - margin && z <= b.maxZ() + margin) return true;
        }
        return false;
    }

    /** The boxes of the settlement (see {@link #settledRadius}). */
    static List<BoundingBox> settlement(ServerLevel level, Colony colony) {
        List<BoundingBox> boxes = new ArrayList<>();
        // Of the generated village, its houses and centre (its streets run far out; the ring crosses them at gates).
        StructureStart start = level.structureManager().getStructureWithPieceAt(colony.core(), StructureTags.VILLAGE);
        if (start.isValid()) {
            for (StructurePiece piece : start.getPieces()) {
                if (net.betterthanvillage.society.VillagePieces.isBuilding(piece)) boxes.add(piece.getBoundingBox());
            }
        }
        for (ConstructionJob job : colony.jobs) {
            if (job.status == net.betterthanvillage.colony.JobStatus.BLOCKED && job.retryAfter == 0) continue;
            boxes.add(job.box());
        }
        for (BlockPos p : colony.managedStorages.keySet()) boxes.add(new BoundingBox(p));
        BlockPos pen = colony.livestockPen;
        if (pen != null) boxes.add(new BoundingBox(pen.getX() - 5, 0, pen.getZ() - 5, pen.getX() + 5, 0, pen.getZ() + 5));
        for (net.betterthanvillage.colony.LandPlot plot : ColonyManager.data(level).land) {
            if (!plot.colony().equals(colony.id())) continue;
            BlockPos c = plot.center();
            boxes.add(new BoundingBox(c.getX() - plot.rx(), 0, c.getZ() - plot.rz(), c.getX() + plot.rx(), 0, c.getZ() + plot.rz()));
        }
        return boxes;
    }

    /** Debug: the settled radius and the box farthest out ("piece/storage/job/pen/plot at x,z"). */
    public static String settledReason(ServerLevel level, Colony colony) {
        int best = 0;
        String why = "-";
        StructureStart start = level.structureManager().getStructureWithPieceAt(colony.core(), StructureTags.VILLAGE);
        List<BoundingBox> pieces = new ArrayList<>();
        if (start.isValid()) {
            for (StructurePiece piece : start.getPieces()) {
                if (net.betterthanvillage.society.VillagePieces.isBuilding(piece)) pieces.add(piece.getBoundingBox());
            }
        }
        List<BoundingBox> all = settlement(level, colony);
        for (BoundingBox b : all) {
            int r = Territory.radiusFor(colony, b, RING_INSET + SETTLED_MARGIN);
            if (r > best) {
                best = r;
                String kind = pieces.contains(b) ? "piece"
                        : colony.managedStorages.containsKey(new BlockPos(b.minX(), b.minY(), b.minZ())) ? "storage" : "job/pen/plot";
                why = kind + " " + b.minX() + "," + b.minZ() + ".." + b.maxX() + "," + b.maxZ();
            }
        }
        return "settled radius " + settledRadius(level, colony) + " (farthest: " + why + ")";
    }

    /** Debug: a ring the given distance outside the current one (layered: the current one is kept). */
    public static int addRing(ServerLevel level, Colony colony, int blocks) {
        FortificationState f = colony.fortifications;
        float[] base = f.radius == 0 ? settlementProfile(level, colony) : profileOf(colony, f.radius);
        colony.growRadius(Math.min((int) Math.ceil(max(base)) + blocks + RING_INSET, Territory.maxRadius(colony)));
        float[] next = new float[PROFILE_BINS];
        for (int i = 0; i < PROFILE_BINS; i++) next[i] = (float) Math.min(base[i] + blocks, Math.max(limit(colony, i), base[i]));
        setRing(level, colony, next, f.radius != 0 && ColonyConfig.get().land.layered_walls);
        return f.radius;
    }

    /** Relocation: clear the plan, queue removal of every built block, set the radius and plan the new ring. */
    public static void relocate(ServerLevel level, Colony colony, int radius) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        f.pending.clear();
        rt.retry.clear();
        rt.failures.clear();
        f.abandoned.clear();
        f.surveyed.clear();
        for (FortBlock b : f.managed.values()) {
            f.pending.put(b.pos(), new FortBlock(b.pos(), b.state(), true));
        }
        f.profiles.clear();
        f.radius = radius;
        planRing(level, colony);
        ColonyManager.markDirty(level);
    }

    /**
     * A plot site is accepted (05 §4.3): without layered walls a ring the plot would cross moves out; with layers
     * the territory grows and the next ring is added outside (tick).
     */
    public static void onPlotAccepted(ServerLevel level, Colony colony, int x0, int z0, int w, int d) {
        FortificationState f = colony.fortifications;
        if (f.radius == 0 || ColonyConfig.get().land.layered_walls) return;
        int need = Math.max(settledRadius(level, colony),
                Math.min(colony.radius(), Territory.radiusFor(colony, x0, z0, x0 + w - 1, z0 + d - 1, RING_INSET + SETTLED_MARGIN)));
        if (crossesRing(colony, f.radius, x0, z0, w, d) && need > f.radius) relocate(level, colony, need);
    }

    /** The footprint (plus 1) has columns on both sides of a ring of the colony: a house there would cross a wall. */
    public static boolean wallBlocksPlot(Colony colony, int x0, int z0, int w, int d) {
        if (colony.fortifications.radius == 0) return false;
        for (int r : rings(colony.fortifications)) {
            if (crossesRing(colony, r, x0, z0, w, d)) return true;
        }
        return false;
    }

    private static boolean crossesRing(Colony colony, int r, int x0, int z0, int w, int d) {
        boolean inside = false, outside = false;
        for (int x = x0 - 1; x <= x0 + w; x += Math.max(1, (w + 1) / 4)) {
            for (int z = z0 - 1; z <= z0 + d; z += Math.max(1, (d + 1) / 4)) {
                double dx = x - colony.core().getX(), dz = z - colony.core().getZ();
                if (Math.sqrt(dx * dx + dz * dz) < ringDist(colony, r, dx, dz)) inside = true;
                else outside = true;
            }
        }
        return inside && outside;
    }

    /** Postpones a wall job without counting a failure. */
    static void postpone(Colony colony, FortBlock job, long now) {
        runtime(colony).retry.put(job.pos(), now + FAILURE_RETRY);
    }

    // ---------------------------------------------------------------- ring layout

    /** Wall materials by villager type at the core: base, infill, pillar, gate. */
    record Materials(BlockState base, BlockState infill, BlockState pillar, BlockState gate) {
    }

    static Materials materials(ServerLevel level, Colony colony) {
        VillagerType type = VillagerType.byBiome(level.getBiome(colony.core()));
        String key = BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath();
        return switch (key) {
            case "desert" -> new Materials(Blocks.SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(),
                    Blocks.CHISELED_SANDSTONE.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState());
            case "savanna" -> new Materials(Blocks.COBBLESTONE.defaultBlockState(), Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
                    Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_FENCE_GATE.defaultBlockState());
            case "snow" -> new Materials(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
            case "taiga" -> new Materials(Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
            default -> new Materials(Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState());
        };
    }

    /**
     * Ring columns (x, 0, z) for territory radius r, insertion ordered: the territory border then (moved in by
     * RING_INSET). Where it would cross a player's property it goes around the property's outer side, so the
     * property stays inside (docs_v2/04-land.md §Layered walls).
     */
    static LinkedHashSet<BlockPos> ringColumns(ServerLevel level, Colony colony, int r) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        BlockPos core = colony.core();
        int steps = Math.max(64, (int) Math.ceil(Math.PI * 2 * r * 2));
        int px = 0, pz = 0;
        int fx = 0, fz = 0;
        for (int i = 0; i <= steps; i++) {
            double a = i * Math.PI * 2 / steps;
            double cos = Math.cos(a), sin = Math.sin(a);
            double dist = ringDist(colony, r, cos, sin);
            int x = (int) Math.round(core.getX() + cos * dist), z = (int) Math.round(core.getZ() + sin * dist);
            // Out of player property, along the ray.
            for (int k = 0; k < 80 && net.betterthanvillage.colony.Protection.playerProperty(level, new BlockPos(x, core.getY(), z)); k++) {
                dist += 1;
                x = (int) Math.round(core.getX() + cos * dist);
                z = (int) Math.round(core.getZ() + sin * dist);
            }
            if (i == 0) {
                fx = x;
                fz = z;
            } else {
                line(out, px, pz, x, z);
            }
            px = x;
            pz = z;
        }
        line(out, px, pz, fx, fz);
        return out;
    }

    /** 4-connected raster line (a wall without diagonal gaps). */
    private static void line(LinkedHashSet<BlockPos> out, int x0, int z0, int x1, int z1) {
        int x = x0, z = z0;
        out.add(new BlockPos(x, 0, z));
        while (x != x1 || z != z1) {
            int ex = Math.abs(x1 - x), ez = Math.abs(z1 - z);
            if (ex >= ez) x += Integer.signum(x1 - x);
            else z += Integer.signum(z1 - z);
            out.add(new BlockPos(x, 0, z));
        }
    }

    /** Adds build jobs for every loaded ring column not planned before (05 §4.3). */
    static void planRing(ServerLevel level, Colony colony) {
        FortificationState f = colony.fortifications;
        if (f.radius <= 0) return;
        Materials m = materials(level, colony);
        BlockPos core = colony.core();
        int index = -1;
        for (BlockPos col : ringColumns(level, colony, f.radius)) {
            index++;
            if (f.surveyed.contains(col) || !Terrain.loaded(level, col.getX(), col.getZ())) continue;
            f.surveyed.add(col);
            int x = col.getX(), z = col.getZ();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int i = 0; i < 20 && y > level.getMinBuildHeight(); i++) {
                BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
                if (below.canBeReplaced() || below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS)) {
                    y--;
                } else {
                    break;
                }
            }
            BlockState ground = level.getBlockState(new BlockPos(x, y - 1, z));
            if (!Terrain.natural(ground) && !Terrain.isRoad(ground)) continue;
            boolean xCross = Math.abs(x - core.getX()) <= 2;
            boolean spaced = index % GATE_SPACING == GATE_SPACING / 2;
            List<FortBlock> blocks = new ArrayList<>();
            if (xCross || Math.abs(z - core.getZ()) <= 1 || ground.is(Blocks.DIRT_PATH) || spaced) {
                // The gate opens along the ray from the bell (across the ring).
                Direction facing = Math.abs(z - core.getZ()) >= Math.abs(x - core.getX()) ? Direction.NORTH : Direction.EAST;
                blocks.add(new FortBlock(new BlockPos(x, y, z), m.gate().setValue(FenceGateBlock.FACING, facing), false));
                if (xCross && z > core.getZ()) {
                    blocks.add(new FortBlock(new BlockPos(x, y + 4, z), m.base(), false));
                }
            } else {
                boolean pillar = Math.floorMod(x + z, 6) == 0;
                blocks.add(new FortBlock(new BlockPos(x, y, z), m.base(), false));
                blocks.add(new FortBlock(new BlockPos(x, y + 1, z), pillar ? m.pillar() : m.infill(), false));
                blocks.add(new FortBlock(new BlockPos(x, y + 2, z), pillar ? m.pillar() : m.infill(), false));
                if (pillar) blocks.add(new FortBlock(new BlockPos(x, y + 3, z), m.base(), false));
            }
            for (FortBlock b : blocks) {
                if (f.abandoned.contains(b.pos()) || f.pending.size() >= 32768) continue;
                FortBlock existing = f.pending.get(b.pos());
                if (existing == null || !existing.remove()) {
                    f.pending.put(b.pos(), b);
                } else if (f.managed.containsKey(b.pos()) && f.managed.get(b.pos()).state() == b.state()) {
                    // The old wall already has this exact block here: keep it instead of removing it.
                    f.pending.remove(b.pos());
                }
            }
        }
    }

    // ---------------------------------------------------------------- jobs

    /** Accepted item for a wall block: logs/gates take any #logs; others any #stone_crafting_materials or sandstone. */
    public static boolean accepts(BlockState state, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(state.getBlock().asItem())) return true;
        if (state.is(BlockTags.LOGS) || state.getBlock() instanceof FenceGateBlock) return stack.is(ItemTags.LOGS);
        return stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || stack.is(Items.SANDSTONE) || stack.is(Items.RED_SANDSTONE);
    }

    /** Storage count of accepted material for a state (cached for 20 ticks). */
    static int stock(ServerLevel level, Colony colony, BlockState state) {
        Runtime rt = runtime(colony);
        long now = level.getGameTime();
        if (now - rt.stockTime >= 20 || now < rt.stockTime) {
            rt.stockCache.clear();
            rt.stockTime = now;
        }
        return rt.stockCache.computeIfAbsent(state, s -> ColonyStorage.count(level, colony, st -> accepts(s, st)));
    }

    /** Job lies within 2 (x/z) of an active plot box or within distance^2 4 of the cell above a road node. */
    static boolean nearWork(Colony colony, BlockPos pos) {
        for (ConstructionJob job : colony.jobs) {
            if (!HousePlanner.activePlot(job)) continue;
            BoundingBox b = job.box();
            if (pos.getX() >= b.minX() - 2 && pos.getX() <= b.maxX() + 2 && pos.getZ() >= b.minZ() - 2 && pos.getZ() <= b.maxZ() + 2) return true;
        }
        for (BlockPos r : colony.roadQueue) {
            if (r.above().distSqr(pos) <= 4) return true;
        }
        return false;
    }

    /** Selects the next wall job for a villager (05 §4.4); null if none. */
    static FortBlock selectJob(ServerLevel level, Colony colony, BlockPos from, boolean requireNear) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        long now = level.getGameTime();
        rt.retry.values().removeIf(t -> t <= now);
        boolean removals = false;
        for (FortBlock b : f.pending.values()) {
            if (b.remove() && !rt.retry.containsKey(b.pos()) && level.isLoaded(b.pos())) {
                removals = true;
                break;
            }
        }
        FortBlock best = null;
        boolean bestNear = false;
        double bestD = Double.MAX_VALUE;
        for (FortBlock b : f.pending.values()) {
            if (removals && !b.remove()) continue;
            if (rt.retry.containsKey(b.pos()) || !level.isLoaded(b.pos())) continue;
            if (!b.remove() && stock(level, colony, b.state()) <= stockReserve(colony)) continue;
            boolean near = nearWork(colony, b.pos());
            if (requireNear && !b.remove() && !near) continue;
            boolean priority = b.remove() && near;
            double d = from == null ? 0 : b.pos().distSqr(from);
            if (best == null || (priority && !bestNear) || (priority == bestNear && d < bestD)) {
                best = b;
                bestNear = priority;
                bestD = d;
            }
        }
        return best;
    }

    static boolean hasRemoval(Colony colony) {
        for (FortBlock b : colony.fortifications.pending.values()) {
            if (b.remove()) return true;
        }
        return false;
    }

    /** Alive adult builders in the colony area box sorted by UUID. */
    static List<Villager> sortedBuilders(ServerLevel level, Colony colony) {
        List<Villager> list = new ArrayList<>(SiteSearch.builders(level, colony));
        list.sort(Comparator.comparing(Villager::getUUID));
        return list;
    }

    /** Active wall builder test (05 §4.4), cached per tick. */
    public static boolean isWallBuilder(ServerLevel level, Colony colony, Villager v) {
        FortificationState f = colony.fortifications;
        if (f.pending.isEmpty() || !ColonyConfig.get().roles.enable_village_walls) return false;
        Runtime rt = runtime(colony);
        long now = level.getGameTime();
        if (rt.builderTime != now) {
            rt.builderTime = now;
            rt.builder = null;
            rt.builderHasJob = false;
            boolean removal = hasRemoval(colony);
            if ((removal || colony.roadQueue.isEmpty()) && !Damage.hasActionableRepair(level, colony)
                    && HousePlanner.established(level, colony) && !raidActive(level, colony)) {
                List<Villager> builders = sortedBuilders(level, colony);
                if (!builders.isEmpty()) {
                    Villager last = builders.get(builders.size() - 1);
                    // A lone mason builds what the elder puts first (BuildPriority); walls that only come down always go.
                    boolean single = builders.size() == 1 && colony.hasActiveConstruction()
                            && colony.buildPriority != BuildPriority.Choice.WALLS;
                    if (!(single && !removal)) {
                        rt.builder = last.getUUID();
                        rt.builderHasJob = selectJob(level, colony, last.blockPosition(), single) != null;
                    }
                }
            }
        }
        return rt.builderHasJob && v.getUUID().equals(rt.builder);
    }

    /** Material the wall leaves in storage: houses come first while one is under construction. */
    static int stockReserve(Colony colony) {
        return colony.hasActiveConstruction() && colony.buildPriority != BuildPriority.Choice.WALLS ? 128 : 32;
    }

    /** Requires a single builder with an active project to stay near the current works. */
    static boolean requireNear(ServerLevel level, Colony colony) {
        return sortedBuilders(level, colony).size() == 1 && colony.hasActiveConstruction();
    }

    /** Failure: retry after 1200 ticks, abandoned after 3 failures. */
    static void fail(Colony colony, FortBlock job, long now) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        int n = rt.failures.merge(job.pos(), 1, Integer::sum);
        if (n >= 3) {
            f.pending.remove(job.pos());
            rt.retry.remove(job.pos());
            rt.failures.remove(job.pos());
            if (f.abandoned.size() < 32768) f.abandoned.add(job.pos());
            f.skipped++;
        } else {
            rt.retry.put(job.pos(), now + FAILURE_RETRY);
        }
    }

    /** Completion of a wall job (05 §4.4). */
    /**
     * Debug (tests of sieges): every planned wall block in loaded chunks is put up at once, without materials.
     * Returns the blocks placed.
     */
    public static int buildNow(ServerLevel level, Colony colony) {
        int n = 0;
        for (FortBlock job : new ArrayList<>(colony.fortifications.pending.values())) {
            if (!level.isLoaded(job.pos())) continue;
            if (job.remove()) {
                SafeEditScope.run(() -> level.setBlock(job.pos(), Blocks.AIR.defaultBlockState(), 3));
                complete(level, colony, job, false);
                continue;
            }
            BlockState here = level.getBlockState(job.pos());
            if (!here.canBeReplaced() && here.getBlock() != job.state().getBlock()) {
                complete(level, colony, job, false);
                continue;
            }
            SafeEditScope.run(() -> level.setBlock(job.pos(), job.state(), 2));
            complete(level, colony, job, true);
            n++;
        }
        return n;
    }

    /** Debug: takes every ring down at once (loaded blocks) and forgets the plan; walls are planned again later. */
    public static int unwall(ServerLevel level, Colony colony) {
        FortificationState f = colony.fortifications;
        int n = 0;
        for (FortBlock b : f.managed.values()) {
            if (!level.isLoaded(b.pos()) || level.getBlockState(b.pos()).getBlock() != b.state().getBlock()) continue;
            SafeEditScope.run(() -> level.setBlock(b.pos(), Blocks.AIR.defaultBlockState(), 2));
            n++;
        }
        f.managed.clear();
        f.pending.clear();
        f.oldRings.clear();
        f.profiles.clear();
        f.surveyed.clear();
        f.abandoned.clear();
        f.skipped = 0;
        f.radius = 0;
        f.nextPlan = 0;
        Runtime rt = runtime(colony);
        rt.retry.clear();
        rt.failures.clear();
        colony.runtime.remove("btv_built_rings");
        ColonyManager.markDirty(level);
        return n;
    }

    static void complete(ServerLevel level, Colony colony, FortBlock job, boolean placed) {
        FortificationState f = colony.fortifications;
        Runtime rt = runtime(colony);
        f.pending.remove(job.pos());
        rt.retry.remove(job.pos());
        rt.failures.remove(job.pos());
        if (job.remove()) {
            f.managed.remove(job.pos());
        } else if (placed) {
            if (f.managed.size() < 16384) f.managed.put(job.pos(), job);
        } else {
            f.skipped++;
        }
        colony.repairs.remove(job.pos());
        ColonyManager.markDirty(level);
    }

    /** Abandons every build job in the x/z column of a position. */
    static void abandonColumn(Colony colony, BlockPos pos) {
        FortificationState f = colony.fortifications;
        List<BlockPos> drop = new ArrayList<>();
        for (FortBlock b : f.pending.values()) {
            if (!b.remove() && b.pos().getX() == pos.getX() && b.pos().getZ() == pos.getZ()) drop.add(b.pos());
        }
        for (BlockPos p : drop) {
            f.pending.remove(p);
            if (f.abandoned.size() < 32768) f.abandoned.add(p);
            f.skipped++;
        }
    }

    // ---------------------------------------------------------------- gates

    /** Opens wall gates for nearby villagers/golems and closes them otherwise (05 §4.6). */
    static void gateControl(ServerLevel level, Colony colony) {
        java.util.Set<BlockPos> opened = runtime(colony).opened;
        // Under an assault a gate the enemy opened is not taken over (and closed after a villager): it stays open.
        boolean assault = net.betterthanvillage.warfare.Campaigns.assaulted(level, colony);
        for (FortBlock b : colony.fortifications.managed.values()) {
            if (!(b.state().getBlock() instanceof FenceGateBlock) || !level.isLoaded(b.pos())) continue;
            BlockState s = level.getBlockState(b.pos());
            if (!(s.getBlock() instanceof FenceGateBlock) || s.getValue(FenceGateBlock.POWERED)) continue;
            boolean isOpen = s.getValue(FenceGateBlock.OPEN);
            boolean colonist = wanted(level, colony, b.pos());
            boolean want = colonist || playerAt(level, b.pos());
            if (want && !isOpen) {
                SafeEditScope.run(() -> level.setBlock(b.pos(), s.setValue(FenceGateBlock.OPEN, true), 3));
                opened.add(b.pos());
            } else if (colonist && isOpen && !assault && !playerAt(level, b.pos())) {
                // an open gate in colony use is closed after it (also after a restart lost the tracking)
                opened.add(b.pos());
            } else if (!want && isOpen && opened.remove(b.pos())) {
                // only gates opened here are closed again; a gate a player opened stays open
                SafeEditScope.run(() -> level.setBlock(b.pos(), s.setValue(FenceGateBlock.OPEN, false), 3));
            } else if (!isOpen) {
                opened.remove(b.pos());
            }
        }
    }

    private static boolean wanted(ServerLevel level, Colony colony, BlockPos gate) {
        AABB box = new AABB(gate);
        AABB near = box.inflate(1);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box.inflate(6, 2, 6), e -> e.isAlive() && (e instanceof Villager || e instanceof IronGolem))) {
            if (mob instanceof Villager v && (v.isSleeping() || v.isTrading())) continue;
            if (net.betterthanvillage.warfare.Campaigns.enemyOf(level, colony, mob)) continue;
            if (mob.getBoundingBox().intersects(near)) return true;
            Path path = mob.getNavigation().getPath();
            if (path != null && !path.isDone()) {
                for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 8); i++) {
                    Node n = path.getNode(i);
                    if (n.asBlockPos().distSqr(gate) <= 9) return true;
                }
            }
        }
        return false;
    }

    /** A player stands in or next to the gate. */
    private static boolean playerAt(ServerLevel level, BlockPos gate) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, new AABB(gate).inflate(1, 1, 1), p -> !p.isSpectator()).isEmpty();
    }

    /** Living entity occupying a block. */
    static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e.isAlive() && !e.isSpectator()).isEmpty();
    }

    static boolean blockedPosition(ServerLevel level, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos)) return true;
        if (data.isPlayerBlock(pos) && !level.getBlockState(pos).isAir()) return true;
        if (level.getBlockEntity(pos) != null) return true;
        if (level.getBlockState(pos).is(net.betterthanvillage.registry.ModTags.BUILDER_PROTECTED)) return true;
        return net.betterthanvillage.colony.Protection.beaconNear(level, pos);
    }
}
