package net.betterthanvillage.food;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** NeoForge game-event listeners of the food subsystem. */
public final class FoodEvents {
    private FoodEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(FoodEvents::onServerStopped);
        bus.addListener(EventPriority.LOWEST, FoodEvents::onToolModification);
        bus.addListener(EventPriority.LOWEST, FoodEvents::onLivingDrops);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        FarmTerritory.clearCaches();
    }

    /** Farmland tilled by a player is a player block: colony farmers leave that field alone. */
    private static void onToolModification(BlockEvent.BlockToolModificationEvent event) {
        if (event.isSimulated() || !ItemAbilities.HOE_TILL.equals(event.getItemAbility())) return;
        Player player = event.getPlayer();
        if (player == null || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockState result = event.getFinalState();
        if (result == null || !result.is(Blocks.FARMLAND)) return;
        BlockPos pos = event.getPos().immutable();
        ColonyManager.data(level).recordPlacement(pos, player.getUUID(), level.getGameTime());
        boolean changed = false;
        for (Colony colony : ColonyManager.data(level).colonies()) {
            changed |= colony.farmland.remove(pos);
        }
        if (changed) ColonyManager.markDirty(level);
    }

    /** Marks a player's death drops so that villagers never pick them up. */
    private static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        for (ItemEntity item : event.getDrops()) {
            ItemPickup.markPlayerLoot(item);
        }
    }
}
