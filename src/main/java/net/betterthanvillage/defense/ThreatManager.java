package net.betterthanvillage.defense;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.WaypointNavigator;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Threat, alarm and hostility manager (02 §4). */
public final class ThreatManager {
    /** Vertical half-height of the colony cylinder and box. */
    public static final int VERTICAL = 12;
    private static final double ENGAGE_RADIUS_SQ = 144.0;

    private record CacheEntry(ServerLevel level, long computedAt, LivingEntity threat) {
    }

    private static final Map<Colony, CacheEntry> CACHE = new WeakHashMap<>();
    /** Debug counter of threat recomputations. */
    public static long recomputes;

    private ThreatManager() {
    }

    // ---------------------------------------------------------------- geometry

    /** The core block box expanded by radius on x/z and by 12 on y. */
    public static AABB colonyBox(Colony colony) {
        return new AABB(colony.core()).inflate(colony.radius(), VERTICAL, colony.radius());
    }

    /** True when the entity's positional colony is this colony. */
    public static boolean memberOf(ServerLevel level, Colony colony, Entity e) {
        Colony at = ColonyManager.colonyAt(level, e.blockPosition());
        return at != null && at.id().equals(colony.id());
    }

    /** Both entities stand in the same colony (by position). */
    public static boolean sameColony(ServerLevel level, Entity a, Entity b) {
        Colony ca = ColonyManager.colonyAt(level, a.blockPosition());
        if (ca == null) return false;
        Colony cb = ColonyManager.colonyAt(level, b.blockPosition());
        return cb != null && ca.id().equals(cb.id());
    }

    /** Non-null, alive, and not a creeper, villager, iron golem or creative/spectator player. */
    public static boolean isValidHostile(LivingEntity e) {
        if (e == null || !e.isAlive()) return false;
        if (e instanceof Creeper || e instanceof Villager || e instanceof IronGolem) return false;
        return !(e instanceof Player p) || (!p.isCreative() && !p.isSpectator());
    }

    /** Cylinder test: horizontal distance to core centre <= radius and |dy| <= 12. */
    public static boolean inColony(Colony colony, LivingEntity e) {
        double dx = e.getX() - (colony.core().getX() + 0.5);
        double dz = e.getZ() - (colony.core().getZ() + 0.5);
        return dx * dx + dz * dz <= (double) colony.radius() * colony.radius() && Math.abs(e.getY() - colony.core().getY()) <= VERTICAL;
    }

    private static boolean validInside(Colony colony, LivingEntity e) {
        return isValidHostile(e) && inColony(colony, e);
    }

    // ---------------------------------------------------------------- threat search

    /** Nearest valid hostile threatening the colony, or null (cached for up to 5 ticks). */
    public static LivingEntity findThreat(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        CacheEntry entry = CACHE.get(colony);
        if (entry != null && entry.level() == level && now >= entry.computedAt() && now - entry.computedAt() < 5
                && (entry.threat() == null || validInside(colony, entry.threat()))) {
            return entry.threat();
        }
        recomputes++;
        LivingEntity threat = explicitThreat(level, colony, now);
        if (threat == null) {
            threat = hostileMob(level, colony);
        }
        CACHE.put(colony, new CacheEntry(level, now, threat));
        return threat;
    }

    private static LivingEntity explicitThreat(ServerLevel level, Colony colony, long now) {
        if (colony.assaultAttacker != null && now < colony.assaultUntil
                && level.getEntity(colony.assaultAttacker) instanceof LivingEntity attacker && validInside(colony, attacker)) {
            return attacker;
        }
        if (colony.reportedThief != null && now < colony.theftUntil) {
            Player thief = level.getPlayerByUUID(colony.reportedThief);
            if (thief != null && thief.level() == level && validInside(colony, thief)) {
                return thief;
            }
        }
        for (Villager v : level.getEntitiesOfClass(Villager.class, colonyBox(colony), LivingEntity::isAlive)) {
            LivingEntity attacker = v.getLastHurtByMob();
            if (attacker != null && v.tickCount - v.getLastHurtByMobTimestamp() <= 100 && memberOf(level, colony, v)
                    && validInside(colony, attacker)) {
                return attacker;
            }
        }
        return null;
    }

    private static LivingEntity hostileMob(ServerLevel level, Colony colony) {
        AABB box = colonyBox(colony);
        List<LivingEntity> friends = new ArrayList<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, LivingEntity::isAlive)) {
            if (memberOf(level, colony, v)) friends.add(v);
        }
        friends.addAll(level.getEntitiesOfClass(IronGolem.class, box, LivingEntity::isAlive));
        if (friends.isEmpty()) return null;
        BlockPos core = colony.core();
        Monster best = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Monster mob : level.getEntitiesOfClass(Monster.class, box, m -> m.isAlive() && !(m instanceof Creeper))) {
            if (!inColony(colony, mob) || !engaging(mob, friends)) continue;
            LivingEntity target = mob.getTarget();
            int priority = 2;
            if (target instanceof Villager tv) {
                priority = colony.roleOf(tv.getUUID()) == ColonyRole.GUARD ? 0 : 1;
            }
            double d = mob.blockPosition().distSqr(core);
            if (priority < bestPriority || (priority == bestPriority && d < bestDist)) {
                best = mob;
                bestPriority = priority;
                bestDist = d;
            }
        }
        return best;
    }

    private static boolean engaging(Monster mob, List<LivingEntity> friends) {
        LivingEntity target = mob.getTarget();
        if (target != null && friends.contains(target)) return true;
        for (LivingEntity f : friends) {
            if (f.distanceToSqr(mob) <= ENGAGE_RADIUS_SQ && Math.abs(f.getY() - mob.getY()) <= 6 && f.hasLineOfSight(mob)) {
                return true;
            }
        }
        return false;
    }

    /** Raid at the core or a hostile threat near the colony (checked every 10 ticks by the world tick). */
    public static boolean threatNear(ServerLevel level, Colony colony) {
        return RaidShelter.isActive(level, colony) || findThreat(level, colony) != null;
    }

    public static void clearCaches() {
        CACHE.clear();
        recomputes = 0;
    }

    // ---------------------------------------------------------------- reporting

    /** A living attacker hurt a colony villager/golem: alarm and mobilize (02 §4 reportAttack). */
    public static void reportAttack(ServerLevel level, Colony colony, LivingEntity attacker) {
        if (colony == null || !validInside(colony, attacker)) return;
        long now = level.getGameTime();
        CACHE.remove(colony);
        colony.assaultAttacker = attacker.getUUID();
        colony.assaultUntil = now + 1200;
        colony.lastThreatTick = now;
        mobilize(level, colony, attacker);
        ColonyManager.markDirty(level);
    }

    /** Adds hostility points against a player (theft, griefing, trespass; 02 §4). */
    public static void addPlayerHostility(ServerLevel level, Colony colony, Player player, int points) {
        if (colony == null || player == null || points <= 0 || player.isCreative() || player.isSpectator()) return;
        long now = level.getGameTime();
        CACHE.remove(colony);
        colony.reportedThief = player.getUUID();
        colony.theftUntil = Math.max(colony.theftUntil, now + 1200);
        colony.stolenItems += points;
        colony.lastThreatTick = now;
        mobilize(level, colony, player);
        ColonyManager.markDirty(level);
    }

    /** Sends every colony golem and guard after the attacker. */
    private static void mobilize(ServerLevel level, Colony colony, LivingEntity attacker) {
        AABB box = colonyBox(colony);
        BlockPos target = attacker.blockPosition();
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, box, LivingEntity::isAlive)) {
            if (!memberOf(level, colony, golem)) continue;
            golem.setTarget(attacker);
            WaypointNavigator.travel(level, golem, target, 1.9);
        }
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, LivingEntity::isAlive)) {
            if (colony.roleOf(v.getUUID()) != ColonyRole.GUARD) continue;
            if (v.isSleeping()) v.stopSleeping();
            v.setTradingPlayer(null);
            v.getBrain().setMemoryWithExpiry(ModRegistries.THREAT_TARGET.get(), attacker, 1200L);
            VillagerUtil.doExclusively(v, Activity.FIGHT);
            WaypointNavigator.travel(level, v, target, 1.15);
        }
    }
}
