package net.betterthanvillage;

import net.betterthanvillage.ai.work.RoleTools;
import net.betterthanvillage.colony.BellScanner;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.society.ColonyCommands;
import net.betterthanvillage.society.Deeds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game event wiring (NeoForge event bus). */
public final class ModEvents {
    private ModEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ModEvents::onLevelTick);
        bus.addListener(ModEvents::onServerTick);
        bus.addListener(ModEvents::onChunkLoad);
        bus.addListener(ModEvents::onRightClickBlock);
        bus.addListener(ModEvents::onEntityLeave);
        bus.addListener(ModEvents::onRegisterCommands);
        bus.addListener(ModEvents::onServerStopped);
        net.betterthanvillage.defense.DefenseEvents.register(bus);
        net.betterthanvillage.gather.GatherEvents.register(bus);
        net.betterthanvillage.food.FoodEvents.register(bus);
        net.betterthanvillage.construction.ConstructionEvents.register(bus);
        net.betterthanvillage.society.SocietyEvents.register(bus);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BellScanner.processQueue(level);
            ColonyManager.tickLevel(level);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Deeds.serverTick(event.getServer());
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            BellScanner.onChunkLoad(level, chunk);
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (Deeds.onUseOnBlock(level, event.getEntity(), event.getHand(), event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (level.getBlockState(event.getPos()).is(Blocks.BELL)) {
            ColonyManager.bellAlarm(level, event.getPos());
        }
    }

    /** A villager removed for good (killed, converted, discarded) loses its colony role entries; unloads keep them. */
    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Villager v) {
            Entity.RemovalReason reason = v.getRemovalReason();
            if (reason != null && reason.shouldDestroy()) {
                ColonyManager.forgetVillager(level, v.getUUID());
            }
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ColonyCommands.register(event.getDispatcher());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ColonyManager.clearCaches();
        RoleTools.clearCaches();
        BellScanner.clear();
    }
}
