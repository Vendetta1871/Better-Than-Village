package net.betterthanvillage.mixin.society;

import net.betterthanvillage.society.PlayerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hoppers and hopper minecarts cannot pull from managed colony storages (06 §4.3). */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void btv$protect(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel server) {
            BlockPos above = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
            if (PlayerBlocks.blocksHopper(server, above)) {
                cir.setReturnValue(false);
            }
        }
    }
}
