package net.betterthanvillage.mixin.gather;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the trapdoor's block set type (hand-openable flag and sounds) to the door handler. */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockAccessor {
    @Accessor("type")
    BlockSetType btv$type();
}
