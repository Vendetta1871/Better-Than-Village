package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.WeakHashMap;

/** Runtime terrain survey per colony: forest/water/soil samples, animals, stock deltas. */
public final class TerrainSurvey {
    private static final Map<Colony, State> STATES = new WeakHashMap<>();

    public static final class State {
        // published results
        public boolean published;
        public boolean complete;
        public int forest;
        public int water;
        public int soil;
        // in-progress sampling
        int cursor;
        boolean allLoaded = true;
        int curForest, curWater, curSoil;
        // stock deltas over 600-tick windows
        long lastSnapshotTick = Long.MIN_VALUE;
        int lastFood, lastLogs, lastStone;
        public int foodDelta, logDelta, stoneDelta;
        // animals
        long lastAnimalTick = Long.MIN_VALUE;
        public int animals;
    }

    private TerrainSurvey() {
    }

    public static State state(Colony colony) {
        return STATES.get(colony);
    }

    public static void clear() {
        STATES.clear();
    }

    /** One survey step per census. */
    public static void step(ServerLevel level, Colony colony) {
        State s = STATES.computeIfAbsent(colony, c -> new State());
        long now = level.getGameTime();
        if (s.lastSnapshotTick == Long.MIN_VALUE) {
            s.lastSnapshotTick = now;
            s.lastFood = colony.cachedFood;
            s.lastLogs = colony.cachedLogs;
            s.lastStone = colony.cachedStone;
        } else if (now - s.lastSnapshotTick >= 600) {
            s.foodDelta = colony.cachedFood - s.lastFood;
            s.logDelta = colony.cachedLogs - s.lastLogs;
            s.stoneDelta = colony.cachedStone - s.lastStone;
            s.lastFood = colony.cachedFood;
            s.lastLogs = colony.cachedLogs;
            s.lastStone = colony.cachedStone;
            s.lastSnapshotTick = now;
        }
        BlockPos core = colony.core();
        int span = Math.min(96, colony.radius() - 8);
        int n = span / 4 + 1;
        int total = n * n;
        for (int k = 0; k < 64 && s.cursor < total; k++, s.cursor++) {
            int ix = s.cursor % n;
            int iz = s.cursor / n;
            int x = core.getX() - span + 8 * ix;
            int z = core.getZ() - span + 8 * iz;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                s.allLoaded = false;
                continue;
            }
            BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
            BlockState st = level.getBlockState(p);
            if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) {
                s.curForest++;
            }
            if (st.getFluidState().is(Fluids.WATER) || st.is(Blocks.WATER)) {
                long dx = x - core.getX(), dz = z - core.getZ();
                if (dx * dx + dz * dz <= 2304) {
                    s.curWater++;
                }
            }
            if (st.is(BlockTags.DIRT) || st.is(Blocks.FARMLAND)) {
                s.curSoil++;
            }
        }
        if (s.cursor >= total) {
            s.published = true;
            s.complete = s.allLoaded;
            s.forest = s.curForest;
            s.water = s.curWater;
            s.soil = s.curSoil;
            s.cursor = 0;
            s.allLoaded = true;
            s.curForest = s.curWater = s.curSoil = 0;
        }
        if (s.lastAnimalTick == Long.MIN_VALUE || now - s.lastAnimalTick >= 600) {
            s.lastAnimalTick = now;
            int r = colony.radius();
            AABB box = new AABB(core.getX() - r, level.getMinBuildHeight(), core.getZ() - r, core.getX() + r + 1, level.getMaxBuildHeight(), core.getZ() + r + 1);
            s.animals = level.getEntitiesOfClass(Animal.class, box, Animal::isAlive).size();
        }
    }
}
