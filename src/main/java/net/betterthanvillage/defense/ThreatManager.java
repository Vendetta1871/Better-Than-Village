package net.betterthanvillage.defense;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.WaypointNavigator;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Threat, alarm and hostility manager (02 §4). */
public final class ThreatManager {
    /** Vertical half-height of the colony cylinder and box. */
    public static final int VERTICAL = 12;
    /** Hysteresis: a chased target only counts as gone once this far beyond the colony border. */
    public static final int BORDER_MARGIN = 8;
    private static final double ENGAGE_RADIUS_SQ = 144.0;
    private static final int MOBILIZE_GUARDS = 3;
    private static final int MOBILIZE_GOLEMS = 2;
    /** Persistent-data flag on golems sent after a player, so they give up once outside the colony. */
    public static final String MOBILIZED_TAG = "better_than_village_mobilized";
    private static final String LAST_THREAT_KEY = "btv_last_threat";

    /** A threat by entity id (no strong reference); priority 0 = attacking the colony, 1 = hostile and close. */
    private record Threat(int entityId, int priority) {
    }

    private record CacheEntry(ResourceKey<Level> dimension, long computedAt, List<Threat> threats) {
    }

    /** Where and when the colony last saw a threat (guard patrols visit it). */
    public record LastThreat(BlockPos pos, long tick) {
    }

    // Keyed by colony id and holding entity ids only: nothing pins a level, an entity or a removed colony.
    private static final Map<UUID, CacheEntry> CACHE = new HashMap<>();
    /** Colony id -> entity id -> tick until which an unreachable target is ignored. */
    private static final Map<UUID, Map<Integer, Long>> IGNORED = new HashMap<>();
    /** Debug counter of threat recomputations. */
    public static long recomputes;

    private ThreatManager() {
    }

    // ---------------------------------------------------------------- geometry

    /** The core block box expanded by radius on x/z and by 12 on y. */
    public static AABB colonyBox(Colony colony) {
        return new AABB(colony.core()).inflate(colony.radius(), VERTICAL, colony.radius());
    }

    private static Colony colonyOfEntity(ServerLevel level, Entity e) {
        return e instanceof Villager v ? ColonyManager.colonyOf(level, v) : ColonyManager.colonyAt(level, e.blockPosition());
    }

    /** True when the entity belongs to this colony (villagers by membership, others by position). */
    public static boolean memberOf(ServerLevel level, Colony colony, Entity e) {
        Colony c = colonyOfEntity(level, e);
        return c != null && c.id().equals(colony.id());
    }

    /** Both entities belong to the same colony. */
    public static boolean sameColony(ServerLevel level, Entity a, Entity b) {
        Colony ca = colonyOfEntity(level, a);
        if (ca == null) return false;
        Colony cb = colonyOfEntity(level, b);
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
        return inColony(colony, e, 0);
    }

    /** Cylinder test with the radius and the vertical limit widened by margin. */
    public static boolean inColony(Colony colony, LivingEntity e, int margin) {
        double dx = e.getX() - (colony.core().getX() + 0.5);
        double dz = e.getZ() - (colony.core().getZ() + 0.5);
        double r = colony.radius() + margin;
        return dx * dx + dz * dz <= r * r && Math.abs(e.getY() - colony.core().getY()) <= VERTICAL + margin;
    }

    private static boolean validInside(Colony colony, LivingEntity e) {
        return isValidHostile(e) && inColony(colony, e);
    }

    /** Neutral mobs only count as threats while they target a colony villager or golem. */
    private static boolean neutral(Mob mob) {
        return mob instanceof NeutralMob || mob instanceof Spider || mob instanceof AbstractPiglin;
    }

    // ---------------------------------------------------------------- threat search

    /** Best threat of the colony (attackers first, then nearest to the core), or null. */
    public static LivingEntity findThreat(ServerLevel level, Colony colony) {
        for (Threat t : threats(level, colony)) {
            LivingEntity e = resolve(level, colony, t);
            if (e != null) return e;
        }
        return null;
    }

    /**
     * Threat for one defender: the nearest one attacking the colony, else (unless attackingOnly)
     * the nearest close hostile. Entities matching skip are ignored.
     */
    public static LivingEntity nearestThreat(ServerLevel level, Colony colony, LivingEntity from, Predicate<LivingEntity> skip,
                                             boolean attackingOnly) {
        LivingEntity best = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Threat t : threats(level, colony)) {
            if (t.priority() > bestPriority || (attackingOnly && t.priority() > 0)) break;
            LivingEntity e = resolve(level, colony, t);
            if (e == null || e == from || skip.test(e)) continue;
            double d = e.distanceToSqr(from);
            if (t.priority() < bestPriority || d < bestDist) {
                best = e;
                bestPriority = t.priority();
                bestDist = d;
            }
        }
        return best;
    }

    /** The entity is currently attacking the colony (explicit attacker or targeting a villager/golem). */
    public static boolean isAttacking(ServerLevel level, Colony colony, LivingEntity e) {
        if (e == null) return false;
        for (Threat t : threats(level, colony)) {
            if (t.priority() > 0) break;
            if (t.entityId() == e.getId()) return true;
        }
        return false;
    }

    private static LivingEntity resolve(ServerLevel level, Colony colony, Threat t) {
        return level.getEntity(t.entityId()) instanceof LivingEntity e && validInside(colony, e)
                && !isIgnored(colony, e, level.getGameTime()) ? e : null;
    }

    private static List<Threat> threats(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        CacheEntry entry = CACHE.get(colony.id());
        if (entry != null && entry.dimension() == level.dimension() && now >= entry.computedAt() && now - entry.computedAt() < 5) {
            return entry.threats();
        }
        recomputes++;
        // entries of colonies that stopped ticking (or were removed) do not linger
        CACHE.values().removeIf(e -> now < e.computedAt() || now - e.computedAt() > 200);
        List<Threat> list = compute(level, colony, now);
        CACHE.put(colony.id(), new CacheEntry(level.dimension(), now, list));
        if (!list.isEmpty() && level.getEntity(list.get(0).entityId()) instanceof LivingEntity first) {
            colony.runtime.put(LAST_THREAT_KEY, new LastThreat(first.blockPosition(), now));
        }
        return list;
    }

    private static List<Threat> compute(ServerLevel level, Colony colony, long now) {
        List<LivingEntity> attackers = new ArrayList<>();
        if (colony.assaultAttacker != null && now < colony.assaultUntil
                && level.getEntity(colony.assaultAttacker) instanceof LivingEntity attacker && validInside(colony, attacker)) {
            attackers.add(attacker);
        }
        if (colony.reportedThief != null && now < colony.theftUntil) {
            Player thief = level.getPlayerByUUID(colony.reportedThief);
            if (thief != null && thief.level() == level && validInside(colony, thief)) attackers.add(thief);
        }
        AABB box = colonyBox(colony);
        List<LivingEntity> friends = new ArrayList<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, LivingEntity::isAlive)) {
            if (!memberOf(level, colony, v)) continue;
            friends.add(v);
            // lastHurtByMob is also set by harmless hits (snowballs, blocked blows): players and
            // non-hostile creatures only become threats through the damage-filtered reportAttack
            LivingEntity attacker = v.getLastHurtByMob();
            if (attacker instanceof Enemy && v.tickCount - v.getLastHurtByMobTimestamp() <= 100 && validInside(colony, attacker)) {
                attackers.add(attacker);
            }
        }
        friends.addAll(level.getEntitiesOfClass(IronGolem.class, box, LivingEntity::isAlive));
        List<LivingEntity> close = new ArrayList<>();
        if (!friends.isEmpty()) {
            for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && m instanceof Enemy && !(m instanceof Creeper))) {
                if (!inColony(colony, mob)) continue;
                LivingEntity target = mob.getTarget();
                if (target != null && friends.contains(target)) {
                    attackers.add(mob);
                } else if (!neutral(mob) && near(mob, friends)) {
                    close.add(mob);
                }
            }
        }
        BlockPos core = colony.core();
        Comparator<LivingEntity> byCore = Comparator.comparingDouble(e -> e.blockPosition().distSqr(core));
        attackers.sort(byCore);
        close.sort(byCore);
        List<Threat> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (LivingEntity e : attackers) {
            if (seen.add(e.getId()) && !isIgnored(colony, e, now)) out.add(new Threat(e.getId(), 0));
        }
        for (LivingEntity e : close) {
            if (seen.add(e.getId()) && !isIgnored(colony, e, now)) out.add(new Threat(e.getId(), 1));
        }
        return out;
    }

    private static boolean near(Mob mob, List<LivingEntity> friends) {
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

    /** The player is the colony's current assault attacker or reported thief. */
    public static boolean isWanted(ServerLevel level, Colony colony, Player player) {
        long now = level.getGameTime();
        UUID id = player.getUUID();
        return (id.equals(colony.assaultAttacker) && now < colony.assaultUntil)
                || (id.equals(colony.reportedThief) && now < colony.theftUntil);
    }

    /** The target is still worth chasing: hostile, inside the colony (with hysteresis), a wanted player. */
    public static boolean keepsTarget(ServerLevel level, Colony colony, LivingEntity target) {
        if (!isValidHostile(target) || !inColony(colony, target, BORDER_MARGIN)) return false;
        return !(target instanceof Player p) || isWanted(level, colony, p);
    }

    /** Last threat position seen by the colony, or null. */
    public static LastThreat lastThreat(Colony colony) {
        return colony.runtime.get(LAST_THREAT_KEY) instanceof LastThreat t ? t : null;
    }

    // ---------------------------------------------------------------- unreachable targets

    /** Leaves an unreachable target alone colony-wide (no alarm, no defenders) for the given ticks. */
    public static void ignore(ServerLevel level, Colony colony, LivingEntity e, int ticks) {
        IGNORED.computeIfAbsent(colony.id(), k -> new HashMap<>()).put(e.getId(), level.getGameTime() + ticks);
        CACHE.remove(colony.id());
    }

    public static boolean isIgnored(Colony colony, LivingEntity e, long now) {
        Map<Integer, Long> map = IGNORED.get(colony.id());
        if (map == null) return false;
        Long until = map.get(e.getId());
        if (until == null) return false;
        if (now < until) return true;
        map.remove(e.getId());
        return false;
    }

    /** Drops the cached state of a colony (call when a colony is removed or merged away). */
    public static void forget(UUID colonyId) {
        CACHE.remove(colonyId);
        IGNORED.remove(colonyId);
    }

    public static void clearCaches() {
        CACHE.clear();
        IGNORED.clear();
        recomputes = 0;
    }

    // ---------------------------------------------------------------- reporting

    /** A living attacker hurt a colony villager/golem: alarm and mobilize (02 §4 reportAttack). */
    public static void reportAttack(ServerLevel level, Colony colony, LivingEntity attacker) {
        if (colony == null || !validInside(colony, attacker)) return;
        long now = level.getGameTime();
        Map<Integer, Long> ignored = IGNORED.get(colony.id());
        if (ignored != null) ignored.remove(attacker.getId());
        CACHE.remove(colony.id());
        colony.assaultAttacker = attacker.getUUID();
        colony.assaultUntil = now + 1200;
        colony.lastThreatTick = now;
        colony.runtime.put(LAST_THREAT_KEY, new LastThreat(attacker.blockPosition(), now));
        mobilize(level, colony, attacker);
        ColonyManager.markDirty(level);
    }

    /** Adds hostility points against a player (theft, griefing, trespass; 02 §4). */
    public static void addPlayerHostility(ServerLevel level, Colony colony, Player player, int points) {
        if (colony == null || player == null || points <= 0 || player.isCreative() || player.isSpectator()) return;
        long now = level.getGameTime();
        CACHE.remove(colony.id());
        colony.reportedThief = player.getUUID();
        colony.theftUntil = Math.max(colony.theftUntil, now + 1200);
        colony.lastThreatTick = now;
        colony.runtime.put(LAST_THREAT_KEY, new LastThreat(player.blockPosition(), now));
        mobilize(level, colony, player);
        ColonyManager.markDirty(level);
    }

    /** Items taken from colony storage: counts them as stolen and raises hostility. */
    public static void reportTheft(ServerLevel level, Colony colony, Player player, int items) {
        if (colony == null || player == null || items <= 0 || player.isCreative() || player.isSpectator()) return;
        colony.stolenItems += items;
        addPlayerHostility(level, colony, player, items);
    }

    /** Sends the nearest few free golems and guards after the attacker; the others keep their own fights. */
    private static void mobilize(ServerLevel level, Colony colony, LivingEntity attacker) {
        AABB box = colonyBox(colony);
        BlockPos target = attacker.blockPosition();
        boolean player = attacker instanceof Player;
        Comparator<LivingEntity> nearest = Comparator.comparingDouble(e -> e.distanceToSqr(attacker));
        List<IronGolem> golems = new ArrayList<>(level.getEntitiesOfClass(IronGolem.class, box, g -> g.isAlive()
                && memberOf(level, colony, g)
                // vanilla never lets player-built golems attack players
                && !(player && g.isPlayerCreated())
                && (g.getTarget() == null || g.getTarget() == attacker || !keepsTarget(level, colony, g.getTarget()))));
        golems.sort(nearest);
        for (IronGolem golem : golems.subList(0, Math.min(MOBILIZE_GOLEMS, golems.size()))) {
            if (player) golem.getPersistentData().putBoolean(MOBILIZED_TAG, true);
            golem.setTarget(attacker);
            WaypointNavigator.travel(level, golem, target, 1.9);
        }
        List<Villager> guards = new ArrayList<>(level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive()
                && colony.roleOf(v.getUUID()) == ColonyRole.WARRIOR && (player || !busy(level, colony, v))));
        guards.sort(nearest);
        for (Villager v : guards.subList(0, Math.min(MOBILIZE_GUARDS, guards.size()))) {
            if (v.isSleeping()) v.stopSleeping();
            v.setTradingPlayer(null);
            v.getBrain().setMemoryWithExpiry(ModRegistries.THREAT_TARGET.get(), attacker, 1200L);
            VillagerUtil.doExclusively(v, Activity.FIGHT);
            WaypointNavigator.travel(level, v, target, 1.15);
        }
    }

    /** The guard already fights a valid target. */
    private static boolean busy(ServerLevel level, Colony colony, Villager v) {
        LivingEntity mem = v.getBrain().getMemory(ModRegistries.THREAT_TARGET.get()).orElse(null);
        return mem != null && keepsTarget(level, colony, mem);
    }
}
