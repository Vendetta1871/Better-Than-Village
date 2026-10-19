package net.betterthanvillage.mixin.client;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Accepts a villager as the owner of a spawned bobber (vanilla only accepts players, 02 §2.3). */
@Mixin(FishingHook.class)
public abstract class FishingHookClientMixin {
    @Inject(method = "recreateFromPacket", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/FishingHook;getPlayerOwner()Lnet/minecraft/world/entity/player/Player;"), cancellable = true)
    private void btv$villagerOwner(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        FishingHook self = (FishingHook) (Object) this;
        if (self.level().getEntity(packet.getData()) instanceof Villager villager) {
            self.setOwner(villager);
            ci.cancel();
        }
    }
}
