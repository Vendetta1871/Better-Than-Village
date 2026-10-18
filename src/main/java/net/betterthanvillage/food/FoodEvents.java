package net.betterthanvillage.food;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** NeoForge game-event listeners of the food subsystem. */
public final class FoodEvents {
    private FoodEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(FoodEvents::onServerStopped);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        FarmTerritory.clearCaches();
    }
}
