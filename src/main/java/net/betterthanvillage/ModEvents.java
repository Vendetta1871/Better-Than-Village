package net.betterthanvillage;

import net.betterthanvillage.ai.work.RoleTools;
import net.betterthanvillage.colony.BellScanner;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.society.ColonyCommands;
import net.betterthanvillage.society.Deeds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Game event wiring (NeoForge event bus). */
public final class ModEvents {
    private ModEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ModEvents::onLevelTick);
        bus.addListener(net.betterthanvillage.economy.CureTask::onConverted);
        bus.addListener(ModEvents::onServerTick);
        bus.addListener(ModEvents::onChunkLoad);
        bus.addListener(ModEvents::onChunkUnload);
        bus.addListener(ModEvents::onBlockPlaced);
        bus.addListener(ModEvents::onBlockBroken);
        bus.addListener(ModEvents::onRightClickBlock);
        bus.addListener(ModEvents::onEntityLeave);
        bus.addListener(ModEvents::onEntityJoin);
        bus.addListener(ModEvents::onVillagerDeath);
        bus.addListener(ModEvents::onRegisterCommands);
        bus.addListener(ModEvents::onServerStopped);
        net.betterthanvillage.defense.DefenseEvents.register(bus);
        net.betterthanvillage.gather.GatherEvents.register(bus);
        net.betterthanvillage.food.FoodEvents.register(bus);
        net.betterthanvillage.construction.ConstructionEvents.register(bus);
        net.betterthanvillage.society.SocietyEvents.register(bus);
        net.betterthanvillage.law.LawEvents.register(bus);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            processBellRings(level);
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
            Protection.invalidateBeacons(level, chunk.getPos().x, chunk.getPos().z);
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Protection.invalidateBeacons(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    private static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getPlacedBlock().is(Blocks.BEACON)) {
            Protection.invalidateBeacons(level, event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        }
    }

    private static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getState().is(Blocks.BEACON)) {
            Protection.invalidateBeacons(level, event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
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
        if (event.getHand() == InteractionHand.MAIN_HAND && level.getBlockState(event.getPos()).is(Blocks.BELL)) {
            // Vanilla decides whether the bell rings (hit face, sneaking with an item); checked on the next level tick.
            PENDING_RINGS.computeIfAbsent(level, l -> new ArrayList<>()).add(new PendingRing(event.getPos().immutable(), event.getEntity().getUUID()));
        }
    }

    private record PendingRing(BlockPos pos, UUID player) {
    }

    private static final Map<ServerLevel, List<PendingRing>> PENDING_RINGS = new WeakHashMap<>();

    /** Bell clicks that actually rang the bell (its block entity started shaking) alarm the colony. */
    private static void processBellRings(ServerLevel level) {
        List<PendingRing> rings = PENDING_RINGS.remove(level);
        if (rings == null) {
            return;
        }
        for (PendingRing ring : rings) {
            if (level.isLoaded(ring.pos()) && level.getBlockEntity(ring.pos()) instanceof BellBlockEntity bell && bell.shaking && bell.ticks <= 2) {
                ColonyManager.bellAlarm(level, ring.pos(), ring.player());
            }
        }
    }

    /** A villager removed for good (killed, converted, discarded) loses its colony role entries; unloads keep them. */
    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Villager v) {
            Entity.RemovalReason reason = v.getRemovalReason();
            if (reason != null && reason.shouldDestroy() && !net.betterthanvillage.world.Materializer.stashing(v.getUUID())) {
                ColonyManager.forgetVillager(level, v.getUUID());
            }
        }
    }

    /**
     * Entities of travellers loaded away from their caravan or army are kept out (docs_v2/08-simulation.md
     * §Materialization), and so are villagers and golems that died in abstract battles.
     */
    private static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!(e instanceof Villager) && !(e instanceof net.minecraft.world.entity.animal.IronGolem)) return;
        if (net.betterthanvillage.warfare.Casualties.onJoin(level, e) || net.betterthanvillage.warfare.Campaigns.onJoin(level, e)
                || (e instanceof Villager && net.betterthanvillage.world.Caravans.onJoin(level, e))
                || net.betterthanvillage.society.AbstractExpedition.onJoin(level, e)) {
            event.setCanceled(true);
        }
    }

    private static void onVillagerDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof Villager v) net.betterthanvillage.world.Caravans.onDeath(level, v);
        if (event.getEntity() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse) net.betterthanvillage.warfare.Army.horseDied(level, horse);
        if (event.getEntity() instanceof Villager || event.getEntity() instanceof net.minecraft.world.entity.animal.IronGolem) {
            net.betterthanvillage.warfare.Campaigns.onDeath(level, event.getEntity(), event.getSource().getEntity());
            net.betterthanvillage.warfare.Army.onDeath(level, event.getEntity());
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ColonyCommands.register(event.getDispatcher());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ColonyManager.clearCaches();
        RoleTools.clearCaches();
        BellScanner.clear();
        net.betterthanvillage.colony.Domain.clear();
        net.betterthanvillage.law.Law.clearCaches();
        net.betterthanvillage.law.Wars.clearCaches();
        net.betterthanvillage.debug.TestBot.clear();
        net.betterthanvillage.politics.Politics.clearCaches();
        net.betterthanvillage.world.Materializer.clearCaches();
        net.betterthanvillage.warfare.Campaigns.clearCaches();
        net.betterthanvillage.warfare.PlayerRaid.clearCaches();
        net.betterthanvillage.warfare.NightOps.clearCaches();
        PENDING_RINGS.clear();
    }
}
