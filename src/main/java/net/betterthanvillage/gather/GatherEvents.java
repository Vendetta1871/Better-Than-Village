package net.betterthanvillage.gather;

import net.betterthanvillage.ai.nav.AntiStuck;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** NeoForge game-event listeners of the gather subsystem. */
public final class GatherEvents {
    private GatherEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(GatherEvents::onIncomingDamage);
    }

    /** Villager rescue (02 §2.4): suffocation or lethal fall damage triggers the emergency teleport, cancelling the damage on success. */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Villager v) || !(v.level() instanceof ServerLevel level)) return;
        DamageSource source = event.getSource();
        boolean suffocation = source.is(DamageTypes.IN_WALL);
        boolean lethalFall = source.is(DamageTypeTags.IS_FALL) && event.getAmount() >= v.getHealth();
        if (!suffocation && !lethalFall) return;
        // Members only, sent to their own colony's core.
        Colony colony = ColonyManager.colonyOf(level, v);
        if (colony != null && AntiStuck.emergencyTeleport(level, colony, v)) {
            event.setCanceled(true);
        }
    }
}
