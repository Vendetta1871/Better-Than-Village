package net.betterthanvillage.construction;

import net.betterthanvillage.colony.ColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Terrain predicates shared by plots, roads and walls (05 §2.3, §2.5, §2.9, §3.1). */
public final class Terrain {
    private Terrain() {
    }

    /** Natural terrain: #dirt, #sand, #base_stone_overworld, #terracotta, sandstone, red sandstone, gravel, snow block, tuff. */
    public static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.TUFF);
    }

    /** Road block: dirt path or smooth sandstone. */
    public static boolean isRoad(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.SMOOTH_SANDSTONE);
    }

    /** Builder-clearable block (05 §2.5). */
    public static boolean clearable(BlockState s) {
        return s.canBeReplaced() || s.getBlock() instanceof CropBlock || s.getBlock() instanceof StemBlock
                || s.getBlock() instanceof AttachedStemBlock || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.FARMLAND)
                || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || natural(s);
    }

    /** A #logs block that is not player-placed and has leaves within (-3,-1,-3)..(+3,+8,+3). */
    public static boolean treeLog(ServerLevel level, BlockPos pos, BlockState s) {
        if (!s.is(BlockTags.LOGS) || ColonyManager.data(level).isPlayerBlock(pos)) {
            return false;
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, 8, 3))) {
            if (level.getBlockState(p).is(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    /** Ground position for roads: top(MOTION_BLOCKING_NO_LEAVES) - 1, descended up to 6 times while a built wall block. */
    public static BlockPos roadGround(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 6 && Fortifications.isWallBlock(level, p); i++) {
            p.move(0, -1, 0);
        }
        return p.immutable();
    }

    /** Surface standing cell: top(MOTION_BLOCKING_NO_LEAVES), one lower if the block below is thin snow. */
    public static BlockPos surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(p.below());
        if (below.is(Blocks.SNOW) && below.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) < 8) {
            return p.below();
        }
        return p;
    }

    public static boolean loaded(ServerLevel level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    /** All chunks covering the XZ rectangle are loaded. */
    public static boolean loaded(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }
}
