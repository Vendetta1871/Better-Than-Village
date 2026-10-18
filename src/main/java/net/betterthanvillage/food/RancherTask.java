package net.betterthanvillage.food;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Rancher / animal herder (04 §4) (stub, not implemented yet). */
public final class RancherTask extends ColonyTask {
    public RancherTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
