package net.betterthanvillage.society;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** NeoForge game-event listeners of the society subsystem. */
public final class SocietyEvents {
    private SocietyEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(SocietyEvents::onDeath);
    }

    /** Expedition deaths (06 §6.7); fired at the start of LivingEntity#die like the original onDeath HEAD hook. */
    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Villager v && v.level() instanceof ServerLevel level) {
            Colonization.onVillagerDeath(level, v);
        }
    }
}
