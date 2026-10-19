package net.betterthanvillage.mixin.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/** Path search with an explicit follow range (05 §2.3/§2.8: follow range 128). */
@Mixin(PathNavigation.class)
public interface PathNavigationInvoker {
    @Invoker("createPath")
    Path btv$createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange);

    /** createPath stores the probe's target for recomputePath(); probes restore the previous values. */
    @Accessor("targetPos")
    BlockPos btv$targetPos();

    @Accessor("targetPos")
    void btv$setTargetPos(BlockPos pos);

    @Accessor("reachRange")
    int btv$reachRange();

    @Accessor("reachRange")
    void btv$setReachRange(int range);
}
