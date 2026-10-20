package net.betterthanvillage.gather;

import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Quarry yield (docs_v2/02-professions.md §Miner): breaking ordinary stone sometimes yields an ore, by depth and
 * biome, so a miner need not strip every vein near the village (deliberate simplification).
 */
public final class Quarry {
    private Quarry() {
    }

    /** The bonus ore for breaking this stone block, or EMPTY. */
    public static ItemStack bonus(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (!state.is(BlockTags.BASE_STONE_OVERWORLD)) return ItemStack.EMPTY;
        double scale = ColonyConfig.get().economy.quarry_ore_chance_scale;
        if (scale <= 0) return ItemStack.EMPTY;
        int y = pos.getY();
        double roll = random.nextDouble() / scale;
        double acc = 0;
        if (y < 0 && roll < (acc += 0.0005)) return new ItemStack(Items.DIAMOND);
        if (level.getBiome(pos).is(BiomeTags.IS_MOUNTAIN) && roll < (acc += 0.0005)) return new ItemStack(Items.EMERALD);
        if (y < 32 && roll < (acc += 0.002)) return new ItemStack(Items.RAW_GOLD);
        if (y < 32 && roll < (acc += 0.003)) return new ItemStack(Items.LAPIS_LAZULI, 2);
        if (y < 16 && roll < (acc += 0.004)) return new ItemStack(Items.REDSTONE, 2);
        if (roll < (acc += 0.012)) return new ItemStack(Items.RAW_IRON);
        if (roll < (acc += 0.012)) return new ItemStack(Items.RAW_COPPER);
        if (roll < (acc += 0.02)) return new ItemStack(Items.COAL);
        return ItemStack.EMPTY;
    }
}
