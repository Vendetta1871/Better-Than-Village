package net.betterthanvillage.ai;

import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

/** Suppresses vanilla walk-target following while the mod steers the villager. */
public final class MovementGate<E extends LivingEntity> implements BehaviorControl<E> {
    private final BehaviorControl<E> inner;

    public MovementGate(BehaviorControl<E> inner) {
        this.inner = inner;
    }

    @SuppressWarnings("unchecked")
    public static <E extends LivingEntity> BehaviorControl<E> wrap(BehaviorControl<? super E> inner) {
        return new MovementGate<>((BehaviorControl<E>) inner);
    }

    private static boolean blocked(ServerLevel level, LivingEntity entity) {
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        if (Nav.steering(villager, level.getGameTime())) {
            return true;
        }
        Activity a = villager.getBrain().getActiveNonCoreActivity().orElse(null);
        return a == ModRegistries.COLONY_WORK.get() || a == ModRegistries.COLONY_SHELTER.get() || a == ModRegistries.COLONY_TRAVEL.get();
    }

    @Override
    public Behavior.Status getStatus() {
        return inner.getStatus();
    }

    @Override
    public boolean tryStart(ServerLevel level, E entity, long time) {
        if (blocked(level, entity)) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            return false;
        }
        return inner.tryStart(level, entity, time);
    }

    @Override
    public void tickOrStop(ServerLevel level, E entity, long time) {
        if (blocked(level, entity)) {
            if (inner.getStatus() == Behavior.Status.RUNNING) {
                inner.doStop(level, entity, time);
            }
            return;
        }
        inner.tickOrStop(level, entity, time);
    }

    @Override
    public void doStop(ServerLevel level, E entity, long time) {
        inner.doStop(level, entity, time);
    }

    @Override
    public String debugString() {
        return inner.debugString();
    }

    public BehaviorControl<E> inner() {
        return inner;
    }
}
