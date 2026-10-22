package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.betterthanvillage.law.Law;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** NeoForge game-event listeners of the defense subsystem (02 §2.3, §2.4). */
public final class DefenseEvents {
    private DefenseEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(DefenseEvents::onEntityTick);
        bus.addListener(DefenseEvents::onIncomingDamage);
        bus.addListener(DefenseEvents::onDamagePost);
        bus.addListener(DefenseEvents::onProjectileImpact);
    }

    /** Golem mob tick: regeneration, swimming, colony targeting and pillar breaking. */
    private static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof IronGolem golem && golem.level() instanceof ServerLevel level && golem.isAlive() && !golem.isNoAi()) {
            GolemManager.tickGolem(level, golem);
        }
    }

    /** Golems take no damage from villagers of their own colony. */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof IronGolem golem && golem.level() instanceof ServerLevel level
                && event.getSource().getEntity() instanceof Villager v && ThreatManager.sameColony(level, golem, v)) {
            event.setCanceled(true);
        }
    }

    /** Damage applied to a colony villager or golem raises the alarm against a living attacker. */
    private static void onDamagePost(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !(event.getSource().getEntity() instanceof LivingEntity attacker)) return;
        // hurt() reports failure when a shield absorbed everything
        if (event.getBlockedDamage() > 0 && event.getNewDamage() <= 0) return;
        // harmless hits (snowballs, eggs) never raise the alarm
        if (event.getOriginalDamage() <= 0) return;
        if (!(victim instanceof Villager) && (!(victim instanceof IronGolem) || !ThreatManager.isValidHostile(attacker))) {
            return;
        }
        Colony colony = victim instanceof Villager v ? ColonyManager.colonyOf(level, v) : ColonyManager.colonyAt(level, victim.blockPosition());
        if (colony == null) return;
        if (attacker instanceof Player player) {
            // A soldier or night agent away from home is fair game: fighting it is no offence against its country
            // (docs_v2/07-warfare.md §Night operations, Getting caught).
            if (net.betterthanvillage.warfare.Campaigns.away(level, victim.getUUID()) && !colony.withinXZ(victim.blockPosition(), 0)) return;
            // Players answer to the law: a warning first, war on repeat (docs_v2/05-law.md).
            Law.attacked(level, colony, player, victim);
        } else {
            ThreatManager.reportAttack(level, colony, attacker);
        }
    }

    /** Guard projectiles never hurt villagers or golems of the shooter's colony. */
    private static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile p = event.getProjectile();
        if (!(p.level() instanceof ServerLevel level) || !(p.getOwner() instanceof Villager owner)
                || !(event.getRayTraceResult() instanceof EntityHitResult hit)) {
            return;
        }
        Entity victim = hit.getEntity();
        if (!(victim instanceof Villager) && !(victim instanceof IronGolem)) return;
        var army = net.betterthanvillage.warfare.Campaigns.ofSoldier(level, owner.getUUID());
        if (army != null && army == net.betterthanvillage.warfare.Campaigns.ofSoldier(level, victim.getUUID())) {
            // Soldiers of one army never shoot each other.
            p.discard();
            event.setCanceled(true);
            return;
        }
        Colony shooter = ColonyManager.colonyOf(level, owner);
        if (shooter == null || shooter.roleOf(owner.getUUID()) != ColonyRole.WARRIOR) return;
        if (ThreatManager.memberOf(level, shooter, victim)) {
            p.discard();
            event.setCanceled(true);
        }
    }
}
