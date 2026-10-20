package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.UUID;

/**
 * Land a player owns inside a village territory (docs_v2/04-land.md §Plots and houses): a bought plot or a bought
 * house. A rectangle in XZ given by its centre and half sizes (Chebyshev radii per axis).
 */
public record LandPlot(UUID owner, UUID colony, BlockPos center, int rx, int rz, boolean house) {
    public LandPlot {
        rx = Math.max(1, Math.min(32, rx));
        rz = Math.max(1, Math.min(32, rz));
    }

    public LandPlot(UUID owner, UUID colony, BlockPos center, int radius) {
        this(owner, colony, center, radius, radius, false);
    }

    /** A plot covering the box (a house) plus margin blocks. */
    public static LandPlot ofBox(UUID owner, UUID colony, BoundingBox box, int margin) {
        int cx = (box.minX() + box.maxX()) >> 1, cz = (box.minZ() + box.maxZ()) >> 1;
        int rx = Math.max(cx - box.minX(), box.maxX() - cx) + margin;
        int rz = Math.max(cz - box.minZ(), box.maxZ() - cz) + margin;
        return new LandPlot(owner, colony, new BlockPos(cx, box.minY(), cz), rx, rz, true);
    }

    public int minX() {
        return center.getX() - rx;
    }

    public int maxX() {
        return center.getX() + rx;
    }

    public int minZ() {
        return center.getZ() - rz;
    }

    public int maxZ() {
        return center.getZ() + rz;
    }

    public boolean contains(BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= rx && Math.abs(pos.getZ() - center.getZ()) <= rz;
    }

    public boolean intersects(BoundingBox box) {
        return box.maxX() >= minX() && box.minX() <= maxX() && box.maxZ() >= minZ() && box.minZ() <= maxZ();
    }

    /** True if the rectangle around c with half sizes (hx, hz), widened by gap blocks, overlaps this plot. */
    public boolean overlaps(BlockPos c, int hx, int hz, int gap) {
        return Math.abs(center.getX() - c.getX()) <= rx + hx + gap && Math.abs(center.getZ() - c.getZ()) <= rz + hz + gap;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", owner.toString());
        tag.putString("colony", colony.toString());
        tag.putLong("center", center.asLong());
        tag.putInt("radius", rx);
        tag.putInt("radius_z", rz);
        tag.putBoolean("house", house);
        return tag;
    }

    public static LandPlot load(CompoundTag tag) {
        UUID owner = NbtHelper.uuid(tag, "owner");
        UUID colony = NbtHelper.uuid(tag, "colony");
        if (owner == null || colony == null || !tag.contains("center")) {
            return null;
        }
        int rx = tag.contains("radius") ? tag.getInt("radius") : 8;
        int rz = tag.contains("radius_z") ? tag.getInt("radius_z") : rx;
        return new LandPlot(owner, colony, BlockPos.of(tag.getLong("center")), rx, rz, tag.getBoolean("house"));
    }
}
