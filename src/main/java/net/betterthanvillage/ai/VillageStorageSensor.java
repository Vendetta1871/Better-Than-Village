package net.betterthanvillage.ai;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.npc.Villager;

import java.util.Set;

/** Remembers the nearest loaded colony storage as storage_chest_pos (period 100 ticks). */
public final class VillageStorageSensor extends Sensor<Villager> {
    public VillageStorageSensor() {
        super(100);
    }

    @Override
    protected void doTick(ServerLevel level, Villager villager) {
        Colony colony = ColonyManager.colonyOf(level, villager);
        BlockPos best = null;
        if (colony != null) {
            double bestD = Double.MAX_VALUE;
            for (BlockPos pos : colony.storages) {
                if (!level.isLoaded(pos)) continue;
                double d = pos.distToCenterSqr(villager.position());
                if (d < bestD) {
                    bestD = d;
                    best = pos;
                }
            }
        }
        if (best != null) {
            villager.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), best);
        } else {
            villager.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
        }
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(ModRegistries.STORAGE_CHEST_POS.get());
    }
}
