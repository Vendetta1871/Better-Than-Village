package net.betterthanvillage.law;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** NeoForge game-event listeners of the law subsystem. */
public final class LawEvents {
    private LawEvents() {
    }

    public static void register(IEventBus bus) {
        // LOWEST: run after any listener that might cancel the death.
        bus.addListener(EventPriority.LOWEST, LawEvents::onDeath);
    }

    /** Killed animals (an offence), losses of a country at war and players killed by a country's warriors. */
    private static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        var killer = event.getSource().getEntity();
        if (killer instanceof Player player) {
            if (victim instanceof Animal animal) {
                Law.animalKilled(level, player, animal);
            } else if (victim instanceof Villager || victim instanceof IronGolem) {
                Wars.memberKilled(level, victim, player);
            }
        } else if (victim instanceof Player player && (killer instanceof Villager || killer instanceof IronGolem)) {
            Wars.playerKilled(level, player, (LivingEntity) killer);
        }
    }
}
