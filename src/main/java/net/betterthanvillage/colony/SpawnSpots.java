package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Spawn spot search around the core and villager creation helpers. */
public final class SpawnSpots {
    private SpawnSpots() {
    }

    /** Rings r = 2..12, perimeter walk starting at (seed*7) mod 8r; first valid surface cell. */
    public static BlockPos find(ServerLevel level, Colony colony, long seed) {
        BlockPos core = colony.core();
        for (int r = 2; r <= 12; r++) {
            int n = 8 * r;
            int start = (int) Math.floorMod(seed * 7, (long) n);
            for (int k = 0; k < n; k++) {
                int idx = (start + k) % n;
                int[] off = perimeter(r, idx);
                int x = core.getX() + off[0];
                int z = core.getZ() + off[1];
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (valid(level, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    /** Perimeter order: north edge W->E, east edge N->S, south edge E->W, west edge S->N. */
    static int[] perimeter(int r, int idx) {
        int side = 2 * r;
        if (idx < side) return new int[]{-r + idx, -r};
        idx -= side;
        if (idx < side) return new int[]{r, -r + idx};
        idx -= side;
        if (idx < side) return new int[]{r - idx, r};
        idx -= side;
        return new int[]{-r, r - idx};
    }

    private static boolean valid(ServerLevel level, BlockPos pos) {
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) return false;
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        if (ColonyManager.data(level).inLand(pos) || Protection.inPlayerOwnedJob(level, pos)) return false;
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(0.2)).isEmpty();
    }

    /** Creates (but does not add) a persistent villager at the spot. */
    public static Villager createVillager(ServerLevel level, BlockPos pos, MobSpawnType reason) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) {
            return null;
        }
        villager.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360f, 0);
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), reason, null);
        villager.setPersistenceRequired();
        return villager;
    }

    /** Spawns a new adult villager at a spawn spot; returns null if no spot. */
    public static Villager spawnAdult(ServerLevel level, Colony colony, long seed) {
        BlockPos pos = find(level, colony, seed);
        if (pos == null) {
            return null;
        }
        Villager villager = createVillager(level, pos, MobSpawnType.MOB_SUMMONED);
        if (villager == null) {
            return null;
        }
        villager.setAge(0);
        return level.addFreshEntity(villager) ? villager : null;
    }
}
