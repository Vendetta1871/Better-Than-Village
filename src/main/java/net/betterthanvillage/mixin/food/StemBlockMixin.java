package net.betterthanvillage.mixin.food;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.food.FarmTerritory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Colony stems only grow fruit onto colony farm ground (02 §2.5). */
@Mixin(StemBlock.class)
public abstract class StemBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void btv$randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        Colony colony = colonyStem(level, pos);
        if (colony == null) return;
        if (!FarmTerritory.territoryOk(level, colony, pos.below()) || allowed(level, colony, pos).isEmpty()) {
            ci.cancel();
        }
    }

    @Redirect(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/Direction$Plane;getRandomDirection(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/core/Direction;"))
    private Direction btv$fruitDirection(Direction.Plane plane, RandomSource random, BlockState state, ServerLevel level, BlockPos pos, RandomSource r) {
        Colony colony = colonyStem(level, pos);
        if (colony != null) {
            List<Direction> dirs = allowed(level, colony, pos);
            if (!dirs.isEmpty()) return dirs.get(random.nextInt(dirs.size()));
        }
        return plane.getRandomDirection(random);
    }

    private static Colony colonyStem(ServerLevel level, BlockPos pos) {
        if (ColonyManager.data(level).isPlayerBlock(pos)) return null;
        return ColonyManager.colonyAt(level, pos);
    }

    private static List<Direction> allowed(ServerLevel level, Colony colony, BlockPos pos) {
        List<Direction> out = new ArrayList<>(4);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (FarmTerritory.territoryOk(level, colony, pos.below().relative(d))) out.add(d);
        }
        return out;
    }
}
