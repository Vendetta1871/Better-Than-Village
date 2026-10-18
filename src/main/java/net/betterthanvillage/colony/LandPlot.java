package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.UUID;

/** A purchased square land plot (Chebyshev radius in XZ). */
public record LandPlot(UUID owner, UUID colony, BlockPos center, int radius) {
    public LandPlot {
        radius = Math.max(1, Math.min(32, radius));
    }

    public boolean contains(BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius;
    }

    public boolean intersects(BoundingBox box) {
        return box.maxX() >= center.getX() - radius && box.minX() <= center.getX() + radius
                && box.maxZ() >= center.getZ() - radius && box.minZ() <= center.getZ() + radius;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", owner.toString());
        tag.putString("colony", colony.toString());
        tag.putLong("center", center.asLong());
        tag.putInt("radius", radius);
        return tag;
    }

    public static LandPlot load(CompoundTag tag) {
        UUID owner = NbtHelper.uuid(tag, "owner");
        UUID colony = NbtHelper.uuid(tag, "colony");
        if (owner == null || colony == null || !tag.contains("center")) {
            return null;
        }
        return new LandPlot(owner, colony, BlockPos.of(tag.getLong("center")), tag.contains("radius") ? tag.getInt("radius") : 8);
    }
}
