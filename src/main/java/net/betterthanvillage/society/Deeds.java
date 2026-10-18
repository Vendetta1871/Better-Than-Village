package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Property and land deeds (06 §2) (stub, not implemented yet). */
public final class Deeds {
    private Deeds() {
    }

    /** Server tick: property-deed item refresh. */
    public static void serverTick(net.minecraft.server.MinecraftServer server) {
    }

    /** Right-click on a block with a land deed; returns true if handled (cancel vanilla). */
    public static boolean onUseOnBlock(ServerLevel level, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, BlockPos pos) {
        return false;
    }
}
