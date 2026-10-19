package net.betterthanvillage.mixin.food;

import net.betterthanvillage.food.FisherTask;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager-owned bobber is a static prop: only the base entity tick runs (02 §2.3, 04 §3.5). */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin extends Projectile {
    protected FishingHookMixin(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void btv$villagerTick(CallbackInfo ci) {
        if (!(this.getOwner() instanceof Villager v)) {
            return;
        }
        this.baseTick();
        if (!this.level().isClientSide && (!v.isAlive() || v.isSleeping() || v.getTradingPlayer() != null
                || !v.getMainHandItem().is(Items.FISHING_ROD) || this.distanceToSqr(v) > 64
                || !FisherTask.isLiveBobber((FishingHook) (Object) this))) {
            this.discard();
        }
        this.setDeltaMovement(Vec3.ZERO);
        ci.cancel();
    }
}
