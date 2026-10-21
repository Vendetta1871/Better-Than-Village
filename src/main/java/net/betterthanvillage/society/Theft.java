package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.economy.Households;
import net.betterthanvillage.law.Law;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;

/**
 * Theft from colony storages (docs_v2/05-law.md §Offences): what counts as the colony's goods. Net accounting per
 * open container lives in {@link PlayerBlocks}; witnesses, warning and war in {@link Law#theft}.
 */
public final class Theft {
    private Theft() {
    }

    /** Colony owning the storage at pos (managed or scanned, not player storage), or null. */
    static Colony storageOwner(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            // Home chests belong to a villager; taking from them is theft as well (05 §Offences).
            if (colony.managedStorages.containsKey(pos) || colony.storages.contains(pos) || Households.ownerOf(colony, pos) != null) {
                return Protection.isProtectedContainer(level, pos) ? null : colony;
            }
        }
        return null;
    }

    static boolean valuable(ItemStack s) {
        return s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.GOLD_INGOT) || s.is(Items.NETHERITE_INGOT)
                || s.is(Items.NETHERITE_SCRAP) || s.isEnchanted() || s.getRarity() != Rarity.COMMON;
    }

    /** The player took a net amount of colony items; unnoticed thefts go unpunished. */
    static void taken(ServerLevel level, Colony colony, Player player, int items, boolean valuable) {
        Law.theft(level, colony, player, items, valuable);
    }
}
