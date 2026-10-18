package net.betterthanvillage.ai.nav;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Doors, gates and trapdoors for colony villagers (03 §5.4) (stub, not implemented yet). */
public final class DoorHandler {
    private DoorHandler() {
    }

    /** Called every tick for every villager. */
    public static void tick(ServerLevel level, Villager v) {
    }

    /** Called every world tick; closes recorded blocks every 5 ticks. */
    public static void processRecorded(ServerLevel level) {
    }

    public static void clearCaches() {
    }
}
