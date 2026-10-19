package net.betterthanvillage.mixin.food;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.food.FarmTerritory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla farmer task restricted by the suitability predicate S(pos); disabled for colony farmers (02 §2.2). */
@Mixin(HarvestFarmland.class)
public abstract class HarvestFarmlandMixin {
    @Shadow
    private BlockPos aboveFarmlandPos;

    @Inject(method = "validPos", at = @At("HEAD"), cancellable = true)
    private void btv$validPos(BlockPos pos, ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (!FarmTerritory.vanillaFarmSuitable(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V", at = @At("HEAD"), cancellable = true)
    private void btv$tick(ServerLevel level, Villager v, long time, CallbackInfo ci) {
        Colony colony = ColonyManager.colonyAt(level, v.blockPosition());
        if (colony != null && colony.roleOf(v.getUUID()) == ColonyRole.FARMER) {
            ci.cancel();
        } else if (aboveFarmlandPos != null && !FarmTerritory.vanillaFarmSuitable(level, aboveFarmlandPos)) {
            ci.cancel();
        }
    }

    @Inject(method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)Z", at = @At("HEAD"), cancellable = true)
    private void btv$canStillUse(ServerLevel level, Villager v, long time, CallbackInfoReturnable<Boolean> cir) {
        if (aboveFarmlandPos != null && !FarmTerritory.vanillaFarmSuitable(level, aboveFarmlandPos)) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean btv$plant(ServerLevel level, BlockPos pos, BlockState state) {
        return FarmTerritory.vanillaFarmSuitable(level, pos) && level.setBlockAndUpdate(pos, state);
    }

    @Redirect(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
    private boolean btv$harvest(ServerLevel level, BlockPos pos, boolean drop, Entity entity) {
        return FarmTerritory.vanillaFarmSuitable(level, pos) && level.destroyBlock(pos, drop, entity);
    }
}
