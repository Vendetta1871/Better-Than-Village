package net.betterthanvillage.defense;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Guard equipment and golem duty (02 §3.4) (stub, not implemented yet). */
public final class GuardEquipmentTask extends ColonyTask {
    public GuardEquipmentTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
