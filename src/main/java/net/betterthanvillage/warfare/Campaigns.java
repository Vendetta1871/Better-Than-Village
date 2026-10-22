package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.SpawnSpots;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.law.PlayerWar;
import net.betterthanvillage.law.Wars;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Order;
import net.betterthanvillage.politics.Relation;
import net.betterthanvillage.world.Caravans;
import net.betterthanvillage.world.Materializer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Campaigns (docs_v2/07-warfare.md §Campaigns): the ruler of a country at war calls the warriors of its villages to
 * arms, leaves a garrison home, marches the army to the enemy domain, builds an outpost near the target and lays
 * siege. Everything runs abstractly while no player is near (an army is a position, battles are rounds of the strength
 * model in {@link Battle}); near a player the soldiers walk and fight as real villagers ({@link SoldierTask}) and their
 * entities are stored in the campaign whenever they leave the loaded world, like caravan leaders.
 */
public final class Campaigns {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** An army within this distance of its goal has arrived. */
    private static final double ARRIVE = 8;
    /** Persistent-data tag of a golem on campaign (its own colony's golem logic leaves it alone). */
    public static final String GOLEM_TAG = "better_than_village_campaign";

    private static final Map<UUID, Campaign> BY_SOLDIER = new HashMap<>();
    private static boolean indexDirty = true;

    private Campaigns() {
    }

    public static void clearCaches() {
        BY_SOLDIER.clear();
        indexDirty = true;
    }

    static ColonyConfig.Warfare cfg() {
        return ColonyConfig.get().warfare;
    }

    static void markIndex() {
        indexDirty = true;
    }

    // ---------------------------------------------------------------- queries

    public static Campaign ofSoldier(ServerLevel level, UUID id) {
        if (id == null) return null;
        if (indexDirty) {
            BY_SOLDIER.clear();
            for (Campaign c : ColonyManager.data(level).campaigns) {
                for (Campaign.Soldier s : c.soldiers) BY_SOLDIER.put(s.id, c);
            }
            indexDirty = false;
        }
        return BY_SOLDIER.get(id);
    }

    /** The villager or golem is on campaign (not back home yet). */
    public static boolean away(ServerLevel level, UUID id) {
        if (ColonyManager.data(level).campaigns.isEmpty()) return false;
        Campaign c = ofSoldier(level, id);
        return c != null && c.phase != Campaign.Phase.HOME;
    }

    /** The home village of a soldier on campaign (membership while far from home), or null. */
    public static Colony homeOf(ServerLevel level, UUID id) {
        if (!away(level, id)) return null;
        Campaign.Soldier s = ofSoldier(level, id).soldier(id);
        return s == null ? null : ColonyManager.data(level).colony(s.home);
    }

    public static boolean isSoldier(ServerLevel level, Villager v) {
        return away(level, v.getUUID());
    }

    /** Campaigns of a country (armies, not night agents). */
    public static List<Campaign> of(ServerLevel level, UUID attacker) {
        List<Campaign> out = new ArrayList<>();
        for (Campaign c : ColonyManager.data(level).campaigns) {
            if (c.attacker.equals(attacker) && c.phase != Campaign.Phase.HOME && c.kind != Campaign.Kind.NIGHT) out.add(c);
        }
        return out;
    }

    /** The campaign laying siege to (or building its outpost against) the village, or null. */
    public static Campaign against(ServerLevel level, UUID colony) {
        for (Campaign c : ColonyManager.data(level).campaigns) {
            if (colony.equals(c.target) && (c.phase == Campaign.Phase.OUTPOST || c.phase == Campaign.Phase.SIEGE)) return c;
        }
        return null;
    }

    /**
     * The entity is a soldier (villager or golem) of an army hostile to the village: its campaign targets the village
     * or its country is at war with the village's country.
     */
    public static boolean enemyOf(ServerLevel level, Colony colony, Entity e) {
        if (colony == null || e == null || ColonyManager.data(level).campaigns.isEmpty()) return false;
        Campaign c = ofSoldier(level, e.getUUID());
        if (c == null || c.phase == Campaign.Phase.HOME || c.kind == Campaign.Kind.PLAYER) return false;
        // A night agent is an enemy once someone awake has seen it.
        if (c.kind == Campaign.Kind.NIGHT) return c.spotted && colony.id().equals(c.target);
        return colony.id().equals(c.target) || (colony.country != null && Diplomacy.atWar(level, c.attacker, colony.country));
    }

    /** A defender the army fights: a warrior or the village golem of a village of the enemy country. */
    public static boolean defenderOf(ServerLevel level, Campaign c, Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (ofSoldier(level, e.getUUID()) == c) return false;
        if (c.kind == Campaign.Kind.PLAYER) return e.getUUID().equals(c.player);
        Colony colony;
        if (e instanceof Villager v) {
            colony = ColonyManager.colonyOf(level, v);
            if (colony == null || colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR) return false;
        } else if (e instanceof IronGolem g) {
            // Whoever fights the army is its enemy (a golem chasing soldiers out of its village, a player's golem).
            if (g.getTarget() != null && ofSoldier(level, g.getTarget().getUUID()) == c) return true;
            if (g.isPlayerCreated() || away(level, g.getUUID())) return false;
            colony = ColonyManager.colonyAt(level, g.blockPosition());
            if (colony == null) {
                for (Colony v : ColonyManager.data(level).colonies()) if (g.getUUID().equals(v.golem)) colony = v;
            }
            if (colony == null) return false;
        } else {
            return false;
        }
        return colony.id().equals(c.target) || (colony.country != null && Diplomacy.atWar(level, c.attacker, colony.country));
    }

    // ---------------------------------------------------------------- launch

    /**
     * The ruler of the attacker sends an army against the defender's most suitable village: near and weak. Returns
     * null when it already has a campaign against that country or cannot raise an army.
     */
    public static Campaign launch(ServerLevel level, UUID attacker, UUID defender) {
        if (!cfg().campaigns || attacker == null || defender == null) return null;
        for (Campaign c : of(level, attacker)) {
            if (defender.equals(c.defender)) return c;
        }
        Colony capital = Countries.capital(level, attacker);
        if (capital == null) return null;
        Colony target = null;
        double best = Double.MAX_VALUE;
        for (Colony v : Countries.villages(level, defender)) {
            double score = Math.sqrt(v.core().distSqr(capital.core())) + 40 * Countries.villageStrength(level, v);
            if (score < best) {
                best = score;
                target = v;
            }
        }
        if (target == null) return null;
        Colony origin = nearest(Countries.villages(level, attacker), target.core());
        Campaign c = new Campaign(UUID.randomUUID(), Campaign.Kind.WAR, attacker, defender);
        c.target = target.id();
        c.origin = origin.id();
        if (!raise(level, c, origin)) return null;
        LOGGER.info("[war] {} sends {} soldiers from {} against {}", Countries.plainName(level, attacker), c.soldiers.size(),
                Countries.villageName(origin), Countries.villageName(target));
        return c;
    }

    /** A campaign against the base of a player at war with the country. */
    public static Campaign launchAgainstPlayer(ServerLevel level, UUID attacker, UUID player, BlockPos base) {
        if (!cfg().campaigns || base == null) return null;
        for (Campaign c : of(level, attacker)) {
            if (player.equals(c.player)) return c;
        }
        Colony origin = nearest(Countries.villages(level, attacker), base);
        if (origin == null) return null;
        Campaign c = new Campaign(UUID.randomUUID(), Campaign.Kind.PLAYER, attacker, null);
        c.player = player;
        c.base = base.immutable();
        c.origin = origin.id();
        if (!raise(level, c, origin)) return null;
        LOGGER.info("[war] {} sends {} soldiers from {} against the base of player {} at {}", Countries.plainName(level, attacker),
                c.soldiers.size(), Countries.villageName(origin), player.toString().substring(0, 8), base.toShortString());
        return c;
    }

    private static Colony nearest(List<Colony> villages, BlockPos pos) {
        Colony best = null;
        double d = Double.MAX_VALUE;
        for (Colony v : villages) {
            double x = v.core().distSqr(pos);
            if (x < d) {
                d = x;
                best = v;
            }
        }
        return best;
    }

    /**
     * Mobilization: from every village that answered the call to arms, the warriors beyond the garrison (and the
     * staging village's golem, if it stands in a loaded village) join; warriors of other villages reach the staging
     * village after the walk there.
     */
    private static boolean raise(ServerLevel level, Campaign c, Colony origin) {
        ColonySavedData data = ColonyManager.data(level);
        long now = level.getGameTime();
        for (Colony v : Countries.villages(level, c.attacker)) {
            if (ignoredCall(level, v)) continue;
            List<UUID> warriors = new ArrayList<>();
            for (UUID id : v.roles.keySet()) {
                if (v.roleOf(id) != ColonyRole.WARRIOR || id.equals(v.elder) || v.isFounderParty(id)) continue;
                if (Caravans.away(level, id) || ofSoldier(level, id) != null) continue;
                Entity e = level.getEntity(id);
                if (e != null && (!e.isAlive() || ((Villager) e).isBaby())) continue;
                warriors.add(id);
            }
            warriors.sort(Comparator.naturalOrder());
            int keep = Math.max(1, (int) Math.ceil(warriors.size() * cfg().garrison_share));
            double dist = Math.sqrt(v.core().distSqr(origin.core()));
            long join = v == origin ? now : now + (long) (dist / cfg().army_blocks_per_hour * 1000);
            for (UUID id : warriors.subList(0, Math.max(0, warriors.size() - keep))) {
                Campaign.Soldier s = new Campaign.Soldier(id, v.id(), false);
                s.cls = v.warriorClasses.getOrDefault(id, WarriorClass.INFANTRY);
                s.gear = level.getEntity(id) instanceof Villager w ? Battle.gearOf(w) : v.warriorGear;
                s.joinAt = join;
                c.soldiers.add(s);
            }
        }
        if (c.soldiers.size() < cfg().min_army) {
            LOGGER.info("[war] {} cannot raise an army ({} soldiers)", Countries.plainName(level, c.attacker), c.soldiers.size());
            return false;
        }
        if (origin.golem != null && level.getEntity(origin.golem) instanceof IronGolem g && g.isAlive() && !g.isPlayerCreated()) {
            Campaign.Soldier s = new Campaign.Soldier(g.getUUID(), origin.id(), true);
            s.hp = g.getHealth() / g.getMaxHealth();
            c.soldiers.add(s);
            g.getPersistentData().putBoolean(GOLEM_TAG, true);
        }
        c.started = c.phaseSince = now;
        c.x = origin.core().getX() + 0.5;
        c.z = origin.core().getZ() + 0.5;
        data.campaigns.add(c);
        markIndex();
        for (Campaign.Soldier s : c.soldiers) {
            if (level.getEntity(s.id) instanceof Villager v) {
                VillagerUtil.stopModTasks(level, v);
                Nav.reset(v);
                v.setPersistenceRequired();
            }
        }
        if (c.defender != null) {
            Relation r = Diplomacy.relation(level, c.attacker, c.defender);
            Relation.Side side = r.side(c.attacker);
            if (side.warriors == 0) side.warriors = warriorsOf(level, c.attacker);
            Relation.Side other = r.side(c.defender);
            if (other.warriors == 0) other.warriors = warriorsOf(level, c.defender);
        }
        ColonyManager.markDirty(level);
        return true;
    }

    /** The village ignored the ruler's call to arms (a disloyal village keeps its warriors home). */
    private static boolean ignoredCall(ServerLevel level, Colony v) {
        for (Order o : ColonyManager.data(level).orders) {
            if (o.kind == Order.Kind.MOBILIZE && o.colony.equals(v.id()) && o.status == Order.Status.IGNORED) return true;
        }
        return false;
    }

    static int warriorsOf(ServerLevel level, UUID country) {
        int n = 0;
        for (Colony v : Countries.villages(level, country)) {
            for (UUID id : v.roles.keySet()) if (v.roleOf(id) == ColonyRole.WARRIOR) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- tick

    public static void tick(ServerLevel level, long now) {
        if (now % 20 != 0) return;
        ColonySavedData data = ColonyManager.data(level);
        if (data.campaigns.isEmpty()) return;
        int hour = Math.max(1, ColonyConfig.get().world.step_ticks / 20);
        for (Campaign c : new ArrayList<>(data.campaigns)) {
            advance(level, c, now);
            if (!data.campaigns.contains(c)) continue;
            if (Math.floorMod(now / 20 + c.id.getLeastSignificantBits(), (long) hour) == 0) step(level, c, now);
        }
    }

    private static void setPhase(ServerLevel level, Campaign c, Campaign.Phase phase) {
        LOGGER.info("[war] campaign {} of {}: {} -> {}", shortId(c), Countries.plainName(level, c.attacker), c.phase.key(), phase.key());
        c.phase = phase;
        c.phaseSince = level.getGameTime();
        for (Campaign.Soldier s : c.soldiers) {
            s.progressTick = 0;
            s.progressDist = Double.MAX_VALUE;
        }
        ColonyManager.markDirty(level);
    }

    /** The army gives up and goes home. */
    public static void retreat(ServerLevel level, Campaign c, String why) {
        if (c.phase == Campaign.Phase.RETURN || c.phase == Campaign.Phase.HOME) return;
        LOGGER.info("[war] campaign {} of {} turns back: {}", shortId(c), Countries.plainName(level, c.attacker), why);
        setPhase(level, c, Campaign.Phase.RETURN);
    }

    private static void advance(ServerLevel level, Campaign c, long now) {
        ColonySavedData data = ColonyManager.data(level);
        if (Countries.get(level, c.attacker) == null || data.colony(c.origin) == null) {
            // The country or its staging village is gone: the army breaks up where it is.
            disband(level, c);
            return;
        }
        if (c.phase.ordinal() < Campaign.Phase.RETURN.ordinal()) {
            String why = invalid(level, c, now);
            if (why != null) retreat(level, c, why);
        }
        // Soldiers in the loaded world, and those leaving it.
        boolean anyLive = false;
        for (Campaign.Soldier s : new ArrayList<>(c.soldiers)) {
            Entity e = level.getEntity(s.id);
            if (e instanceof LivingEntity le && le.isAlive()) {
                s.live = true;
                if (!level.isPositionEntityTicking(e.blockPosition())) {
                    stash(level, c, s, le);
                } else {
                    anyLive = true;
                }
            } else {
                s.live = false;
            }
        }
        c.live = anyLive;
        if (c.soldiers.isEmpty()) {
            finish(level, c, "every soldier is lost");
            return;
        }
        switch (c.phase) {
            case MOBILIZE -> {
                gather(level, c, origin(level, c).core(), now);
                if (now - c.started >= cfg().mobilize_ticks) {
                    c.initialPower = Battle.armyPower(c, now);
                    setPhase(level, c, Campaign.Phase.MARCH);
                }
            }
            case MARCH -> {
                double[] goal = staging(level, c);
                if (march(level, c, goal[0], goal[1], now)) {
                    if (c.kind == Campaign.Kind.NIGHT) {
                        setPhase(level, c, Campaign.Phase.SIEGE);
                    } else {
                        c.outpost = BlockPos.containing(goal[0], 0, goal[1]);
                        setPhase(level, c, Campaign.Phase.OUTPOST);
                    }
                }
            }
            case OUTPOST -> {
                BlockPos at = outpostSurface(level, c);
                gather(level, c, at, now);
                if (c.live && at != null) Outposts.tickLive(level, c, at);
                if (c.outpostBuilt) setPhase(level, c, Campaign.Phase.SIEGE);
            }
            case SIEGE -> {
                BlockPos at = outpostSurface(level, c);
                if (c.kind == Campaign.Kind.NIGHT) {
                    if (c.live) NightOps.tickLive(level, c, now);
                    break;
                }
                // The rest of the outpost goes up while the siege begins.
                if (c.live && at != null && !c.outpostPlaced) Outposts.tickLive(level, c, at);
                if (c.outpostPlaced && at != null && Outposts.destroyed(level, c, at)) {
                    Outposts.lost(level, c);
                } else if (c.live) {
                    Siege.tickLive(level, c, now);
                }
            }
            case RETURN -> {
                BlockPos home = origin(level, c).core();
                if (march(level, c, home.getX() + 0.5, home.getZ() + 0.5, now)) arriveHome(level, c);
            }
            case HOME -> comeHome(level, c);
        }
        if (data.campaigns.contains(c) && c.phase != Campaign.Phase.HOME) materialize(level, c, now);
    }

    /** Why the campaign cannot go on (target gone, peace, the player's war over), or null. */
    private static String invalid(ServerLevel level, Campaign c, long now) {
        if (c.kind == Campaign.Kind.NIGHT) {
            if (c.player != null) return Wars.war(level, c.attacker, c.player) == null ? "the war with the player is over" : null;
            Colony target = ColonyManager.data(level).colony(c.target);
            return target == null || c.attacker.equals(target.country) ? "the target is gone" : null;
        }
        if (c.kind == Campaign.Kind.PLAYER) {
            PlayerWar w = Wars.war(level, c.attacker, c.player);
            if (w == null) return "the war with the player is over";
            if (w.weary) return "the country is weary of the war";
            if (now - c.started > cfg().player_campaign_days * 24000L) return "the campaign took too long";
            return null;
        }
        Colony target = ColonyManager.data(level).colony(c.target);
        if (target == null) return "the target is gone";
        if (c.attacker.equals(target.country)) return "the target is taken";
        if (!Diplomacy.atWar(level, c.attacker, target.country)) return "peace";
        return null;
    }

    static Colony origin(ServerLevel level, Campaign c) {
        return ColonyManager.data(level).colony(c.origin);
    }

    /** Where the army goes to build its outpost: outside the target's outermost ring, on the side it comes from. */
    static double[] staging(ServerLevel level, Campaign c) {
        BlockPos goal;
        int ring;
        Colony target = c.target == null ? null : ColonyManager.data(level).colony(c.target);
        if (target == null) {
            goal = c.base;
            ring = 8;
        } else {
            goal = target.core();
            ring = target.fortifications.radius > 0 ? target.fortifications.radius : Math.min(target.radius(), 96);
        }
        if (c.kind == Campaign.Kind.NIGHT) {
            // An agent goes to the edge of the village (or the base) and sneaks in from there.
            Colony origin = origin(level, c);
            double dx = origin.core().getX() - goal.getX(), dz = origin.core().getZ() - goal.getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            double d = Math.min(len, ring + 6);
            return new double[]{goal.getX() + 0.5 + dx / len * d, goal.getZ() + 0.5 + dz / len * d};
        }
        Colony origin = origin(level, c);
        double dx = origin.core().getX() - goal.getX(), dz = origin.core().getZ() - goal.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double d = Math.min(len, ring + cfg().outpost_distance);
        return new double[]{goal.getX() + 0.5 + dx / len * d, goal.getZ() + 0.5 + dz / len * d};
    }

    /** The outpost's ground position, or null while its chunk is not loaded. */
    static BlockPos outpostSurface(ServerLevel level, Campaign c) {
        if (c.outpost == null) return null;
        if (!level.hasChunk(c.outpost.getX() >> 4, c.outpost.getZ() >> 4)) return null;
        return surface(level, c.outpost.getX(), c.outpost.getZ());
    }

    static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    // ---------------------------------------------------------------- movement

    /** Live soldiers walk to the point; the army waits there (mobilization, outpost). */
    private static void gather(ServerLevel level, Campaign c, BlockPos at, long now) {
        if (at == null) return;
        for (Campaign.Soldier s : c.present(now)) {
            if (!s.live || !(level.getEntity(s.id) instanceof Mob m)) continue;
            if (m instanceof Villager v && VillagerUtil.isRunning(v, SoldierTask.class)) continue;
            if (m.distanceToSqr(at.getX() + 0.5, m.getY(), at.getZ() + 0.5) > 64) walk(level, m, at, 0.7);
        }
    }

    /**
     * The army moves toward the goal. A live soldier leads and the others follow it; with nobody live the army is a
     * position moving at the army speed. Returns true on arrival.
     */
    private static boolean march(ServerLevel level, Campaign c, double gx, double gz, long now) {
        Mob leader = null;
        Campaign.Soldier leaderRecord = null;
        for (Campaign.Soldier s : c.present(now)) {
            if (s.live && !s.golem && level.getEntity(s.id) instanceof Villager v) {
                leader = v;
                leaderRecord = s;
                break;
            }
        }
        if (leader != null) {
            c.x = leader.getX();
            c.z = leader.getZ();
            double dist = Math.hypot(gx - c.x, gz - c.z);
            if (dist <= ARRIVE) return true;
            steer(level, leaderRecord, leader, c.x, c.z, gx, gz, dist, now);
            for (Campaign.Soldier s : c.present(now)) {
                if (s == leaderRecord || !s.live || !(level.getEntity(s.id) instanceof Mob m)) continue;
                if (m instanceof Villager v && VillagerUtil.isRunning(v, SoldierTask.class)) continue;
                if (m.distanceToSqr(leader) > 36) walk(level, m, leader.blockPosition(), 0.75);
            }
            return false;
        }
        double step = cfg().army_blocks_per_hour * 20.0 / Math.max(1, ColonyConfig.get().world.step_ticks);
        double dist = Math.hypot(gx - c.x, gz - c.z);
        if (dist <= step + ARRIVE) {
            c.x = gx;
            c.z = gz;
            return true;
        }
        c.x += (gx - c.x) / dist * step;
        c.z += (gz - c.z) / dist * step;
        return false;
    }

    /** Far goals are reached by waypoints (the pathfinder only plans within the follow range), near ones directly. */
    static void go(ServerLevel level, Villager v, BlockPos to, double speed) {
        if (v.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5) > 32 * 32) {
            net.betterthanvillage.ai.nav.WaypointNavigator.travel(level, v, to, speed);
        } else {
            Nav.approach(level, v, to, speed);
        }
    }

    private static void walk(ServerLevel level, Mob m, BlockPos to, double speed) {
        if (m instanceof Villager v) {
            Nav.approach(level, v, to, speed);
        } else if (net.betterthanvillage.colony.PathBudget.tryAcquire(level)) {
            m.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
        }
    }

    /** A leader walks toward the goal by waypoints; when it makes no progress it is helped over the obstacle. */
    private static void steer(ServerLevel level, Campaign.Soldier s, Mob v, double x, double z, double gx, double gz, double dist, long now) {
        double ahead = Math.min(20, dist);
        BlockPos wp = surface(level, (int) Math.floor(x + (gx - x) / dist * ahead), (int) Math.floor(z + (gz - z) / dist * ahead));
        walk(level, v, wp, 0.7);
        if (now - s.progressTick >= 400) {
            if (dist > s.progressDist - 4 && s.progressTick != 0) {
                double hop = Math.min(12, dist);
                BlockPos to = surface(level, (int) Math.floor(x + (gx - x) / dist * hop), (int) Math.floor(z + (gz - z) / dist * hop));
                v.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                Nav.reset(v);
            }
            s.progressTick = now;
            s.progressDist = dist;
        }
    }

    // ---------------------------------------------------------------- materialization

    private static void stash(ServerLevel level, Campaign c, Campaign.Soldier s, LivingEntity e) {
        s.hp = Math.max(0.05, e.getHealth() / e.getMaxHealth());
        if (e instanceof Villager v) s.gear = Battle.gearOf(v);
        CompoundTag tag = Materializer.stash(e);
        if (tag == null) return;
        s.data = tag;
        s.live = false;
        ColonyManager.markDirty(level);
    }

    /** Soldiers not in the world walk again where the army stands in entity-ticking chunks. */
    private static void materialize(ServerLevel level, Campaign c, long now) {
        BlockPos at = BlockPos.containing(c.x, 64, c.z);
        if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4) || !level.isPositionEntityTicking(at)) return;
        int i = 0;
        for (Campaign.Soldier s : c.present(now)) {
            i++;
            if (s.live || level.getEntity(s.id) != null) continue;
            if (s.golem && s.data == null) continue;
            BlockPos pos = surface(level, at.getX() + (i % 5) - 2, at.getZ() + (i / 5) - 2);
            Entity e;
            if (s.golem) {
                e = Materializer.spawn(level, s.data, pos);
                if (e instanceof IronGolem g) g.getPersistentData().putBoolean(GOLEM_TAG, true);
            } else {
                boolean standIn = s.data == null;
                e = Materializer.spawnVillager(level, s.data, s.id, pos, ColonyManager.data(level).colony(s.home));
                if (e != null && standIn) {
                    s.standIn = true;
                    // The stand-in carries the gear the warrior has at home.
                    equip((Villager) e, s);
                }
            }
            if (e == null) continue;
            if (e instanceof LivingEntity le) le.setHealth(Math.max(1, (float) (le.getMaxHealth() * s.hp)));
            s.data = null;
            s.live = true;
            c.live = true;
            ColonyManager.markDirty(level);
        }
    }

    /** Gear for a stand-in by the soldier's gear tier and class. */
    static void equip(Villager v, Campaign.Soldier s) {
        net.minecraft.world.item.Item sword = s.gear >= 3 ? net.minecraft.world.item.Items.DIAMOND_SWORD
                : s.gear >= 2 ? net.minecraft.world.item.Items.IRON_SWORD : s.gear >= 1 ? net.minecraft.world.item.Items.STONE_SWORD
                : net.minecraft.world.item.Items.WOODEN_SWORD;
        if (s.cls == WarriorClass.RANGED) {
            v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.BOW));
            v.getInventory().addItem(new ItemStack(sword));
        } else {
            v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(sword));
        }
        double armor = s.gear - Math.floor(s.gear);
        if (armor >= 0.3) v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
        if (armor >= 0.6) v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
        v.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0);
        v.setDropChance(net.minecraft.world.entity.EquipmentSlot.CHEST, 0);
        v.setDropChance(net.minecraft.world.entity.EquipmentSlot.LEGS, 0);
    }

    /**
     * Join listener: a soldier's entity that loads away from its army (with its old chunk) is kept out of the world;
     * its data is stored in the campaign if it has none. Returns true to cancel the join.
     */
    public static boolean onJoin(ServerLevel level, Entity e) {
        if (Materializer.spawning() || ColonyManager.data(level).campaigns.isEmpty()) return false;
        Campaign c = ofSoldier(level, e.getUUID());
        if (c == null || c.phase == Campaign.Phase.HOME) return false;
        Campaign.Soldier s = c.soldier(e.getUUID());
        if (s == null) return false;
        s.standIn = false;
        Entity present = level.getEntity(e.getUUID());
        if (present != null && present != e) return true;
        if (s.data == null) {
            CompoundTag tag = new CompoundTag();
            if (e.saveAsPassenger(tag)) s.data = tag;
        }
        s.live = false;
        ColonyManager.markDirty(level);
        return true;
    }

    // ---------------------------------------------------------------- the hourly step

    /** Debug: every campaign takes its hourly step now. */
    public static int forceStep(ServerLevel level) {
        int n = 0;
        for (Campaign c : new ArrayList<>(ColonyManager.data(level).campaigns)) {
            step(level, c, level.getGameTime());
            n++;
        }
        return n;
    }

    private static void step(ServerLevel level, Campaign c, long now) {
        switch (c.phase) {
            case OUTPOST -> Outposts.step(level, c, now);
            case SIEGE -> {
                if (c.kind == Campaign.Kind.NIGHT) NightOps.step(level, c, now);
                if (c.kind == Campaign.Kind.WAR) {
                    Colony target = ColonyManager.data(level).colony(c.target);
                    if (target != null && c.defender != null) Diplomacy.relation(level, c.attacker, c.defender).side(c.defender).besiegedHours++;
                    if (!c.live && target != null) Battle.round(level, c, target, now);
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- losses

    /** A villager or golem died: a soldier leaves its campaign, a defender's death counts for the war. */
    public static void onDeath(ServerLevel level, LivingEntity dead, Entity killer) {
        ColonySavedData data = ColonyManager.data(level);
        if (data.campaigns.isEmpty()) return;
        Campaign c = ofSoldier(level, dead.getUUID());
        if (c != null) {
            Campaign.Soldier s = c.soldier(dead.getUUID());
            if (s != null) {
                LOGGER.info("[war] soldier {} of {} killed by {}", s.id.toString().substring(0, 8), shortId(c),
                        killer == null ? "-" : killer.getType().toShortString());
                // A stand-in died: the original, still in its unloaded home, is dropped when it loads.
                if (s.standIn) data.fallen.add(s.id);
                // A night agent killed by the target's people (not by a zombie on the way) was caught.
                c.spotted = c.kind != Campaign.Kind.NIGHT || caughtBy(level, c, killer);
                soldierLost(level, c, s);
            }
            return;
        }
        Campaign by = killer == null ? null : ofSoldier(level, killer.getUUID());
        if (by != null && defenderOf(level, by, dead)) defenderLost(level, by, dead.getUUID());
    }

    /** The killer is one of the target's people, its golem or the target player. */
    private static boolean caughtBy(ServerLevel level, Campaign c, Entity killer) {
        if (killer == null) return false;
        if (killer.getUUID().equals(c.player)) return true;
        Colony k = killer instanceof Villager v ? ColonyManager.colonyOf(level, v)
                : killer instanceof IronGolem ? ColonyManager.colonyAt(level, killer.blockPosition()) : null;
        return k != null && k.id().equals(c.target);
    }

    static void soldierLost(ServerLevel level, Campaign c, Campaign.Soldier s) {
        if (c.kind == Campaign.Kind.NIGHT && c.spotted) NightOps.caught(level, c);
        c.soldiers.remove(s);
        c.lossesAttacker++;
        markIndex();
        if (c.defender != null) Diplomacy.relation(level, c.attacker, c.defender).side(c.attacker).losses++;
        else if (c.player != null) {
            PlayerWar w = Wars.war(level, c.attacker, c.player);
            if (w != null) w.losses++;
        }
        Colony home = ColonyManager.data(level).colony(s.home);
        if (s.golem && home != null && s.id.equals(home.golem)) home.golem = null;
        ColonyManager.markDirty(level);
        LOGGER.info("[war] campaign {} lost a soldier ({} left)", shortId(c), c.soldiers.size());
    }

    static void defenderLost(ServerLevel level, Campaign c, UUID id) {
        c.lossesDefender++;
        LOGGER.info("[war] campaign {} killed a defender ({} so far)", shortId(c), c.lossesDefender);
        c.defenderHp.remove(id);
        if (c.defender != null) Diplomacy.relation(level, c.attacker, c.defender).side(c.defender).losses++;
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- the way home

    /** The army is back at its staging village: soldiers of that village are home, the others wait for theirs. */
    private static void arriveHome(ServerLevel level, Campaign c) {
        Colony origin = origin(level, c);
        if (c.kind == Campaign.Kind.NIGHT) NightOps.share(level, c, origin);
        if (!c.loot.isEmpty()) {
            List<ItemStack> goods = new ArrayList<>();
            for (int i = 0; i < c.loot.getContainerSize(); i++) {
                ItemStack s = c.loot.removeItemNoUpdate(i);
                if (!s.isEmpty()) goods.add(s);
            }
            int carried = Chests.total(goods);
            List<ItemStack> rest = Chests.put(ColonyStorage.treasury(level, origin), goods);
            for (ItemStack s : rest) c.loot.addItem(s);
            LOGGER.info("[war] campaign {} brings {} items of loot into the treasury of {}", shortId(c), carried - Chests.total(rest),
                    net.betterthanvillage.politics.Countries.villageName(origin));
        }
        for (Campaign.Soldier s : new ArrayList<>(c.soldiers)) {
            Entity e = level.getEntity(s.id);
            boolean live = e instanceof LivingEntity le && le.isAlive();
            if (s.standIn) {
                // The original never left its home chunk: the stand-in is no longer needed.
                if (live) Materializer.discard(e);
                c.soldiers.remove(s);
            } else if (live && s.home.equals(c.origin)) {
                release(level, s, e);
                c.soldiers.remove(s);
            } else if (live) {
                stash(level, c, s, (LivingEntity) e);
            }
        }
        markIndex();
        setPhase(level, c, Campaign.Phase.HOME);
        comeHome(level, c);
    }

    private static void release(ServerLevel level, Campaign.Soldier s, Entity e) {
        if (e instanceof IronGolem g) g.getPersistentData().remove(GOLEM_TAG);
        if (e instanceof Villager v) Nav.reset(v);
    }

    /** Soldiers waiting for their village walk there again once it is live. */
    private static void comeHome(ServerLevel level, Campaign c) {
        ColonySavedData data = ColonyManager.data(level);
        for (Campaign.Soldier s : new ArrayList<>(c.soldiers)) {
            Colony home = data.colony(s.home);
            if (home == null || s.data == null) {
                c.soldiers.remove(s);
                markIndex();
                continue;
            }
            if (!ColonyManager.isLive(level, home)) continue;
            BlockPos spot = SpawnSpots.find(level, home, level.getGameTime());
            if (spot == null) continue;
            Entity e = s.golem ? Materializer.spawn(level, s.data, spot) : Materializer.spawnVillager(level, s.data, s.id, spot, home);
            if (e == null) continue;
            release(level, s, e);
            c.soldiers.remove(s);
            markIndex();
        }
        if (c.soldiers.isEmpty()) {
            data.campaigns.remove(c);
            markIndex();
            ColonyManager.markDirty(level);
            LOGGER.info("[war] campaign {} of {} is over", shortId(c), Countries.plainName(level, c.attacker));
        }
    }

    /** The campaign ends where it is (no soldier left, or the country is gone). */
    private static void finish(ServerLevel level, Campaign c, String why) {
        ColonyManager.data(level).campaigns.remove(c);
        markIndex();
        ColonyManager.markDirty(level);
        LOGGER.info("[war] campaign {} of {} ends: {}", shortId(c), Countries.plainName(level, c.attacker), why);
    }

    private static void disband(ServerLevel level, Campaign c) {
        for (Campaign.Soldier s : c.soldiers) {
            Entity e = level.getEntity(s.id);
            if (e != null) release(level, s, e);
        }
        finish(level, c, "the army breaks up");
    }

    // ---------------------------------------------------------------- status

    static String shortId(Campaign c) {
        return c.id.toString().substring(0, 8);
    }

    public static List<String> status(ServerLevel level) {
        List<String> out = new ArrayList<>();
        long now = level.getGameTime();
        for (Campaign c : ColonyManager.data(level).campaigns) {
            Colony target = c.target == null ? null : ColonyManager.data(level).colony(c.target);
            int live = 0, stored = 0;
            for (Campaign.Soldier s : c.soldiers) {
                if (s.live) live++;
                else if (s.data != null) stored++;
            }
            out.add("campaign " + shortId(c) + " " + c.kind.key() + " of " + Countries.plainName(level, c.attacker) + " against "
                    + (target != null ? Countries.villageName(target) : c.base != null ? "base " + c.base.toShortString() : "-")
                    + ": " + c.phase.key() + (c.live ? " live" : " abstract") + " at (" + (int) c.x + ", " + (int) c.z + "), soldiers "
                    + c.present(now).size() + "/" + c.soldiers.size() + " (" + live + " live, " + stored + " stored), power "
                    + Math.round(Battle.armyPower(c, now) * 10) / 10.0 + "/" + Math.round(c.initialPower * 10) / 10.0 + ", outpost "
                    + (c.outpost == null ? "-" : c.outpost.getX() + "," + c.outpost.getZ() + " " + c.outpostWork + "/" + cfg().outpost_work
                    + (c.outpostBuilt ? " built" : "") + (c.outpostPlaced ? " placed" : "") + " lost " + c.outpostLosses)
                    + ", rings broken " + c.ringsBroken + " (ring " + Math.round(c.ring * 100) + "%), losses " + c.lossesAttacker + "/"
                    + c.lossesDefender);
        }
        return out;
    }
}
