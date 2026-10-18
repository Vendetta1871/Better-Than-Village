package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Small NBT helpers shared by the persistence code. */
public final class NbtHelper {
    private NbtHelper() {
    }

    /** Reads a UUID stored either as a string or as an int[4]; null if absent or invalid. */
    public static UUID uuid(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        Tag raw = tag.get(key);
        if (raw instanceof IntArrayTag ints && ints.size() == 4) {
            return NbtUtils.loadUUID(ints);
        }
        if (raw != null && raw.getId() == Tag.TAG_STRING) {
            try {
                return UUID.fromString(tag.getString(key));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public static void putUuid(CompoundTag tag, String key, UUID value) {
        if (value != null) {
            tag.putString(key, value.toString());
        }
    }

    public static long[] packPositions(Collection<BlockPos> positions) {
        long[] out = new long[positions.size()];
        int i = 0;
        for (BlockPos pos : positions) {
            out[i++] = pos.asLong();
        }
        return out;
    }

    public static List<BlockPos> unpackPositions(long[] packed) {
        List<BlockPos> out = new ArrayList<>(packed.length);
        for (long l : packed) {
            out.add(BlockPos.of(l));
        }
        return out;
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Sanitizes a fractional accumulator into [0, 1). */
    public static double fraction(double v) {
        if (!Double.isFinite(v) || v < 0) {
            return 0;
        }
        return v - Math.floor(v);
    }
}
