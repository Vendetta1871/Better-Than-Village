package net.betterthanvillage.mixin;

import net.betterthanvillage.colony.Census;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla villager breeding is blocked inside a colony whose population gate reports a reason. */
@Mixin(VillagerMakeLove.class)
public abstract class VillagerMakeLoveMixin {
    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;)Z", at = @At("HEAD"), cancellable = true)
    private void btv$gate(ServerLevel level, Villager villager, CallbackInfoReturnable<Boolean> cir) {
        if (!Census.growthAllowed(level, villager)) {
            cir.setReturnValue(false);
        }
    }
}
