package net.betterthanvillage.mixin.society;

import net.betterthanvillage.society.PlayerBlocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Successful player placements: land notices, player-block registry, chest split, storage release (06 §3, §4). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void btv$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue().consumesAction() && context.getPlayer() != null && context.getLevel() instanceof ServerLevel level) {
            PlayerBlocks.onPlaced(level, context.getPlayer(), context.getClickedPos());
        }
    }
}
