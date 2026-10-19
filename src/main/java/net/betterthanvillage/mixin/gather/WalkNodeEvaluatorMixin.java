package net.betterthanvillage.mixin.gather;

import net.betterthanvillage.ai.nav.DoorHandler;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.construction.Fortifications;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fence gates are walkable for colony villagers (except the livestock pen gate), and wall gates for
 * villagers and iron golems (02 §2.5; Yarn LandPathNodeMaker.getDefaultNodeType).
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {
    @Inject(method = "getPathType(Lnet/minecraft/world/level/pathfinder/PathfindingContext;III)Lnet/minecraft/world/level/pathfinder/PathType;",
            at = @At("HEAD"), cancellable = true)
    private void btv$gateNodeType(PathfindingContext context, int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
        if (this.mob == null || !(this.mob.level() instanceof ServerLevel level)) return;
        boolean villager = this.mob instanceof Villager;
        if (!villager && !(this.mob instanceof IronGolem)) return;
        BlockPos p = new BlockPos(x, y, z);
        if (!(context.getBlockState(p).getBlock() instanceof FenceGateBlock)) return;
        if (Fortifications.isWallGate(level, p)) {
            cir.setReturnValue(PathType.WALKABLE);
            return;
        }
        if (!(this.mob instanceof Villager v) || ColonyManager.colonyOf(level, v) == null) return;
        // Player pens and yards stay closed to villagers.
        if (DoorHandler.playerGate(level, p)) return;
        Colony at = ColonyManager.colonyAt(level, p);
        if (at == null || at.livestockPen == null || !at.livestockPen.north(5).equals(p)) {
            cir.setReturnValue(PathType.WALKABLE);
        }
    }
}
