package net.betterthanvillage.colony;

import net.betterthanvillage.economy.Households;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Incremental scan of the 33x33x33 cube around the bell for chests and barrels. */
public final class StorageScanner {
    public static final int SIZE = 33;
    public static final int TOTAL = SIZE * SIZE * SIZE;

    private StorageScanner() {
    }

    public static void start(Colony colony) {
        colony.scanPending.clear();
        colony.scanCursor = 0;
    }

    /** Scans up to budget positions; returns how many were consumed. */
    public static int advance(ServerLevel level, Colony colony, int budget) {
        int used = 0;
        BlockPos core = colony.core();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (used < budget && colony.scanCursor >= 0 && colony.scanCursor < TOTAL) {
            int i = colony.scanCursor++;
            used++;
            int dx = i % SIZE - 16;
            int dz = (i / SIZE) % SIZE - 16;
            int dy = i / (SIZE * SIZE) - 16;
            pos.set(core.getX() + dx, core.getY() + dy, core.getZ() + dz);
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && !Protection.isProtectedContainer(level, pos)
                    && !Households.isHomeChest(level, pos)) {
                colony.scanPending.add(pos.immutable());
            }
        }
        if (colony.scanCursor >= TOTAL) {
            colony.storages.clear();
            colony.storages.addAll(colony.scanPending);
            for (BlockPos managed : colony.managedStorages.keySet()) {
                if (!colony.storages.contains(managed)) {
                    colony.storages.add(managed);
                }
            }
            colony.scanPending.clear();
            colony.scanCursor = -1;
            colony.lastScanFinish = level.getGameTime();
            ColonyStorage.recount(level, colony);
        }
        return used;
    }
}
