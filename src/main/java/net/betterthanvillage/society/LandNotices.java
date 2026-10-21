package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.LandWarning;
import net.betterthanvillage.law.Law;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.UUID;

/**
 * Building in a territory (docs_v2/04-land.md §Plots and houses, 05-law.md §Offences): placements outside the
 * player's plots are an offence handled by {@link Law}. Once a player was warned in a village, their blocks there
 * are illegal builds the miner tears down after the grace period (LandWork), even if they stopped in time.
 */
public final class LandNotices {
    /** Illegal builds of a warned player are torn down this long after the warning. */
    public static final long GRACE = 600;

    private LandNotices() {
    }

    /** "Structural" placed states: full cube, door, bed, chest, barrel, stairs or slab. */
    public static boolean structural(BlockState state) {
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || state.getBlock() instanceof DoorBlock
                || state.is(BlockTags.BEDS) || state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock;
    }

    /** Utility and temporary blocks never count as building. */
    public static final Set<Block> UTILITY = Set.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.SCAFFOLDING, Blocks.LADDER, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
            Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.TNT, Blocks.ENCHANTING_TABLE,
            Blocks.ANVIL, Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE,
            Blocks.LOOM, Blocks.COMPOSTER, Blocks.BREWING_STAND, Blocks.CAULDRON, Blocks.JUKEBOX, Blocks.NOTE_BLOCK);

    /** A successful player placement. */
    public static void onPlacement(ServerLevel level, Player player, BlockPos pos, BlockState state) {
        if (Law.buildingBlock(state)) Law.blockChanged(level, player, pos, true);
    }

    /** The player was warned for building in this village: their illegal blocks there will come down. */
    public static void warned(ServerLevel level, Colony colony, UUID player, BlockPos pos) {
        ColonySavedData data = ColonyManager.data(level);
        LandWarning record = null;
        for (LandWarning w : data.landWarnings) {
            if (player.equals(w.player)) record = w;
        }
        if (record == null || !record.colony.equals(colony.id())) {
            if (record != null) data.landWarnings.remove(record);
            record = new LandWarning();
            record.player = player;
            record.colony = colony.id();
            data.landWarnings.add(record);
        }
        record.pos = pos.immutable();
        record.delivered = level.getGameTime();
        data.setDirty();
    }
}
