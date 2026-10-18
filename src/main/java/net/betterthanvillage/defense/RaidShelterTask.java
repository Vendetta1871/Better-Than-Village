package net.betterthanvillage.defense;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Civilians hide in enclosed shelters during raids/fights (02 §6) (stub, not implemented yet). */
public final class RaidShelterTask extends ColonyTask {
    public RaidShelterTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return false;
    }
}
