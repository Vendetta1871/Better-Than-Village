package net.betterthanvillage.warfare;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the countries know about a player (docs_v2/07-warfare.md §War against the player): where the player spends
 * nights and logs out (presence per 32x32 cell), the base found from it and the home blocks, and the last strength
 * score. Stored in ColonySavedData.
 */
public final class PlayerIntel {
    public final UUID player;
    public String name = "";
    /** Cell (x >> 5, z >> 5 packed) -> presence weight. */
    public final Map<Long, Integer> presence = new HashMap<>();
    /** The base: the top cluster of home blocks and presence; null when none is known. */
    public BlockPos base;
    public int strength;
    /** Iron golems the player built. */
    public int golemsBuilt;
    public long lastSample;

    public PlayerIntel(UUID player) {
        this.player = player;
    }

    public static long cell(BlockPos pos) {
        return ((long) (pos.getX() >> 5) << 32) | ((pos.getZ() >> 5) & 0xFFFFFFFFL);
    }

    public static int cellX(long cell) {
        return (int) (cell >> 32);
    }

    public static int cellZ(long cell) {
        return (int) cell;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "player", player);
        tag.putString("name", name);
        long[] cells = new long[presence.size() * 2];
        int i = 0;
        for (Map.Entry<Long, Integer> e : presence.entrySet()) {
            cells[i++] = e.getKey();
            cells[i++] = e.getValue();
        }
        tag.putLongArray("presence", cells);
        if (base != null) tag.putLong("base", base.asLong());
        tag.putInt("strength", strength);
        tag.putInt("golems_built", golemsBuilt);
        tag.putLong("last_sample", lastSample);
        return tag;
    }

    public static PlayerIntel load(CompoundTag tag) {
        UUID player = NbtHelper.uuid(tag, "player");
        if (player == null) return null;
        PlayerIntel p = new PlayerIntel(player);
        p.name = tag.getString("name");
        long[] cells = tag.getLongArray("presence");
        for (int i = 0; i + 1 < cells.length && p.presence.size() < 4096; i += 2) {
            p.presence.put(cells[i], (int) Math.max(0, Math.min(1_000_000, cells[i + 1])));
        }
        if (tag.contains("base")) p.base = BlockPos.of(tag.getLong("base"));
        p.strength = Math.max(0, tag.getInt("strength"));
        p.golemsBuilt = Math.max(0, tag.getInt("golems_built"));
        p.lastSample = tag.getLong("last_sample");
        return p;
    }
}
