package net.betterthanvillage.gather;

import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.ToolSupply;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.construction.Damage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Village night lighting: the carpenter builds lamp posts (a fence with a torch on top) in dark spots inside the
 * colony (docs_v2/02-professions.md §Carpenter). Posts are public work: the treasury pays fence and torch.
 */
public final class NightLighting {
    private NightLighting() {
    }

    /** Cells deeper than this below the surface are tunnels or caves and are not lit. */
    private static final int MAX_DEPTH = 6;

    public static void tick(ServerLevel level, Colony positional, Villager v) {
        if ((level.getGameTime() + (long) v.getId() * 17) % 80 != 0 || !v.isAlive() || v.isSleeping() || v.isBaby()) return;
        // Only adult members light their own colony.
        Colony colony = ColonyManager.colonyOf(level, v);
        if (colony == null || colony.roleOf(v.getUUID()) != net.betterthanvillage.colony.ColonyRole.CARPENTER) return;
        BlockPos here = v.blockPosition();
        if (level.getMaxLocalRawBrightness(here) > 6 || here.getY() < GatherUtil.surfaceY(level, here) - MAX_DEPTH || torchNear(level, here)) return;
        BlockPos spot = findSpot(level, colony, here);
        if (spot == null) return;
        SimpleContainer inv = v.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.TORCH)) {
                slot = i;
                break;
            }
        }
        ItemStack external = ItemStack.EMPTY;
        if (slot < 0) {
            external = ColonyStorage.withdrawOne(level, colony, s -> s.is(Items.TORCH));
            if (external.isEmpty()) {
                if (ColonyStorage.has(level, colony, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL))
                        && ColonyStorage.has(level, colony, s -> s.is(Items.STICK) || s.is(ItemTags.LOGS))) {
                    ColonyStorage.consume(level, colony, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1);
                    ColonyStorage.consume(level, colony, s -> s.is(Items.STICK) || s.is(ItemTags.LOGS), 1);
                    external = new ItemStack(Items.TORCH, 4);
                } else {
                    return;
                }
            }
        }
        // The post: a fence from the treasury (or two planks), the torch on top of it.
        boolean fence = ColonyStorage.consume(level, colony, s -> s.is(ItemTags.WOODEN_FENCES), 1)
                || ColonyStorage.consume(level, colony, s -> s.is(ItemTags.PLANKS), 2);
        if (!fence) {
            if (!external.isEmpty()) ToolSupply.stash(level, colony, v, external);
            return;
        }
        if (!SafeEdit.place(level, spot, Blocks.OAK_FENCE.defaultBlockState(), 3)) {
            ColonyStorage.deposit(level, colony, new ItemStack(Items.OAK_FENCE));
            if (!external.isEmpty()) ToolSupply.stash(level, colony, v, external);
            return;
        }
        if (SafeEdit.place(level, spot.above(), Blocks.TORCH.defaultBlockState(), 3)) {
            net.betterthanvillage.ai.work.WorkTracker.credit(v, "фонарный столб", 2);
            if (slot >= 0) {
                inv.getItem(slot).shrink(1);
                inv.setChanged();
            } else {
                external.shrink(1);
                if (!external.isEmpty()) {
                    ItemStack rest = inv.addItem(external);
                    if (!rest.isEmpty()) {
                        ColonyStorage.deposit(level, colony, rest);
                        if (!rest.isEmpty()) level.addFreshEntity(new ItemEntity(level, v.getX(), v.getY() + 0.5, v.getZ(), rest));
                    }
                }
            }
            v.swing(InteractionHand.MAIN_HAND);
        } else if (!external.isEmpty()) {
            ToolSupply.stash(level, colony, v, external);
        }
    }

    private static boolean torchNear(ServerLevel level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -3, -5), pos.offset(5, 3, 5))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) return true;
        }
        return false;
    }

    private static boolean nearPlayerBlock(ServerLevel level, BlockPos c) {
        ColonySavedData data = ColonyManager.data(level);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) {
            if (data.isPlayerBlock(p)) return true;
        }
        return false;
    }

    /** A torch on a plot that is still to be built would block the builder (and the site search). */
    private static boolean onPendingPlot(Colony colony, BlockPos c) {
        for (ConstructionJob j : colony.jobs) {
            if (j.isActive() && j.contains(c)) return true;
        }
        return false;
    }

    private static BlockPos findSpot(ServerLevel level, Colony colony, BlockPos here) {
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos c = here.offset(dx, dy, dz);
                        if (!level.isLoaded(c) || !colony.containsXZ(c)) continue;
                        // A post needs two free cells on sturdy ground.
                        if (level.getMaxLocalRawBrightness(c) > 6 || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()
                                || !level.getBlockState(c.below()).isFaceSturdy(level, c.below(), net.minecraft.core.Direction.UP)) continue;
                        BlockState ground = level.getBlockState(c.below());
                        if (ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND)) continue;
                        // A protected spot would only churn torches every attempt and hide valid spots further out.
                        if (!Protection.terrainModifiable(level, c) || onPendingPlot(colony, c)) continue;
                        // No tunnels/caves, no roofed cells by day (house interiors), no structures or player builds.
                        if (c.getY() < GatherUtil.surfaceY(level, c) - MAX_DEPTH || (level.isDay() && !level.canSeeSky(c))) continue;
                        if (GatherUtil.inStructure(level, c, 0, 0, 0) || Damage.isProtectedHousePiece(level, c) || nearPlayerBlock(level, c)) continue;
                        return c;
                    }
                }
            }
        }
        return null;
    }
}
