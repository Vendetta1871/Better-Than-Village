package net.betterthanvillage.gather;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Damage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Shared block predicates and helpers of the gather subsystem (03 §2-§3). */
public final class GatherUtil {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GatherUtil() {
    }

    /** Block tag {@code dirt}, mangrove roots or muddy mangrove roots. */
    public static boolean isTreeSoil(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /** Natural terrain the miner may dig: dirt, sand, overworld base stone, terracotta, sandstones, gravel, snow block, tuff. */
    public static boolean naturalTerrain(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.TUFF);
    }

    /** Any block whose registry id path ends with {@code _ore}. */
    public static boolean isOre(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_ore");
    }

    public static String path(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    /** No fluid and an empty collision shape. */
    public static boolean passable(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** No fluid and a full solid top face. */
    public static boolean solidFloor(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }

    /** No living entity other than {@code except} in the cell box expanded by 0.35. */
    public static boolean freeOfLiving(ServerLevel level, BlockPos pos, Entity except) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(0.35), e -> e != except && e.isAlive()).isEmpty();
    }

    /** Teleports to the bottom centre of a cell: stop navigation, zero velocity, reset fall distance. */
    public static void teleport(LivingEntity e, BlockPos pos) {
        if (ColonyConfig.get().debug.log_teleports) {
            // The direct caller names the reason (pit escape, emergency, route shortcut, ...).
            String caller = StackWalker.getInstance().walk(fr -> fr.skip(1).limit(2)
                    .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                    .reduce((a, b) -> a + " <- " + b).orElse("?"));
            LOGGER.info("[btv teleport] {} {} -> {} via {}", e.getName().getString(), e.blockPosition().toShortString(), pos.toShortString(), caller);
        }
        if (e instanceof net.minecraft.world.entity.Mob mob) {
            mob.getNavigation().stop();
        }
        e.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        e.setDeltaMovement(Vec3.ZERO);
        e.resetFallDistance();
    }

    /** Squared horizontal distance between two block positions. */
    public static long hDist2(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Inside some colony structure's box expanded by mx/my/mz. */
    public static boolean inStructure(ServerLevel level, BlockPos pos, int mx, int my, int mz) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            for (ConstructionJob job : colony.jobs) {
                BoundingBox b = job.box();
                if (pos.getX() >= b.minX() - mx && pos.getX() <= b.maxX() + mx && pos.getZ() >= b.minZ() - mz && pos.getZ() <= b.maxZ() + mz
                        && pos.getY() >= b.minY() - my && pos.getY() <= b.maxY() + my) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Inside some colony structure's XZ bounds expanded by m, at or above the structure's minY - below. */
    public static boolean underStructure(ServerLevel level, BlockPos pos, int m, int below) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            for (ConstructionJob job : colony.jobs) {
                BoundingBox b = job.box();
                if (pos.getX() >= b.minX() - m && pos.getX() <= b.maxX() + m && pos.getZ() >= b.minZ() - m && pos.getZ() <= b.maxZ() + m
                        && pos.getY() >= b.minY() - below) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Road material (DIRT_PATH or SMOOTH_SANDSTONE). */
    public static boolean isRoadBlock(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.SMOOTH_SANDSTONE);
    }

    /**
     * Shared "free natural ground" check (saplings, 03 §2.6 / 04 §2.11): not near the pen, not in claimed land
     * or owned plots, not in a vanilla house piece, not near any colony's structures, wall ring or road points,
     * and no road block (all loaded) in the box (±1, ±2, ±1).
     */
    public static boolean freeNaturalGround(ServerLevel level, Colony colony, BlockPos pos) {
        if (colony.livestockPen != null && Math.abs(pos.getX() - colony.livestockPen.getX()) <= 6
                && Math.abs(pos.getZ() - colony.livestockPen.getZ()) <= 6) {
            return false;
        }
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos) || Protection.inPlayerOwnedJob(level, pos)) return false;
        if (Damage.isProtectedHousePiece(level, pos) || Damage.isProtectedHousePiece(level, pos.above())) return false;
        for (Colony c : data.colonies()) {
            if (nearStructure(c, pos, 3) || nearRoad(c, pos)) return false;
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -2, -1), pos.offset(1, 2, 1))) {
            if (!level.isLoaded(p) || isRoadBlock(level.getBlockState(p))) return false;
        }
        return true;
    }

    /** Near the planned wall ring (|dist - W| <= margin + 2) or within margin of a construction job box horizontally. */
    private static boolean nearStructure(Colony c, BlockPos pos, int margin) {
        int w = c.fortifications.radius;
        if (w > 0) {
            double d = Math.sqrt(hDist2(pos, c.core()));
            if (Math.abs(d - w) <= margin + 2) return true;
        }
        for (ConstructionJob job : c.jobs) {
            BoundingBox b = job.box();
            if (pos.getX() >= b.minX() - margin && pos.getX() <= b.maxX() + margin && pos.getZ() >= b.minZ() - margin && pos.getZ() <= b.maxZ() + margin) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearRoad(Colony c, BlockPos pos) {
        for (BlockPos r : c.roadQueue) {
            if (Math.abs(r.getX() - pos.getX()) <= 1 && Math.abs(r.getZ() - pos.getZ()) <= 1) return true;
        }
        return false;
    }
}
