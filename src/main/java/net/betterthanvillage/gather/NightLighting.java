package net.betterthanvillage.gather;

import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
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

/** Village night lighting (03 §4): villagers place standing torches in dark spots inside the colony. */
public final class NightLighting {
    private NightLighting() {
    }

    public static void tick(ServerLevel level, Colony colony, Villager v) {
        if ((level.getGameTime() + (long) v.getId() * 17) % 80 != 0 || !v.isAlive() || v.isSleeping()) return;
        BlockPos here = v.blockPosition();
        if (level.getMaxLocalRawBrightness(here) > 6 || torchNear(level, here)) return;
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
        if (SafeEdit.place(level, spot, Blocks.TORCH.defaultBlockState(), 3)) {
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
            ColonyStorage.deposit(level, colony, external);
        }
    }

    private static boolean torchNear(ServerLevel level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -3, -5), pos.offset(5, 3, 5))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) return true;
        }
        return false;
    }

    private static BlockPos findSpot(ServerLevel level, Colony colony, BlockPos here) {
        BlockState torch = Blocks.TORCH.defaultBlockState();
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos c = here.offset(dx, dy, dz);
                        if (!level.isLoaded(c) || !colony.containsXZ(c)) continue;
                        if (level.getMaxLocalRawBrightness(c) > 6 || !level.getBlockState(c).isAir() || !torch.canSurvive(level, c)) continue;
                        return c;
                    }
                }
            }
        }
        return null;
    }
}
