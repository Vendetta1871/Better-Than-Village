package net.betterthanvillage.colony;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The domain (docs_v2/04-land.md §Domains at world generation): land a country controls, per chunk.
 * <p>
 * At generation every chunk goes to the nearest generated village ("seat": the start chunk of its structure),
 * with a little noise on the borders, unless it is farther than the cap from every seat. Seats come from vanilla's
 * village placement (and from registered villages), so the split is the same whenever it is computed and never
 * changes when another village loads; it is computed on demand and cached. Only changes to it (land bought,
 * conquered or claimed by a founded village) are stored, in {@link ColonySavedData#domainChanges}.
 */
public final class Domain {
    /** Stored owner meaning "nobody" (a change that took a chunk out of every domain). */
    public static final UUID NOBODY = new UUID(0, 0);
    /** Border noise: up to this many blocks added to or taken from a seat's distance. */
    private static final double BORDER_NOISE = 24;
    /** Lattice of the border noise, in chunks. */
    private static final int NOISE_CELL = 4;
    private static final long NO_SEAT = Long.MIN_VALUE;

    private record VillageSet(RandomSpreadStructurePlacement placement, List<Holder<Structure>> structures) {
    }

    private static final class Cache {
        List<VillageSet> sets;
        /** Potential start chunk -> whether a village generates there. */
        final Map<Long, Boolean> present = new Long2ObjectOpenHashMap<>();
        /** Chunk -> seat chunk of the generation split (or NO_SEAT). */
        final Long2LongOpenHashMap seatOf = new Long2LongOpenHashMap();
    }

    private static final Map<ServerLevel, Cache> CACHES = new WeakHashMap<>();

    private Domain() {
    }

    private static Cache cache(ServerLevel level) {
        return CACHES.computeIfAbsent(level, l -> new Cache());
    }

    public static void clear() {
        CACHES.clear();
    }

    /** The country of a generated village that has not registered yet: derived from its seat. */
    public static UUID seatCountry(ServerLevel level, ChunkPos seat) {
        String key = "better_than_village:" + level.dimension().location() + ":" + seat.x + ":" + seat.z;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** The owning country of a chunk, or null if no country owns it. */
    public static UUID countryAt(ServerLevel level, ChunkPos chunk) {
        UUID changed = ColonyManager.data(level).domainChanges.get(chunk.toLong());
        if (changed != null) return NOBODY.equals(changed) ? null : changed;
        long seat = generationSeat(level, chunk);
        if (seat == NO_SEAT) return null;
        ChunkPos s = new ChunkPos(seat);
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (s.equals(colony.seat)) return colony.country;
        }
        return seatCountry(level, s);
    }

    /** The village (registered colony) whose share of the generation split holds the chunk, or null. */
    public static Colony shareOf(ServerLevel level, ChunkPos chunk) {
        long seat = generationSeat(level, chunk);
        if (seat == NO_SEAT) return null;
        ChunkPos s = new ChunkPos(seat);
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (s.equals(colony.seat)) return colony;
        }
        return null;
    }

    /** True if the chunk belongs to the colony's country. */
    public static boolean owns(ServerLevel level, Colony colony, ChunkPos chunk) {
        return colony.country != null && colony.country.equals(countryAt(level, chunk));
    }

    /** Stores a change of owner (null: nobody). */
    public static void transfer(ServerLevel level, ChunkPos chunk, UUID country) {
        ColonyManager.data(level).domainChanges.put(chunk.toLong(), country == null ? NOBODY : country);
        ColonyManager.markDirty(level);
    }

    /**
     * A founded village claims the unowned chunks within the cap around its bell; it takes nobody's land.
     * Returns the number of chunks claimed.
     */
    public static int claimAround(ServerLevel level, Colony colony) {
        int cap = ColonyConfig.get().land.domain_cap;
        ChunkPos center = new ChunkPos(colony.core());
        int r = (cap >> 4) + 1;
        int claimed = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos c = new ChunkPos(center.x + dx, center.z + dz);
                if (blockDist(c, center) > cap || countryAt(level, c) != null) continue;
                transfer(level, c, colony.country);
                claimed++;
            }
        }
        return claimed;
    }

    // ---------------------------------------------------------------- generation split

    private static double blockDist(ChunkPos a, ChunkPos b) {
        double dx = (a.x - b.x) * 16.0, dz = (a.z - b.z) * 16.0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Seat of the generation split for the chunk, or NO_SEAT. */
    private static long generationSeat(ServerLevel level, ChunkPos chunk) {
        Cache cache = cache(level);
        long key = chunk.toLong();
        if (cache.seatOf.containsKey(key)) return cache.seatOf.get(key);
        int cap = ColonyConfig.get().land.domain_cap;
        long best = NO_SEAT;
        double bestScore = Double.MAX_VALUE;
        for (ChunkPos seat : seatsNear(level, chunk, cap)) {
            double d = blockDist(chunk, seat);
            if (d > cap) continue;
            double score = d + BORDER_NOISE * noise(level.getSeed() ^ seat.toLong(), chunk.x, chunk.z);
            if (score < bestScore) {
                bestScore = score;
                best = seat.toLong();
            }
        }
        cache.seatOf.put(key, best);
        return best;
    }

    /** Seats within range blocks of the chunk: registered generated villages and vanilla's village starts. */
    private static List<ChunkPos> seatsNear(ServerLevel level, ChunkPos chunk, int range) {
        List<ChunkPos> out = new ArrayList<>();
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (colony.seat != null && blockDist(chunk, colony.seat) <= range) out.add(colony.seat);
        }
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        int rc = (range >> 4) + 1;
        for (VillageSet set : villageSets(level)) {
            int spacing = set.placement().spacing();
            int minCell = Math.floorDiv(chunk.x - rc, spacing), maxCell = Math.floorDiv(chunk.x + rc, spacing);
            int minCellZ = Math.floorDiv(chunk.z - rc, spacing), maxCellZ = Math.floorDiv(chunk.z + rc, spacing);
            for (int cx = minCell; cx <= maxCell; cx++) {
                for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                    ChunkPos start = set.placement().getPotentialStructureChunk(state.getLevelSeed(), cx * spacing, cz * spacing);
                    if (blockDist(chunk, start) > range || out.contains(start)) continue;
                    if (villageAt(level, set, start)) out.add(start);
                }
            }
        }
        return out;
    }

    /** True if a village of the set generates at the start chunk (the check /locate uses; cached). */
    private static boolean villageAt(ServerLevel level, VillageSet set, ChunkPos start) {
        Cache cache = cache(level);
        Boolean known = cache.present.get(start.toLong());
        if (known != null) return known;
        boolean present = false;
        for (Holder<Structure> structure : set.structures()) {
            StructureCheckResult r = level.structureManager().checkStructurePresence(start, structure.value(), set.placement(), false);
            if (r != StructureCheckResult.START_NOT_PRESENT) {
                present = true;
                break;
            }
        }
        cache.present.put(start.toLong(), present);
        return present;
    }

    /** Structure sets that place villages with a random spread (vanilla's "villages"). */
    private static List<VillageSet> villageSets(ServerLevel level) {
        Cache cache = cache(level);
        if (cache.sets != null) return cache.sets;
        List<VillageSet> sets = new ArrayList<>();
        for (Holder<StructureSet> holder : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) continue;
            List<Holder<Structure>> villages = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                if (entry.structure().is(StructureTags.VILLAGE)) villages.add(entry.structure());
            }
            if (!villages.isEmpty()) sets.add(new VillageSet(placement, villages));
        }
        cache.sets = sets;
        return sets;
    }

    /** Smooth value noise in [-1, 1] over chunk coordinates. */
    private static double noise(long seed, int x, int z) {
        int gx = Math.floorDiv(x, NOISE_CELL), gz = Math.floorDiv(z, NOISE_CELL);
        double fx = smooth((x - gx * NOISE_CELL) / (double) NOISE_CELL), fz = smooth((z - gz * NOISE_CELL) / (double) NOISE_CELL);
        double a = lattice(seed, gx, gz), b = lattice(seed, gx + 1, gz);
        double c = lattice(seed, gx, gz + 1), d = lattice(seed, gx + 1, gz + 1);
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lattice(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53 * 2 - 1;
    }
}
