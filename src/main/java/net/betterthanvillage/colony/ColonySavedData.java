package net.betterthanvillage.colony;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.betterthanvillage.economy.Debt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension persistent state: colonies, player-placed blocks, land plots and land notices.
 */
public final class ColonySavedData extends SavedData {
    public static final String ID = "better_than_village";
    public static final int RECENT_PLACEMENT_TICKS = 1200;

    private final Map<UUID, Colony> colonies = new LinkedHashMap<>();
    private final LongOpenHashSet playerBlocks = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<LongSet> playerBlocksByChunk = new Long2ObjectOpenHashMap<>();
    private final Map<Long, UUID> blockOwners = new HashMap<>();
    public final List<LandPlot> land = new ArrayList<>();
    public final List<LandWarning> landWarnings = new ArrayList<>();
    /** Domain chunks whose owner differs from the generation split ({@link Domain}): chunk -> country. */
    public final Map<Long, UUID> domainChanges = new java.util.HashMap<>();
    /** Open debts by id (docs_v2/03-economy.md §Credit and debt notes). */
    public final Map<UUID, Debt> debts = new LinkedHashMap<>();
    /** Countries at war with players and signed treaties (docs_v2/05-law.md). */
    public final List<net.betterthanvillage.law.PlayerWar> wars = new ArrayList<>();
    public final List<net.betterthanvillage.law.PlayerWar.Treaty> treaties = new ArrayList<>();
    /** Runtime only: recent player placements, pos -> (player, tick). */
    private final Map<Long, RecentPlacement> recentPlacements = new HashMap<>();
    /** Runtime only: the level this data belongs to (lazy pruning of player blocks). */
    private ServerLevel level;
    /** Runtime only: villager UUID -> colony holding its role entry. */
    private final Map<UUID, Colony> memberIndex = new HashMap<>();
    private int memberIndexVersion;
    private boolean memberIndexDirty = true;

    public record RecentPlacement(UUID player, long tick) {
    }

    public static ColonySavedData get(ServerLevel level) {
        ColonySavedData data = level.getDataStorage().computeIfAbsent(FACTORY, ID);
        data.level = level;
        return data;
    }

    private static final SavedData.Factory<ColonySavedData> FACTORY = new SavedData.Factory<>(ColonySavedData::new, ColonySavedData::load, null);

    // ---------------------------------------------------------------- colonies

    public Collection<Colony> colonies() {
        return colonies.values();
    }

    public Colony colony(UUID id) {
        return id == null ? null : colonies.get(id);
    }

    public void addColony(Colony colony) {
        colonies.put(colony.id(), colony);
        memberIndexDirty = true;
        setDirty();
    }

    public void removeColony(Colony colony) {
        if (colonies.remove(colony.id(), colony)) {
            landWarnings.removeIf(w -> colony.id().equals(w.colony));
            memberIndexDirty = true;
            setDirty();
        }
    }

    /** Colony whose role map holds this villager (newest entry wins), or null. */
    public Colony roleColony(UUID villager) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (memberIndexDirty || memberIndexVersion != Colony.roleVersion) {
                rebuildMemberIndex();
            }
            Colony c = memberIndex.get(villager);
            if (c == null) {
                return null;
            }
            if (c.roles.containsKey(villager) && colonies.get(c.id()) == c) {
                return c;
            }
            // removed since the last rebuild
            memberIndexDirty = true;
        }
        return null;
    }

    private void rebuildMemberIndex() {
        memberIndex.clear();
        Map<UUID, Long> stamp = new HashMap<>();
        for (Colony colony : colonies.values()) {
            for (Map.Entry<UUID, Colony.RoleEntry> e : colony.roles.entrySet()) {
                Long prev = stamp.get(e.getKey());
                if (prev == null || e.getValue().changedAt() > prev) {
                    stamp.put(e.getKey(), e.getValue().changedAt());
                    memberIndex.put(e.getKey(), colony);
                }
            }
        }
        memberIndexVersion = Colony.roleVersion;
        memberIndexDirty = false;
    }

    // ---------------------------------------------------------------- player blocks

    public void recordPlacement(BlockPos pos, UUID player, long now) {
        long key = pos.asLong();
        playerBlocks.add(key);
        playerBlocksByChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> new LongOpenHashSet()).add(key);
        if (player != null) {
            blockOwners.put(key, player);
            recentPlacements.values().removeIf(r -> now - r.tick() > RECENT_PLACEMENT_TICKS);
            recentPlacements.put(key, new RecentPlacement(player, now));
        }
        setDirty();
    }

    public void forgetPlacement(BlockPos pos) {
        long key = pos.asLong();
        if (playerBlocks.remove(key)) {
            LongSet set = playerBlocksByChunk.get(ChunkPos.asLong(pos));
            if (set != null) {
                set.remove(key);
                if (set.isEmpty()) {
                    playerBlocksByChunk.remove(ChunkPos.asLong(pos));
                }
            }
            setDirty();
        }
        blockOwners.remove(key);
        recentPlacements.remove(key);
    }

    public boolean isPlayerBlock(BlockPos pos) {
        return playerBlocks.contains(pos.asLong());
    }

    public UUID blockOwner(BlockPos pos) {
        return blockOwners.get(pos.asLong());
    }

    /** Same player placed this block 0..1200 ticks ago. */
    public boolean isRecentAttackerBlock(BlockPos pos, UUID player, long now) {
        RecentPlacement r = recentPlacements.get(pos.asLong());
        return r != null && r.player().equals(player) && now - r.tick() >= 0 && now - r.tick() <= RECENT_PLACEMENT_TICKS;
    }

    /** True if any player block lies within horizontal Euclidean distance r. */
    public boolean playerBuildNear(BlockPos pos, int r) {
        long r2 = (long) r * r;
        int minCx = (pos.getX() - r) >> 4, maxCx = (pos.getX() + r) >> 4;
        int minCz = (pos.getZ() - r) >> 4, maxCz = (pos.getZ() + r) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LongSet set = playerBlocksInChunk(cx, cz);
                if (set == null) continue;
                for (long key : set) {
                    long dx = BlockPos.getX(key) - pos.getX();
                    long dz = BlockPos.getZ(key) - pos.getZ();
                    if (dx * dx + dz * dz <= r2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Player blocks within the given chunk (read-only view; may be null). When the chunk is loaded,
     * entries whose block became air are forgotten first. Not done in isPlayerBlock: the griefing
     * listener runs after a block was removed and must still see it as a player block.
     */
    public LongSet playerBlocksInChunk(int cx, int cz) {
        LongSet set = playerBlocksByChunk.get(ChunkPos.asLong(cx, cz));
        if (set != null && level != null && level.hasChunk(cx, cz)) {
            long[] stale = null;
            int n = 0;
            for (long key : set) {
                if (level.getBlockState(BlockPos.of(key)).isAir()) {
                    if (stale == null) stale = new long[set.size()];
                    stale[n++] = key;
                }
            }
            for (int i = 0; i < n; i++) {
                forgetPlacement(BlockPos.of(stale[i]));
            }
            if (n > 0) {
                set = playerBlocksByChunk.get(ChunkPos.asLong(cx, cz));
            }
        }
        return set;
    }

    // ---------------------------------------------------------------- land

    public LandPlot landAt(BlockPos pos) {
        for (LandPlot plot : land) {
            if (plot.contains(pos)) {
                return plot;
            }
        }
        return null;
    }

    public boolean inLand(BlockPos pos) {
        return landAt(pos) != null;
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Colony colony : colonies.values()) {
            list.add(colony.save());
        }
        tag.put("colonies", list);
        tag.putLongArray("player_blocks", playerBlocks.toLongArray());
        ListTag owners = new ListTag();
        blockOwners.forEach((pos, owner) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("pos", pos);
            e.putString("owner", owner.toString());
            owners.add(e);
        });
        tag.put("block_owners", owners);
        ListTag landList = new ListTag();
        land.forEach(p -> landList.add(p.save()));
        tag.put("land", landList);
        ListTag warnings = new ListTag();
        landWarnings.forEach(w -> warnings.add(w.save()));
        tag.put("land_warnings", warnings);
        ListTag debtList = new ListTag();
        debts.values().forEach(d -> debtList.add(d.save()));
        tag.put("debts", debtList);
        ListTag domain = new ListTag();
        domainChanges.forEach((chunk, country) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("chunk", chunk);
            NbtHelper.putUuid(e, "country", country);
            domain.add(e);
        });
        tag.put("domain_changes", domain);
        ListTag warList = new ListTag();
        wars.forEach(w -> warList.add(w.save()));
        tag.put("wars", warList);
        ListTag treatyList = new ListTag();
        treaties.forEach(t -> treatyList.add(t.save()));
        tag.put("treaties", treatyList);
        return tag;
    }

    public static ColonySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonySavedData data = new ColonySavedData();
        ListTag list = tag.getList("colonies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Colony colony = Colony.load(list.getCompound(i), BuiltInRegistries.BLOCK.asLookup());
            data.colonies.put(colony.id(), colony);
        }
        for (long key : tag.getLongArray("player_blocks")) {
            data.playerBlocks.add(key);
            data.playerBlocksByChunk.computeIfAbsent(ChunkPos.asLong(BlockPos.of(key)), k -> new LongOpenHashSet()).add(key);
        }
        ListTag owners = tag.getList("block_owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < owners.size(); i++) {
            CompoundTag e = owners.getCompound(i);
            UUID owner = NbtHelper.uuid(e, "owner");
            if (owner != null) {
                data.blockOwners.put(e.getLong("pos"), owner);
            }
        }
        ListTag landList = tag.getList("land", Tag.TAG_COMPOUND);
        for (int i = 0; i < landList.size(); i++) {
            LandPlot plot = LandPlot.load(landList.getCompound(i));
            if (plot != null) {
                data.land.add(plot);
            }
        }
        ListTag warnings = tag.getList("land_warnings", Tag.TAG_COMPOUND);
        for (int i = 0; i < warnings.size(); i++) {
            LandWarning w = LandWarning.load(warnings.getCompound(i));
            if (w != null) {
                data.landWarnings.add(w);
            }
        }
        ListTag debtList = tag.getList("debts", Tag.TAG_COMPOUND);
        for (int i = 0; i < debtList.size(); i++) {
            Debt d = Debt.load(debtList.getCompound(i));
            if (d != null) data.debts.put(d.id, d);
        }
        ListTag domain = tag.getList("domain_changes", Tag.TAG_COMPOUND);
        for (int i = 0; i < domain.size(); i++) {
            CompoundTag e = domain.getCompound(i);
            UUID country = NbtHelper.uuid(e, "country");
            if (country != null) data.domainChanges.put(e.getLong("chunk"), country);
        }
        ListTag warList = tag.getList("wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < warList.size(); i++) {
            var w = net.betterthanvillage.law.PlayerWar.load(warList.getCompound(i));
            if (w != null) data.wars.add(w);
        }
        ListTag treatyList = tag.getList("treaties", Tag.TAG_COMPOUND);
        for (int i = 0; i < treatyList.size(); i++) {
            var t = net.betterthanvillage.law.PlayerWar.Treaty.load(treatyList.getCompound(i));
            if (t != null) data.treaties.add(t);
        }
        return data;
    }

    /** Iterator helper used by sanitation passes. */
    public Iterator<Colony> colonyIterator() {
        return colonies.values().iterator();
    }
}
