package net.betterthanvillage.mixin;

import com.google.common.collect.ImmutableList;
import net.betterthanvillage.ai.ColonyBrain;
import net.betterthanvillage.ai.VillagerController;
import net.betterthanvillage.economy.StockScope;
import net.betterthanvillage.registry.ModRegistries;
import net.betterthanvillage.society.Trading;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Main villager integration: brain profile, colony activities, per-tick controller, interaction and trades. */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Shadow
    @Final
    private static ImmutableList<MemoryModuleType<?>> MEMORY_TYPES;

    @Shadow
    @Final
    private static ImmutableList<SensorType<? extends Sensor<? super Villager>>> SENSOR_TYPES;

    @Inject(method = "brainProvider", at = @At("HEAD"), cancellable = true)
    private void btv$brainProvider(CallbackInfoReturnable<Brain.Provider<Villager>> cir) {
        ImmutableList<MemoryModuleType<?>> memories = ImmutableList.<MemoryModuleType<?>>builder()
                .addAll(MEMORY_TYPES).addAll(ModRegistries.memories()).build();
        ImmutableList<SensorType<? extends Sensor<? super Villager>>> sensors = ImmutableList.<SensorType<? extends Sensor<? super Villager>>>builder()
                .addAll(SENSOR_TYPES).add(ModRegistries.VILLAGE_STORAGE.get()).build();
        cir.setReturnValue(Brain.provider(memories, sensors));
    }

    @Inject(method = "registerBrainGoals", at = @At("TAIL"))
    private void btv$registerBrainGoals(Brain<Villager> brain, CallbackInfo ci) {
        ColonyBrain.install(brain);
    }

    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void btv$preTick(CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            StockScope.enterVillager(self);
            VillagerController.preTick(level, self);
        }
    }

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void btv$tick(CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            try {
                VillagerController.tick(level, self);
            } finally {
                StockScope.exit();
            }
        }
    }

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void btv$mobInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        Villager self = (Villager) (Object) this;
        if (hand != InteractionHand.MAIN_HAND || self.isBaby() || !(self.level() instanceof ServerLevel level)) {
            return;
        }
        // Routed by membership (Trading.interact); non-members only lose stale colony-built offers.
        InteractionResult result = Trading.interact(level, self, player, hand);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    /** Vanilla golem summoning is suppressed while the village has its golem (docs_v2/07-warfare.md §Iron golems). */
    @Inject(method = "spawnGolemIfNeeded", at = @At("HEAD"), cancellable = true)
    private void btv$golemCap(ServerLevel level, long time, int needed, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        var colony = net.betterthanvillage.colony.ColonyManager.colonyOf(level, self);
        if (colony != null && net.betterthanvillage.defense.GolemManager.villageGolems(level, colony) >= net.betterthanvillage.config.ColonyConfig.get().warfare.golem_cap) {
            ci.cancel();
        }
    }

    @Inject(method = "rewardTradeXp", at = @At("TAIL"))
    private void btv$afterUsing(MerchantOffer offer, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            Trading.afterUsing(level, self, offer);
        }
    }
}
