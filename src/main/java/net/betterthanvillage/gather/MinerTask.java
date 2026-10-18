package net.betterthanvillage.gather;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Miner (03 §3) (stub, not implemented yet). */
public final class MinerTask extends ColonyTask {
    public MinerTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
