package net.betterthanvillage.ai;

import com.google.common.collect.ImmutableMap;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.npc.Villager;

/**
 * Base class for colony work tasks. Subclasses self-filter by role in {@link #shouldRun}.
 * Every subclass lives under the mod package, so it counts as a "mod task".
 */
public abstract class ColonyTask extends Behavior<Villager> {
    protected ColonyTask(int maxDuration) {
        super(ImmutableMap.of(), maxDuration);
    }

    protected ColonyTask(int minDuration, int maxDuration) {
        super(ImmutableMap.of(), minDuration, maxDuration);
    }

    /** The villager's own colony (membership-aware; null if it belongs to none). */
    protected static Colony colony(ServerLevel level, Villager v) {
        return ColonyManager.colonyOf(level, v);
    }

    protected static ColonyRole role(Colony colony, Villager v) {
        return colony == null ? ColonyRole.CITIZEN : colony.roleOf(v.getUUID());
    }

    protected abstract boolean shouldRun(ServerLevel level, Villager v, Colony colony);

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager v) {
        Colony colony = colony(level, v);
        return colony != null && !v.isBaby() && shouldRun(level, v, colony);
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return super.timedOut(gameTime);
    }
}
