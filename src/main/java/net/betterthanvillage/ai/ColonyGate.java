package net.betterthanvillage.ai;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.npc.Villager;

/**
 * Wraps a vanilla villager task so it never runs for founder-party members or for colony members
 * with a role (with flag = true: for any villager inside a colony).
 */
public final class ColonyGate<E extends LivingEntity> implements BehaviorControl<E> {
    private final BehaviorControl<E> inner;
    private final boolean anyMember;

    public ColonyGate(BehaviorControl<E> inner, boolean anyMember) {
        this.inner = inner;
        this.anyMember = anyMember;
    }

    @SuppressWarnings("unchecked")
    public static <E extends LivingEntity> BehaviorControl<E> wrap(BehaviorControl<? super E> inner, boolean anyMember) {
        return new ColonyGate<>((BehaviorControl<E>) inner, anyMember);
    }

    public static boolean blocked(ServerLevel level, LivingEntity entity, boolean anyMember) {
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        if (ColonyManager.foundingPartyColony(level, villager.getUUID()) != null) {
            return true;
        }
        Colony colony = ColonyManager.colonyAt(level, villager.blockPosition());
        return colony != null && (anyMember || colony.roleOf(villager.getUUID()) != ColonyRole.CITIZEN);
    }

    @Override
    public Behavior.Status getStatus() {
        return inner.getStatus();
    }

    @Override
    public boolean tryStart(ServerLevel level, E entity, long time) {
        if (blocked(level, entity, anyMember)) {
            return false;
        }
        return inner.tryStart(level, entity, time);
    }

    @Override
    public void tickOrStop(ServerLevel level, E entity, long time) {
        if (blocked(level, entity, anyMember)) {
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
