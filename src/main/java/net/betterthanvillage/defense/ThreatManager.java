package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

/** Threat, alarm and hostility manager (02 §4) (stub, not implemented yet). */
public final class ThreatManager {
    private ThreatManager() {
    }

    /** Nearest valid hostile threatening the colony, or null. */
    public static LivingEntity findThreat(ServerLevel level, Colony colony) {
        return null;
    }

    /** Raid at the core or a hostile threat near the colony (checked every 10 ticks by the world tick). */
    public static boolean threatNear(ServerLevel level, Colony colony) {
        return false;
    }

    /** Non-null, alive, and not a creeper, villager, iron golem or creative/spectator player. */
    public static boolean isValidHostile(LivingEntity e) {
        return e != null && e.isAlive();
    }

    /** Cylinder test: horizontal distance to core centre <= radius and |dy| <= 12. */
    public static boolean inColony(Colony colony, LivingEntity e) {
        double dx = e.getX() - (colony.core().getX() + 0.5);
        double dz = e.getZ() - (colony.core().getZ() + 0.5);
        return dx * dx + dz * dz <= (double) colony.radius() * colony.radius() && Math.abs(e.getY() - colony.core().getY()) <= 12;
    }

    public static void clearCaches() {
    }

    /** A living attacker hurt a colony villager/golem: alarm and mobilize (02 §4 reportAttack). */
    public static void reportAttack(ServerLevel level, Colony colony, LivingEntity attacker) {
    }

    /** Adds hostility points against a player (theft, griefing, trespass; 02 §4). */
    public static void addPlayerHostility(ServerLevel level, Colony colony, net.minecraft.world.entity.player.Player player, int points) {
    }
}
