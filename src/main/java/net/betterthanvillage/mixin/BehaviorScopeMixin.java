package net.betterthanvillage.mixin;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.economy.StockScope;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Colony tasks doing public work use the treasury instead of the worker's household stock (StockScope). */
@Mixin(Behavior.class)
public abstract class BehaviorScopeMixin {
    @Unique
    private int btv$depth;
    @Unique
    private boolean btv$scoped;
    @Unique
    private Object btv$previous;

    @Unique
    private void btv$enter(LivingEntity entity) {
        if (btv$depth++ == 0 && (Object) this instanceof ColonyTask task && entity instanceof Villager v && task.publicWork(v)) {
            btv$previous = StockScope.push(true, null);
            btv$scoped = true;
        }
    }

    @Unique
    private void btv$leave() {
        if (--btv$depth <= 0) {
            btv$depth = 0;
            if (btv$scoped) {
                btv$scoped = false;
                StockScope.restore(btv$previous);
                btv$previous = null;
            }
        }
    }

    @Inject(method = "tryStart", at = @At("HEAD"))
    private void btv$tryStartHead(ServerLevel level, LivingEntity entity, long time, CallbackInfoReturnable<Boolean> cir) {
        btv$enter(entity);
    }

    @Inject(method = "tryStart", at = @At("RETURN"))
    private void btv$tryStartReturn(ServerLevel level, LivingEntity entity, long time, CallbackInfoReturnable<Boolean> cir) {
        btv$leave();
    }

    @Inject(method = "tickOrStop", at = @At("HEAD"))
    private void btv$tickHead(ServerLevel level, LivingEntity entity, long time, CallbackInfo ci) {
        btv$enter(entity);
    }

    @Inject(method = "tickOrStop", at = @At("RETURN"))
    private void btv$tickReturn(ServerLevel level, LivingEntity entity, long time, CallbackInfo ci) {
        btv$leave();
    }

    @Inject(method = "doStop", at = @At("HEAD"))
    private void btv$stopHead(ServerLevel level, LivingEntity entity, long time, CallbackInfo ci) {
        btv$enter(entity);
    }

    @Inject(method = "doStop", at = @At("RETURN"))
    private void btv$stopReturn(ServerLevel level, LivingEntity entity, long time, CallbackInfo ci) {
        btv$leave();
    }
}
