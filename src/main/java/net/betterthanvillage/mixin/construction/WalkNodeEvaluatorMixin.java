package net.betterthanvillage.mixin.construction;

import net.betterthanvillage.construction.Fortifications;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wall gates (and, for colony villagers, other fence gates) are walkable (05 §4.6, 02 §2.5). */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {
    @Inject(method = "getPathType(Lnet/minecraft/world/level/pathfinder/PathfindingContext;III)Lnet/minecraft/world/level/pathfinder/PathType;", at = @At("HEAD"), cancellable = true)
    private void btv$gates(PathfindingContext context, int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
        Mob m = this.mob;
        if (m != null && Fortifications.gateWalkable(m, context, x, y, z)) {
            cir.setReturnValue(PathType.WALKABLE);
        }
    }
}
