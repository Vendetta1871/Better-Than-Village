package net.betterthanvillage.client;

import net.betterthanvillage.BetterThanVillage;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client wiring of the defense subsystem: guard gear layer on the villager renderer. */
@EventBusSubscriber(modid = BetterThanVillage.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DefenseClient {
    private DefenseClient() {
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        if (event.getRenderer(EntityType.VILLAGER) instanceof VillagerRenderer renderer) {
            renderer.addLayer(new VillagerGearLayer(renderer, event.getContext()));
        }
    }
}
