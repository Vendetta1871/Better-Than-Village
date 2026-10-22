package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Values;
import net.betterthanvillage.law.PlayerWar;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Relation;
import net.betterthanvillage.world.Caravans;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Night operations (docs_v2/07-warfare.md §Night operations): at dusk the elder of a village may send an agent (a
 * warrior or its poorest villager, who gets a share of the loot) against a neighbouring village of another country
 * or a player's base: a theft raid, arson, or killing a sleeper. Which operations are allowed depends on the
 * relations; arson and killing in peacetime only as revenge for a caught agent. An agent is a campaign of one
 * ({@link Campaign.Kind#NIGHT}): abstractly the outcome is rolled against the target's night watch; near a player the
 * agent sneaks in ({@link NightOpTask}) and is attacked once someone awake sees it. A caught agent is an incident.
 */
public final class NightOps {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Emeralds a thief takes at most. */
    static final int THEFT_EMERALDS = 16;
    /** Share of the loot the poor agent keeps. */
    private static final double AGENT_SHARE = 0.25;
    /** Days a victim may take revenge after catching an agent. */
    private static final long REVENGE_DAYS = 5;
    private static long lastNight = Long.MIN_VALUE;

    public enum Op {
        THEFT, ARSON, KILL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Op byKey(String key) {
            for (Op o : values()) {
                if (o.key().equals(key)) return o;
            }
            return THEFT;
        }
    }

    private NightOps() {
    }

    public static void clearCaches() {
        lastNight = Long.MIN_VALUE;
    }

    private static ColonyConfig.Warfare cfg() {
        return ColonyConfig.get().warfare;
    }

    // ---------------------------------------------------------------- dusk

    public static void tick(ServerLevel level, long now) {
        if (now % 20 != 0 || !cfg().campaigns) return;
        long t = Math.floorMod(level.getDayTime(), 24000L);
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        if (t < 12500 || t >= 13500 || day == lastNight) return;
        lastNight = day;
        plan(level, now, day, false);
    }

    /** Debug: the elders decide on their night operations now (every allowed one is sent). */
    public static int force(ServerLevel level) {
        return force(level, null);
    }

    /** Debug: as force, with the operation chosen where it is allowed (null: any). */
    public static int force(ServerLevel level, Op only) {
        return plan(level, level.getGameTime(), Math.floorDiv(level.getDayTime(), 24000L), true, only);
    }

    private record Candidate(Colony village, BlockPos base, UUID player, UUID country, EnumSet<Op> ops, boolean war) {
    }

    private static int plan(ServerLevel level, long now, long day, boolean forced) {
        return plan(level, now, day, forced, null);
    }

    private static int plan(ServerLevel level, long now, long day, boolean forced, Op only) {
        ColonySavedData data = ColonyManager.data(level);
        Random rnd = new Random(level.getSeed() ^ day * 0x9E3779B97F4A7C15L);
        int sent = 0;
        int range = cfg().night_ops_range;
        for (Colony home : new ArrayList<>(data.colonies())) {
            if (home.country == null || home.elder == null || !home.householdsSettled || busy(level, home)) continue;
            List<Candidate> candidates = new ArrayList<>();
            for (Colony other : data.colonies()) {
                if (other == home || other.country == null || other.country.equals(home.country)) continue;
                if (other.core().distSqr(home.core()) > (double) range * range) continue;
                EnumSet<Op> ops = allowed(level, home.country, other.country);
                if (!ops.isEmpty()) candidates.add(new Candidate(other, null, null, other.country, ops,
                        Diplomacy.state(level, home.country, other.country) == Relation.State.WAR));
            }
            for (PlayerWar w : data.wars) {
                if (!w.country.equals(home.country)) continue;
                PlayerIntel in = data.intel.get(w.player);
                if (in == null || in.base == null || in.base.distSqr(home.core()) > (double) range * range) continue;
                candidates.add(new Candidate(null, in.base, w.player, null, EnumSet.allOf(Op.class), true));
            }
            if (candidates.isEmpty()) continue;
            Candidate pick = candidates.get(rnd.nextInt(candidates.size()));
            double chance = cfg().night_ops_chance * (pick.war() ? 2 : 1);
            if (!forced && rnd.nextDouble() >= chance) continue;
            UUID agent = pickAgent(level, home);
            if (agent == null) continue;
            List<Op> ops = new ArrayList<>(pick.ops());
            if (only != null) {
                if (!ops.contains(only)) continue;
                ops = List.of(only);
            }
            Op op = ops.get(rnd.nextInt(ops.size()));
            launch(level, home, pick, op, agent, now);
            sent++;
        }
        return sent;
    }

    /** The village already has an agent out. */
    private static boolean busy(ServerLevel level, Colony home) {
        for (Campaign c : ColonyManager.data(level).campaigns) {
            if (c.kind == Campaign.Kind.NIGHT && home.id().equals(c.origin)) return true;
        }
        return false;
    }

    /**
     * Operations a country may send against another: a theft raid with bad relations; arson and killing in
     * hostility or war, or as revenge for a caught agent.
     */
    public static EnumSet<Op> allowed(ServerLevel level, UUID from, UUID against) {
        Relation.State state = Diplomacy.state(level, from, against);
        EnumSet<Op> ops = EnumSet.noneOf(Op.class);
        if (state == Relation.State.WAR || state == Relation.State.HOSTILITY) return EnumSet.allOf(Op.class);
        if (state == Relation.State.ALLIANCE) return ops;
        Relation r = ColonyManager.data(level).relations.get(Diplomacy.key(from, against));
        if (r != null && r.score <= -20) ops.add(Op.THEFT);
        if (revenge(level, from, against)) {
            ops.add(Op.ARSON);
            ops.add(Op.KILL);
        }
        return ops;
    }

    private static boolean revenge(ServerLevel level, UUID victim, UUID culprit) {
        Long until = ColonyManager.data(level).revenge.get(victim + "|" + culprit);
        return until != null && level.getGameTime() < until;
    }

    /** The agent: the poorest idle villager (it gets a share of the loot), else a warrior the village can spare. */
    private static UUID pickAgent(ServerLevel level, Colony home) {
        UUID poor = null, warrior = null;
        int warriors = 0;
        for (Household h : home.households.values()) {
            UUID id = h.owner;
            if (id.equals(home.elder) || home.isFounderParty(id) || Campaigns.away(level, id) || Caravans.away(level, id)) continue;
            Entity e = level.getEntity(id);
            if (e != null && (!e.isAlive() || ((Villager) e).isBaby())) continue;
            if (home.roleOf(id) == ColonyRole.WARRIOR) {
                warriors++;
                if (warrior == null) warrior = id;
            } else if (h.idlePoor && poor == null) {
                poor = id;
            }
        }
        if (poor != null) return poor;
        return warriors >= 2 ? warrior : null;
    }

    private static void launch(ServerLevel level, Colony home, Candidate pick, Op op, UUID agent, long now) {
        Campaign c = new Campaign(UUID.randomUUID(), Campaign.Kind.NIGHT, home.country, pick.country());
        c.origin = home.id();
        c.op = op;
        if (pick.village() != null) c.target = pick.village().id();
        else {
            c.player = pick.player();
            c.base = pick.base();
        }
        Campaign.Soldier s = new Campaign.Soldier(agent, home.id(), false);
        s.cls = home.warriorClasses.getOrDefault(agent, WarriorClass.INFANTRY);
        s.gear = level.getEntity(agent) instanceof Villager v ? Battle.gearOf(v) : home.roleOf(agent) == ColonyRole.WARRIOR ? home.warriorGear : 0;
        c.soldiers.add(s);
        c.started = c.phaseSince = now;
        c.phase = Campaign.Phase.MARCH;
        c.x = home.core().getX() + 0.5;
        c.z = home.core().getZ() + 0.5;
        ColonyManager.data(level).campaigns.add(c);
        Campaigns.markIndex();
        if (level.getEntity(agent) instanceof Villager v) {
            net.betterthanvillage.ai.VillagerUtil.stopModTasks(level, v);
            net.betterthanvillage.ai.nav.Nav.reset(v);
            v.setPersistenceRequired();
        }
        ColonyManager.markDirty(level);
        LOGGER.info("[night] {} sends {} on a {} against {}", Countries.villageName(home), agent.toString().substring(0, 8), op.key(),
                pick.village() != null ? Countries.villageName(pick.village()) : "the base at " + pick.base().toShortString());
    }

    // ---------------------------------------------------------------- the operation

    /** The night is over: an operation not carried out by dawn fails, and the agent goes home. */
    static boolean dawn(ServerLevel level) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        return t >= 23000 || t < 12000;
    }

    /** Abstract: the outcome is rolled from the agent's skill against the target's night watch. */
    static void step(ServerLevel level, Campaign c, long now) {
        if (c.opDone || c.live) return;
        Campaign.Soldier agent = c.soldiers.isEmpty() ? null : c.soldiers.get(0);
        if (agent == null) return;
        Random rnd = new Random(c.id.getLeastSignificantBits() ^ now);
        Colony target = c.target == null ? null : ColonyManager.data(level).colony(c.target);
        if (c.kind == Campaign.Kind.NIGHT && target == null) {
            // A player's base is never a record: Campaigns holds its area loaded and the agent works there live.
            return;
        }
        double skill = 2 + agent.gear * 0.5;
        int watch = 1;
        for (UUID id : target.nightWatch) if (!Campaigns.away(level, id)) watch++;
        if (Battle.golemStands(level, target)) watch++;
        if (target.watchPost != null) watch++;
        double success = skill / (skill + watch);
        double roll = rnd.nextDouble();
        if (roll < success) {
            apply(level, c, target, rnd);
            finish(level, c, "done");
        } else if (roll < success + (1 - success) * 0.6) {
            LOGGER.info("[night] the agent of {} is caught in {}", Countries.plainName(level, c.attacker), Countries.villageName(target));
            c.spotted = true;
            Casualties.killSoldier(level, c, agent);
        } else {
            finish(level, c, "the agent got nothing and slipped away");
        }
    }

    /** The success of an abstract operation, written to the ledgers. */
    private static void apply(ServerLevel level, Campaign c, Colony target, Random rnd) {
        switch (c.op) {
            case THEFT -> {
                int n = steal(ColonyStorage.treasury(level, target), c);
                LOGGER.info("[night] a thief of {} steals {} from the treasury of {}", Countries.plainName(level, c.attacker), n,
                        Countries.villageName(target));
            }
            case ARSON -> {
                Household h = victimHouse(target, rnd);
                int burnt = 0;
                // The house itself burns when the village loads (the fire needs the world).
                BlockPos house = h == null ? null : h.bed != null ? h.bed : h.chests.isEmpty() ? null : h.chests.get(0);
                if (house != null && target.pendingFires.size() < 64) target.pendingFires.add(house);
                if (h != null) {
                    for (ColonyStorage.Slot slot : ColonyStorage.usable(level, h)) {
                        for (int i = 0; i < slot.container().getContainerSize(); i++) {
                            ItemStack s = slot.container().getItem(i);
                            if (s.isEmpty() || s.is(Items.EMERALD) || rnd.nextBoolean()) continue;
                            burnt += s.getCount();
                            slot.container().setItem(i, ItemStack.EMPTY);
                        }
                        slot.container().setChanged();
                    }
                }
                LOGGER.info("[night] an agent of {} sets a house of {} on fire ({} items burnt)", Countries.plainName(level, c.attacker),
                        Countries.villageName(target), burnt);
            }
            case KILL -> {
                UUID victim = victim(level, target);
                if (victim != null) {
                    Casualties.killVillager(level, victim);
                    LOGGER.info("[night] an agent of {} kills {} in {}", Countries.plainName(level, c.attacker), victim.toString().substring(0, 8),
                            Countries.villageName(target));
                }
            }
        }
    }

    /** Fires set by abstract operations break out once the village is live: a few flames by each burnt house. */
    public static void applyFires(ServerLevel level, Colony colony) {
        if (colony.pendingFires.isEmpty()) return;
        for (var it = colony.pendingFires.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            if (!level.isLoaded(pos)) continue;
            int lit = 0;
            for (int i = 0; i < 3; i++) if (PlayerRaid.arson(level, pos.offset(i * 2 - 2, 0, 0))) lit++;
            it.remove();
            LOGGER.info("[night] the fire set in {} breaks out at {} ({} flames)", Countries.villageName(colony), pos.toShortString(), lit);
        }
        ColonyManager.markDirty(level);
    }

    /** Emeralds (up to the cap), else the most valuable goods, into the agent's loot. Returns the items taken. */
    static int steal(List<ColonyStorage.Slot> from, Campaign c) {
        List<ItemStack> taken = Chests.take(from, s -> s.is(Items.EMERALD), THEFT_EMERALDS);
        if (taken.isEmpty()) {
            taken = Chests.take(from, s -> s.is(Items.DIAMOND) || s.is(Items.IRON_INGOT) || s.is(Items.GOLD_INGOT) || Values.base(s) >= 1, 32);
        }
        int n = 0;
        for (ItemStack s : taken) {
            n += s.getCount();
            ItemStack rest = c.loot.addItem(s);
            if (!rest.isEmpty()) Chests.put(from, List.of(rest));
        }
        return n;
    }

    /** The house to burn: a wooden house of an ordinary household first, the elder's otherwise. */
    private static Household victimHouse(Colony target, Random rnd) {
        List<Household> houses = new ArrayList<>();
        for (Household h : target.households.values()) {
            if (!h.owner.equals(target.elder) && h.hasChest()) houses.add(h);
        }
        if (!houses.isEmpty()) return houses.get(rnd.nextInt(houses.size()));
        return target.elder == null ? null : target.households.get(target.elder);
    }

    /** Who dies: a sleeping warrior of the day shift first, then the elder, then another adult. */
    static UUID victim(ServerLevel level, Colony target) {
        for (UUID id : target.roles.keySet()) {
            if (target.roleOf(id) == ColonyRole.WARRIOR && !target.nightWatch.contains(id) && !Campaigns.away(level, id)) return id;
        }
        if (target.elder != null) return target.elder;
        for (Household h : target.households.values()) return h.owner;
        return null;
    }

    private static void finish(ServerLevel level, Campaign c, String why) {
        c.opDone = true;
        Campaigns.retreat(level, c, "night operation: " + why);
    }

    /** Live: the agent is spotted by anyone awake who sees it; at dawn an unfinished operation fails. */
    static void tickLive(ServerLevel level, Campaign c, long now) {
        if (!c.opDone && dawn(level)) {
            finish(level, c, "dawn");
            return;
        }
        if (c.spotted || c.soldiers.isEmpty() || !(level.getEntity(c.soldiers.get(0).id) instanceof Villager agent)) return;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, agent.getBoundingBox().inflate(16, 6, 16), LivingEntity::isAlive)) {
            if (e == agent || !agent.hasLineOfSight(e)) continue;
            boolean watcher;
            if (e instanceof Villager v) {
                Colony vc = ColonyManager.colonyOf(level, v);
                watcher = !v.isSleeping() && vc != null && vc.id().equals(c.target);
            } else if (e instanceof Player p) {
                watcher = p.getUUID().equals(c.player) && !p.isSleeping();
            } else {
                watcher = false;
            }
            if (watcher) {
                c.spotted = true;
                ColonyManager.markDirty(level);
                LOGGER.info("[night] the agent of {} is spotted at {}", Countries.plainName(level, c.attacker), agent.blockPosition().toShortString());
                return;
            }
        }
    }

    /** The agent died on its operation: an incident, and the victim may take revenge. */
    static void caught(ServerLevel level, Campaign c) {
        UUID victim = c.defender;
        if (victim == null || c.attacker.equals(victim)) return;
        Diplomacy.incident(level, c.attacker, victim, 8);
        ColonyManager.data(level).revenge.put(victim + "|" + c.attacker, level.getGameTime() + REVENGE_DAYS * 24000L);
        ColonyManager.markDirty(level);
        LOGGER.info("[night] {} caught an agent of {}: an incident (relations {})", Countries.plainName(level, victim),
                Countries.plainName(level, c.attacker), Math.round(Diplomacy.relation(level, c.attacker, victim).score));
    }

    /** Back home: a poor agent keeps a share of the loot in its household chests. */
    static void share(ServerLevel level, Campaign c, Colony home) {
        if (c.soldiers.isEmpty()) return;
        UUID agent = c.soldiers.get(0).id;
        Household h = home.households.get(agent);
        if (h == null || home.roleOf(agent) == ColonyRole.WARRIOR) return;
        int emeralds = 0;
        for (int i = 0; i < c.loot.getContainerSize(); i++) if (c.loot.getItem(i).is(Items.EMERALD)) emeralds += c.loot.getItem(i).getCount();
        int keep = (int) Math.floor(emeralds * AGENT_SHARE);
        if (keep <= 0) return;
        List<ColonyStorage.Slot> chests = ColonyStorage.usable(level, h);
        if (chests.isEmpty()) return;
        List<ColonyStorage.Slot> loot = List.of(new ColonyStorage.Slot(home.core(), c.loot));
        List<ItemStack> rest = Chests.put(chests, Chests.take(loot, s -> s.is(Items.EMERALD), keep));
        if (!rest.isEmpty()) Chests.put(loot, rest);
    }
}
