package net.betterthanvillage.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;
import net.betterthanvillage.ai.ColonyGate;
import net.betterthanvillage.ai.MovementGate;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

/** Wraps vanilla core movement/job-site tasks and every vanilla work task in the colony gates. */
@Mixin(VillagerGoalPackages.class)
public abstract class VillagerGoalPackagesMixin {
    @Inject(method = "getCorePackage", at = @At("RETURN"), cancellable = true)
    private static void btv$core(VillagerProfession profession, float speed,
                                 CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir) {
        ImmutableList.Builder<Pair<Integer, ? extends BehaviorControl<? super Villager>>> out = ImmutableList.builder();
        for (Pair<Integer, ? extends BehaviorControl<? super Villager>> pair : cir.getReturnValue()) {
            BehaviorControl<? super Villager> task = pair.getSecond();
            if (task instanceof MoveToTargetSink) {
                out.add(Pair.of(pair.getFirst(), MovementGate.<Villager>wrap(task)));
            } else if (task instanceof GoToPotentialJobSite) {
                out.add(Pair.of(pair.getFirst(), ColonyGate.<Villager>wrap(task, false)));
            } else {
                out.add(pair);
            }
        }
        cir.setReturnValue(out.build());
    }

    @Inject(method = "getWorkPackage", at = @At("RETURN"), cancellable = true)
    private static void btv$work(VillagerProfession profession, float speed,
                                 CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir) {
        ImmutableList.Builder<Pair<Integer, ? extends BehaviorControl<? super Villager>>> out = ImmutableList.builder();
        for (Pair<Integer, ? extends BehaviorControl<? super Villager>> pair : cir.getReturnValue()) {
            out.add(Pair.of(pair.getFirst(), ColonyGate.<Villager>wrap(pair.getSecond(), false)));
        }
        cir.setReturnValue(out.build());
    }

    @WrapOperation(method = "getCorePackage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/InteractWithDoor;create()Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<LivingEntity> btv$doors(Operation<BehaviorControl<LivingEntity>> original) {
        return ColonyGate.wrap(original.call(), true);
    }

    @WrapOperation(method = "getCorePackage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/PoiCompetitorScan;create()Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<Villager> btv$competition(Operation<BehaviorControl<Villager>> original) {
        return ColonyGate.wrap(original.call(), false);
    }

    @WrapOperation(method = "getCorePackage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/YieldJobSite;create(F)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<Villager> btv$yield(float speed, Operation<BehaviorControl<Villager>> original) {
        return ColonyGate.wrap(original.call(speed), false);
    }

    @WrapOperation(method = "getCorePackage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/AssignProfessionFromJobSite;create()Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<Villager> btv$assign(Operation<BehaviorControl<Villager>> original) {
        return ColonyGate.wrap(original.call(), false);
    }

    @WrapOperation(method = "getCorePackage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/ResetProfession;create()Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<Villager> btv$reset(Operation<BehaviorControl<Villager>> original) {
        return ColonyGate.wrap(original.call(), false);
    }

    @WrapOperation(method = "getCorePackage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/AcquirePoi;create(Ljava/util/function/Predicate;Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;ZLjava/util/Optional;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<PathfinderMob> btv$jobSitePoi(Predicate<Holder<PoiType>> poi, MemoryModuleType<GlobalPos> existing, MemoryModuleType<GlobalPos> acquiring,
                                                                boolean onlyIfAdult, Optional<Byte> event, Operation<BehaviorControl<PathfinderMob>> original) {
        BehaviorControl<PathfinderMob> task = original.call(poi, existing, acquiring, onlyIfAdult, event);
        boolean jobSite = existing == MemoryModuleType.JOB_SITE || existing == MemoryModuleType.POTENTIAL_JOB_SITE
                || acquiring == MemoryModuleType.JOB_SITE || acquiring == MemoryModuleType.POTENTIAL_JOB_SITE;
        return jobSite ? ColonyGate.wrap(task, false) : task;
    }
}
