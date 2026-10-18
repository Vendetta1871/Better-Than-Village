package net.betterthanvillage.api;

import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/** Public read-only view of a colony. */
public interface VillageCapability {
    UUID id();

    BlockPos core();

    int radius();

    /** Members counted at the last census (babies included). */
    int population();

    /** HOME POIs counted at the last census. */
    int beds();

    int cachedFood();

    int cachedLogs();

    int cachedStone();

    ColonyRole roleOf(UUID villager);
}
