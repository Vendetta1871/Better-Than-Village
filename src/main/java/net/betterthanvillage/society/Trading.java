package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Player trading driven by colony stock (06 §1.3) (stub, not implemented yet). */
public final class Trading {
    private Trading() {
    }

    /** Stop, idle and look at the customer while trading. */
    public static void freeze(Villager v) {
        v.getNavigation().stop();
        v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        v.getBrain().setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.IDLE);
        if (v.getTradingPlayer() != null) {
            v.getLookControl().setLookAt(v.getTradingPlayer(), 30f, 30f);
        }
    }

    /**
     * Villager right-click by a player (main hand, adult, server side, villager inside a colony).
     * Returns a result to cancel vanilla, or null to let vanilla handle it.
     */
    public static net.minecraft.world.InteractionResult interact(ServerLevel level, Villager v, net.minecraft.world.entity.player.Player player,
                                                               net.minecraft.world.InteractionHand hand) {
        return null;
    }

    /** Storage-side settlement after a completed trade. */
    public static void afterUsing(ServerLevel level, Villager v, net.minecraft.world.item.trading.MerchantOffer offer) {
    }
}
