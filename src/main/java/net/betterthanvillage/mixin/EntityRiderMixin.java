package net.betterthanvillage.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A villager on a horse sits in the saddle as a player does (the villager type has no vehicle attachment). */
@Mixin(Entity.class)
public abstract class EntityRiderMixin {
    @Inject(method = "getVehicleAttachmentPoint", at = @At("RETURN"), cancellable = true)
    private void btv$sitInSaddle(Entity vehicle, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Villager && vehicle instanceof AbstractHorse) cir.setReturnValue(Player.DEFAULT_VEHICLE_ATTACHMENT);
    }
}
