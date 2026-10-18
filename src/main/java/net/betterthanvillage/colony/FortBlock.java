package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** One block of the wall/fortification plan: desired state at a position, or a removal. */
public record FortBlock(BlockPos pos, BlockState state, boolean remove) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pos.asLong());
        tag.put("state", NbtUtils.writeBlockState(state));
        tag.putBoolean("remove", remove);
        return tag;
    }

    public static FortBlock load(CompoundTag tag, HolderGetter<Block> blocks) {
        return new FortBlock(BlockPos.of(tag.getLong("pos")), NbtUtils.readBlockState(blocks, tag.getCompound("state")), tag.getBoolean("remove"));
    }
}
