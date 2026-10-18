package net.betterthanvillage.ai.work;

import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.WeakHashMap;

/** Simulated block breaking with crack overlay; one instance per task. */
public final class BlockWorkTimer {
    private static final Map<LivingEntity, Long> BUSY_UNTIL = new WeakHashMap<>();

    private BlockPos target;
    private BlockState state;
    private long start;
    private int duration = 1;
    private LivingEntity actor;

    /** Break duration = clamp(ceil(max(0,hardness) * 30 / max(1, tool speed)), minimum_break_ticks, 200). */
    public static int breakTicks(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = Math.max(0, state.getDestroySpeed(level, pos));
        float speed = Math.max(1, tool.getDestroySpeed(state));
        int ticks = (int) Math.ceil(hardness * 30 / speed);
        return Math.max(ColonyConfig.get().work.minimum_break_ticks, Math.min(200, ticks));
    }

    public static boolean isBusyBreaking(LivingEntity entity, long now) {
        Long until = BUSY_UNTIL.get(entity);
        return until != null && now <= until;
    }

    /** Ticks breaking progress; returns true once elapsed >= duration. */
    public boolean tick(ServerLevel level, LivingEntity actor, BlockPos pos, boolean visuals) {
        long now = level.getGameTime();
        BlockState current = level.getBlockState(pos);
        if (target == null || !target.equals(pos) || state != current) {
            cancel(level);
            this.target = pos.immutable();
            this.state = current;
            this.start = now;
            this.duration = Math.max(1, breakTicks(level, pos, current, actor.getMainHandItem()));
        }
        this.actor = actor;
        BUSY_UNTIL.put(actor, now + 20);
        long elapsed = now - start;
        if (elapsed % 6 == 0) {
            actor.swing(InteractionHand.MAIN_HAND);
        }
        if (visuals) {
            level.destroyBlockProgress(actor.getId(), pos, (int) Math.min(9, elapsed * 10 / duration));
        }
        if (elapsed >= duration) {
            level.destroyBlockProgress(actor.getId(), pos, -1);
            BUSY_UNTIL.put(actor, now + 20);
            return true;
        }
        return false;
    }

    /** Timed action without block state tracking (placement, farming): returns true when done. */
    public boolean tickAction(ServerLevel level, LivingEntity actor, BlockPos pos, int ticks) {
        long now = level.getGameTime();
        if (target == null || !target.equals(pos) || state != null) {
            cancel(level);
            this.target = pos.immutable();
            this.state = null;
            this.start = now;
            this.duration = Math.max(1, ticks);
        }
        this.actor = actor;
        if ((now - start) % 6 == 0) {
            actor.swing(InteractionHand.MAIN_HAND);
        }
        return now - start >= duration;
    }

    public void cancel(ServerLevel level) {
        if (target != null && actor != null && state != null) {
            level.destroyBlockProgress(actor.getId(), target, -1);
        }
        if (actor != null) {
            BUSY_UNTIL.remove(actor);
        }
        target = null;
        state = null;
    }

    public void reset() {
        target = null;
        state = null;
    }

    public BlockPos target() {
        return target;
    }
}
