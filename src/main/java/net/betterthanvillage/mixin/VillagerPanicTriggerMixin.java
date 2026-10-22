package net.betterthanvillage.mixin;

import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Warriors and soldiers on campaign do not panic when hurt or near a hostile (docs_v2/07-warfare.md): panic would
 * switch their brain away from the fight, and they would die one by one.
 */
@Mixin(VillagerPanicTrigger.class)
public abstract class VillagerPanicTriggerMixin {
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V", at = @At("HEAD"), cancellable = true)
    private void btv$noPanic(ServerLevel level, Villager v, long time, CallbackInfo ci) {
        if (net.betterthanvillage.warfare.Campaigns.isSoldier(level, v) || ColonyManager.roleOf(level, v) == ColonyRole.WARRIOR) {
            ci.cancel();
        }
    }
}
