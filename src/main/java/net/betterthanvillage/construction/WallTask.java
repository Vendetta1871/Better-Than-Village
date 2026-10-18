package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Builder wall task (05 §4.5) (stub, not implemented yet). */
public final class WallTask extends ColonyTask {
    public WallTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
