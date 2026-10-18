package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Elder land-notice state for one player (owned by the society subsystem). */
public final class LandWarning {
    public UUID player;
    public UUID colony;
    public BlockPos pos;
    /** World tick when the notice was delivered, -1 when not yet delivered. */
    public long delivered = -1;
    /** Number of placements after the grace period, 0..3. */
    public int continued;
    public boolean punished;

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "player", player);
        NbtHelper.putUuid(tag, "colony", colony);
        tag.putLong("pos", pos.asLong());
        tag.putLong("delivered", delivered);
        tag.putInt("continued", continued);
        tag.putBoolean("punished", punished);
        return tag;
    }

    public static LandWarning load(CompoundTag tag) {
        LandWarning w = new LandWarning();
        w.player = NbtHelper.uuid(tag, "player");
        w.colony = NbtHelper.uuid(tag, "colony");
        if (w.player == null || w.colony == null) {
            return null;
        }
        w.pos = BlockPos.of(tag.getLong("pos"));
        w.delivered = tag.contains("delivered") ? tag.getLong("delivered") : -1;
        w.continued = NbtHelper.clamp(tag.getInt("continued"), 0, 3);
        w.punished = tag.getBoolean("punished");
        return w;
    }
}
