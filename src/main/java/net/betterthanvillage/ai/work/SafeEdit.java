package net.betterthanvillage.ai.work;

import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Villager block edits: refused when unsafe or when the terrain is protected. */
public final class SafeEdit {
    private SafeEdit() {
    }

    /** Safe break without drops; credits "mining/clearing" on success. */
    public static boolean breakBlock(ServerLevel level, LivingEntity actor, BlockPos pos) {
        if (pos.equals(actor.blockPosition().below())) return false;
        if (level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) return false;
        AABB above = new AABB(pos.above()).inflate(0.02);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, above)) {
            if (e.getBlockPosBelowThatAffectsMyMovement().equals(pos) || e.blockPosition().below().equals(pos)) return false;
        }
        if (!Protection.terrainModifiable(level, pos)) return false;
        boolean ok = SafeEditScope.call(() -> level.destroyBlock(pos, false, actor));
        if (ok && actor instanceof Villager v) {
            WorkTracker.credit(v, WorkTracker.STATUS_MINING, 1);
        }
        return ok;
    }

    /** Safe placement: refused if the new collision would intersect a living entity. */
    public static boolean place(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            AABB box = new AABB(pos).inflate(0, 2, 0);
            AABB placed = shape.bounds().move(pos);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
                AABB eb = e.isSleeping() ? new AABB(e.getX() - 0.35, e.getY(), e.getZ() - 0.35, e.getX() + 0.35, e.getY() + 2, e.getZ() + 0.35) : e.getBoundingBox();
                if (eb.intersects(placed)) return false;
            }
        }
        if (!Protection.terrainModifiable(level, pos)) return false;
        return SafeEditScope.call(() -> level.setBlock(pos, state, flags));
    }

    /** True if no entity other than the given one stands in the block. */
    public static boolean free(ServerLevel level, BlockPos pos, Entity except) {
        return level.getEntities(except, new AABB(pos), e -> e instanceof LivingEntity).isEmpty();
    }
}
