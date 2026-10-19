package net.betterthanvillage.mixin.society;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.society.PlayerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Player block breaking: registry cleanup, griefing penalty and repair queue (06 §4.2). */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Unique
    private PlayerBlocks.BreakState btv$break;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void btv$head(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        btv$break = PlayerBlocks.beforeBreak(level, player, pos);
    }

    @WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;removeBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Z"))
    private boolean btv$suppressed(ServerPlayerGameMode self, BlockPos pos, BlockState state, boolean canHarvest, Operation<Boolean> original) {
        return SafeEditScope.call(() -> original.call(self, pos, state, canHarvest));
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void btv$return(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        PlayerBlocks.BreakState state = btv$break;
        btv$break = null;
        PlayerBlocks.afterBreak(level, player, pos, state, cir.getReturnValueZ());
    }
}
