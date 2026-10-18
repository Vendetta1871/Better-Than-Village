package net.betterthanvillage.gather;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Lumberjack (03 §2) (stub, not implemented yet). */
public final class LumberjackTask extends ColonyTask {
    public LumberjackTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
