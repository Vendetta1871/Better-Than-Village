package net.betterthanvillage.mixin.defense;

import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.defense.ColonyDamage;
import net.betterthanvillage.defense.ThreatManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iron golem attacks: no friendly fire on colony villagers, golem_attack source and slowness against players (02 §2.3). */
@Mixin(IronGolem.class)
public abstract class IronGolemMixin {
    @Unique
    private Entity btv$attackTarget;

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void btv$friendlyFire(Entity target, CallbackInfoReturnable<Boolean> cir) {
        IronGolem self = (IronGolem) (Object) this;
        btv$attackTarget = target;
        if (target instanceof Villager && self.level() instanceof ServerLevel level && ThreatManager.sameColony(level, self, target)) {
            self.setTarget(null);
            btv$attackTarget = null;
            cir.setReturnValue(false);
        }
    }

    @ModifyArg(method = "doHurtTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), index = 0)
    private DamageSource btv$golemSource(DamageSource original) {
        IronGolem self = (IronGolem) (Object) this;
        if (btv$attackTarget != null && self.level() instanceof ServerLevel level) {
            return ColonyDamage.source(level, self, btv$attackTarget, original);
        }
        return original;
    }

    @Inject(method = "doHurtTarget", at = @At("RETURN"))
    private void btv$slowPlayer(Entity target, CallbackInfoReturnable<Boolean> cir) {
        IronGolem self = (IronGolem) (Object) this;
        if (cir.getReturnValueZ() && target instanceof Player player && self.level() instanceof ServerLevel level
                && ColonyManager.colonyAt(level, self.blockPosition()) != null) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0), self);
        }
        btv$attackTarget = null;
    }
}
