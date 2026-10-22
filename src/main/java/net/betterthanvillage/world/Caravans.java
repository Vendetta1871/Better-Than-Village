package net.betterthanvillage.world;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.SpawnSpots;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Credit;
import net.betterthanvillage.economy.Debt;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Market;
import net.betterthanvillage.economy.Needs;
import net.betterthanvillage.economy.Treasury;
import net.betterthanvillage.economy.Values;
import net.betterthanvillage.economy.Values.Family;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Caravans between villages (docs_v2/01-roadmap.md Phase 4, docs_v2/08-simulation.md): the elder of a village sends
 * one of its villagers with the treasury's surpluses and a purse to another village's treasury, to sell there what
 * the other lacks and buy what home lacks; rulers' orders move resources the same way without payment. A caravan is
 * a moving position while no player is near and a real walking villager near a player; its leader's entity is
 * stored in the caravan whenever it leaves the loaded world.
 */
public final class Caravans {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** A caravan within this distance of its goal has arrived. */
    private static final double ARRIVE = 16;
    /** Units of one family a trade caravan carries at most. */
    private static final int CARRY_CAP = 64;
    /** Emeralds of purse at most. */
    private static final int PURSE_CAP = 48;
    /** Emeralds a village treasury may owe another one at most. */
    private static final int CREDIT_CAP = 32;
    private static final int PLAN_INTERVAL = 200;
    /** Goods are worth carrying for profit where the market index is this much higher than at home. */
    private static final double ARBITRAGE = 1.15;

    /** Villages with a trade plan on a worker. */
    private static final Set<UUID> PLANNING = new HashSet<>();
    /** Leader -> caravan (rebuilt when caravans change). */
    private static final Map<UUID, Caravan> BY_LEADER = new HashMap<>();
    private static boolean indexDirty = true;

    private Caravans() {
    }

    public static void clearCaches() {
        PLANNING.clear();
        BY_LEADER.clear();
        indexDirty = true;
    }

    private static ColonyConfig.World cfg() {
        return ColonyConfig.get().world;
    }

    // ---------------------------------------------------------------- queries

    public static Caravan ofLeader(ServerLevel level, UUID villager) {
        if (indexDirty) {
            BY_LEADER.clear();
            for (Caravan c : ColonyManager.data(level).caravans) BY_LEADER.put(c.leader, c);
            indexDirty = false;
        }
        return villager == null ? null : BY_LEADER.get(villager);
    }

    /** The villager leads a caravan on the road (not back home yet). */
    public static boolean away(ServerLevel level, UUID villager) {
        Caravan c = ofLeader(level, villager);
        return c != null && c.phase != Caravan.Phase.HOME;
    }

    /** The home village of a caravan leader on the road (membership while far from home), or null. */
    public static Colony homeOf(ServerLevel level, UUID villager) {
        if (ColonyManager.data(level).caravans.isEmpty()) return null;
        Caravan c = ofLeader(level, villager);
        return c == null || c.phase == Caravan.Phase.HOME ? null : ColonyManager.data(level).colony(c.home);
    }

    /** The villager walks with a caravan: the controller gives it the travel activity only. */
    public static boolean isTraveller(ServerLevel level, Villager v) {
        return away(level, v.getUUID());
    }

    public static boolean stashing(UUID villager) {
        return Materializer.stashing(villager);
    }

    private static void add(ServerLevel level, Caravan c) {
        ColonyManager.data(level).caravans.add(c);
        indexDirty = true;
        ColonyManager.markDirty(level);
    }

    private static void remove(ServerLevel level, Caravan c) {
        ColonyManager.data(level).caravans.remove(c);
        indexDirty = true;
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- tick

    public static void tick(ServerLevel level, long now) {
        if (now % 20 != 0) return;
        for (Caravan c : new ArrayList<>(ColonyManager.data(level).caravans)) {
            advance(level, c, now);
        }
        if (cfg().caravans && now % PLAN_INTERVAL == 0) plan(level, now);
    }

    // ---------------------------------------------------------------- planning

    /** Holdings and reserve of one treasury, per family (units). */
    private record Stock(UUID colony, double x, double z, EnumMap<Family, Integer> held, EnumMap<Family, Integer> reserve,
                         double factor, Map<String, Double> index) {
        double idx(Family f) {
            return index.getOrDefault(f.name(), 1.0);
        }
    }

    /** A trade plan: where to go, what to carry (units per family) and what to buy. */
    private record Plan(UUID target, Map<Family, Integer> carry, Map<Family, Integer> wants, double score) {
    }

    private static void plan(ServerLevel level, long now) {
        ColonySavedData data = ColonyManager.data(level);
        long interval = cfg().caravan_interval_days * 24000L;
        Set<UUID> busy = new HashSet<>();
        for (Caravan c : data.caravans) {
            if (c.phase != Caravan.Phase.HOME) busy.add(c.home);
        }
        for (Colony home : data.colonies()) {
            if (!home.householdsSettled || home.elder == null || busy.contains(home.id()) || PLANNING.contains(home.id())) continue;
            if (home.lastCaravan != Long.MIN_VALUE && now - home.lastCaravan < interval && now >= home.lastCaravan) continue;
            List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, home);
            if (treasury.isEmpty()) continue;
            Stock mine = stock(level, home, treasury, 1.0);
            List<Stock> others = new ArrayList<>();
            int range = cfg().caravan_range;
            for (Colony other : data.colonies()) {
                if (other == home || !other.householdsSettled) continue;
                double dx = other.core().getX() - home.core().getX(), dz = other.core().getZ() - home.core().getZ();
                if (dx * dx + dz * dz > (double) range * range) continue;
                if (!Diplomacy.trades(level, home.country, other.country)) continue;
                List<ColonyStorage.Slot> theirs = ColonyStorage.treasury(level, other);
                if (theirs.isEmpty()) continue;
                others.add(stock(level, other, theirs, Diplomacy.priceFactor(level, home.country, other.country)));
            }
            // Retried in half a day when nothing worth the trip is found.
            home.lastCaravan = now - interval + 12000;
            if (others.isEmpty()) continue;
            PLANNING.add(home.id());
            UUID homeId = home.id();
            SimWorkers.submit(() -> choose(mine, others), plan -> {
                PLANNING.remove(homeId);
                Colony h = ColonyManager.data(level).colony(homeId);
                if (h == null || plan == null) return;
                Colony target = ColonyManager.data(level).colony(plan.target());
                if (target != null && depart(level, h, target, plan) != null) h.lastCaravan = level.getGameTime();
            }, () -> PLANNING.remove(homeId));
        }
    }

    private static Stock stock(ServerLevel level, Colony colony, List<ColonyStorage.Slot> treasury, double factor) {
        return new Stock(colony.id(), colony.core().getX(), colony.core().getZ(), holdings(treasury), Needs.treasury(colony, 0), factor,
                new HashMap<>(colony.marketIndex));
    }

    /**
     * Worker thread: the most worthwhile destination. A surplus is carried where the other treasury lacks it or where
     * it sells dearer (market index); home buys what it lacks or what is cheaper there.
     */
    private static Plan choose(Stock home, List<Stock> others) {
        Plan best = null;
        for (Stock o : others) {
            Map<Family, Integer> carry = new EnumMap<>(Family.class);
            Map<Family, Integer> wants = new EnumMap<>(Family.class);
            double value = 0;
            for (Family f : Family.values()) {
                int surplus = home.held().getOrDefault(f, 0) - home.reserve().getOrDefault(f, 0);
                int theyWant = o.reserve().getOrDefault(f, 0) - o.held().getOrDefault(f, 0);
                boolean dear = o.idx(f) > home.idx(f) * ARBITRAGE * o.factor();
                int n = Math.min(CARRY_CAP, Math.min(surplus, theyWant > 0 ? theyWant : dear ? CARRY_CAP : 0));
                if (n > 0) {
                    carry.put(f, n);
                    value += n * f.unit * (theyWant > 0 ? o.idx(f) : o.idx(f) - home.idx(f));
                }
                int iWant = home.reserve().getOrDefault(f, 0) - home.held().getOrDefault(f, 0);
                boolean cheap = home.idx(f) > o.idx(f) * ARBITRAGE * o.factor();
                int theyHave = o.held().getOrDefault(f, 0) - o.reserve().getOrDefault(f, 0);
                int m = Math.min(CARRY_CAP, Math.min(theyHave, iWant > 0 ? iWant : cheap ? CARRY_CAP : 0));
                if (m > 0) {
                    wants.put(f, m);
                    value += m * f.unit * (iWant > 0 ? o.idx(f) / o.factor() : home.idx(f) - o.idx(f) * o.factor());
                }
            }
            double dist = Math.sqrt((o.x() - home.x()) * (o.x() - home.x()) + (o.z() - home.z()) * (o.z() - home.z()));
            double score = value - dist / 200.0;
            if (score >= 1 && (best == null || score > best.score())) best = new Plan(o.colony(), carry, wants, score);
        }
        return best;
    }

    /** Units per family in these chests (emeralds and untradable items left out). */
    static EnumMap<Family, Integer> holdings(List<ColonyStorage.Slot> slots) {
        EnumMap<Family, Integer> out = new EnumMap<>(Family.class);
        for (ColonyStorage.Slot slot : slots) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                Family f = Values.family(s);
                if (f != null) out.merge(f, s.getCount() * Values.units(s, f), Integer::sum);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- departure

    /** A trade caravan leaves with the planned goods and a purse; null if nobody can lead it or nothing is worth it. */
    private static Caravan depart(ServerLevel level, Colony home, Colony target, Plan plan) {
        UUID leader = pickLeader(level, home);
        if (leader == null) return null;
        Caravan c = new Caravan(UUID.randomUUID(), Caravan.Kind.TRADE, home.id(), target.id(), leader);
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, home);
        List<ColonyStorage.Slot> cargo = cargo(c, home);
        plan.carry().forEach((f, units) -> load(treasury, cargo, s -> Values.family(s) == f, units, f));
        double cost = 0;
        for (Map.Entry<Family, Integer> e : plan.wants().entrySet()) {
            c.wants.put(e.getKey().name(), e.getValue());
            cost += e.getValue() * e.getKey().unit * Market.index(target, e.getKey());
        }
        int spare = Chests.count(treasury, s -> s.is(Items.EMERALD)) - Treasury.dailyWageBill(home) * 3;
        int purse = Math.max(0, Math.min(PURSE_CAP, Math.min(spare, (int) Math.ceil(cost * 1.25))));
        if (purse > 0) Chests.put(cargo, Chests.take(treasury, s -> s.is(Items.EMERALD), purse));
        if (cargo.get(0).container().isEmpty() && c.wants.isEmpty()) {
            return null;
        }
        start(level, c, home);
        LOGGER.info("[caravan] {} sends a caravan to {}: cargo {}, purse {}, wants {}", Countries.villageName(home),
                Countries.villageName(target), describe(c.cargo), purse, c.wants);
        return c;
    }

    /**
     * A caravan carrying out an order: the given goods of the village's treasury go to the target village. Returns
     * null when nobody can lead it or nothing could be loaded.
     */
    public static Caravan dispatch(ServerLevel level, Colony from, Colony to, Caravan.Kind kind, UUID order, Predicate<ItemStack> goods,
                                   int items) {
        if (ofHome(level, from.id()) != null) return null;
        UUID leader = pickLeader(level, from);
        if (leader == null) return null;
        Caravan c = new Caravan(UUID.randomUUID(), kind, from.id(), to.id(), leader);
        c.order = order;
        List<ColonyStorage.Slot> cargo = cargo(c, from);
        Chests.put(cargo, Chests.take(ColonyStorage.treasury(level, from), goods, Math.min(items, Chests.count(ColonyStorage.treasury(level, from), goods))));
        if (c.cargo.isEmpty()) return null;
        start(level, c, from);
        LOGGER.info("[caravan] {} sends {} to {} ({}): {}", Countries.villageName(from), kind.key(), Countries.villageName(to),
                order == null ? "-" : order.toString().substring(0, 8), describe(c.cargo));
        return c;
    }

    /** The caravan of the village on the road, or null (one back home waiting to materialize does not count). */
    public static Caravan ofHome(ServerLevel level, UUID colony) {
        for (Caravan c : ColonyManager.data(level).caravans) {
            if (c.home.equals(colony) && c.phase != Caravan.Phase.HOME) return c;
        }
        return null;
    }

    private static void start(ServerLevel level, Caravan c, Colony home) {
        long now = level.getGameTime();
        c.departed = now;
        c.x = home.core().getX() + 0.5;
        c.z = home.core().getZ() + 0.5;
        if (level.getEntity(c.leader) instanceof Villager v && v.isAlive()) {
            c.live = true;
            c.x = v.getX();
            c.z = v.getZ();
            Nav.reset(v);
            v.setPersistenceRequired();
        }
        add(level, c);
    }

    private static List<ColonyStorage.Slot> cargo(Caravan c, Colony home) {
        return List.of(new ColonyStorage.Slot(home.core(), c.cargo));
    }

    /** Moves up to units of a family from the chests into the cargo, item by item kind. */
    private static void load(List<ColonyStorage.Slot> from, List<ColonyStorage.Slot> cargo, Predicate<ItemStack> pred, int units, Family f) {
        int left = units;
        for (ColonyStorage.Slot slot : from) {
            Container c = slot.container();
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !pred.test(s)) continue;
                int per = Math.max(1, Values.units(s, f));
                int n = Math.min(s.getCount(), (left + per - 1) / per);
                ItemStack part = s.copyWithCount(n);
                List<ItemStack> rest = Chests.put(cargo, List.of(part));
                int moved = n - Chests.total(rest);
                if (moved <= 0) return;
                s.shrink(moved);
                c.setChanged();
                left -= moved * per;
            }
        }
    }

    /**
     * The caravan's leader: a warrior escort if the village has one to spare, else an adult whose trade another
     * villager also practises; never the elder.
     */
    private static UUID pickLeader(ServerLevel level, Colony colony) {
        Map<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (UUID id : colony.households.keySet()) counts.merge(colony.tradeOf(id), 1, Integer::sum);
        UUID fallback = null;
        for (Household h : colony.households.values()) {
            UUID id = h.owner;
            if (id.equals(colony.elder) || colony.isFounderParty(id) || ofLeader(level, id) != null
                    || net.betterthanvillage.warfare.Campaigns.away(level, id)) continue;
            Entity e = level.getEntity(id);
            if (e != null && (!e.isAlive() || ((Villager) e).isSleeping() || ((Villager) e).getTradingPlayer() != null)) continue;
            if (colony.roleOf(id) == ColonyRole.WARRIOR && counts.getOrDefault(ColonyRole.WARRIOR, 0) > 1) return id;
            if (fallback == null && counts.getOrDefault(colony.tradeOf(id), 0) > 1) fallback = id;
        }
        return fallback;
    }

    // ---------------------------------------------------------------- travel

    private static void advance(ServerLevel level, Caravan c, long now) {
        ColonySavedData data = ColonyManager.data(level);
        Colony home = data.colony(c.home);
        Villager v = level.getEntity(c.leader) instanceof Villager vil && vil.isAlive() ? vil : null;
        if (home == null) {
            // The home village is gone: the caravan breaks up where it is.
            if (v != null) dropCargo(level, c, v.blockPosition());
            remove(level, c);
            return;
        }
        if (c.phase == Caravan.Phase.HOME) {
            if (ColonyManager.isLive(level, home)) comeHome(level, c, home);
            return;
        }
        Colony goal = c.phase == Caravan.Phase.OUTBOUND ? data.colony(c.target) : home;
        if (goal == null) {
            c.phase = Caravan.Phase.RETURNING;
            return;
        }
        double gx = goal.core().getX() + 0.5, gz = goal.core().getZ() + 0.5;
        if (v != null) {
            c.live = true;
            c.x = v.getX();
            c.z = v.getZ();
            if (!level.isPositionEntityTicking(v.blockPosition())) {
                stash(level, c, v);
                return;
            }
            double dist = Math.hypot(gx - c.x, gz - c.z);
            if (dist <= ARRIVE) {
                arrive(level, c, home, now);
                return;
            }
            steer(level, c, v, gx, gz, dist, now);
            return;
        }
        c.live = false;
        double step = cfg().caravan_blocks_per_hour * 20 / 1000.0;
        double dist = Math.hypot(gx - c.x, gz - c.z);
        if (dist <= step + ARRIVE) {
            c.x = gx;
            c.z = gz;
            arrive(level, c, home, now);
            return;
        }
        c.x += (gx - c.x) / dist * step;
        c.z += (gz - c.z) / dist * step;
        BlockPos p = BlockPos.containing(c.x, 64, c.z);
        if (level.hasChunk(p.getX() >> 4, p.getZ() >> 4) && level.isPositionEntityTicking(p)) materialize(level, c, home);
    }

    /** A live leader walks toward the goal by waypoints; when it makes no progress it is helped over the obstacle. */
    private static void steer(ServerLevel level, Caravan c, Villager v, double gx, double gz, double dist, long now) {
        double ahead = Math.min(20, dist);
        int wx = (int) Math.floor(c.x + (gx - c.x) / dist * ahead), wz = (int) Math.floor(c.z + (gz - c.z) / dist * ahead);
        BlockPos wp = surface(level, wx, wz);
        Nav.approach(level, v, wp, 0.7);
        if (now - c.progressTick >= 400) {
            if (dist > c.progressDist - 4 && c.progressTick != 0) {
                int hx = (int) Math.floor(c.x + (gx - c.x) / dist * Math.min(12, dist)), hz = (int) Math.floor(c.z + (gz - c.z) / dist * Math.min(12, dist));
                BlockPos hop = surface(level, hx, hz);
                v.teleportTo(hop.getX() + 0.5, hop.getY(), hop.getZ() + 0.5);
                Nav.reset(v);
            }
            c.progressTick = now;
            c.progressDist = dist;
        }
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** The leader leaves the loaded world: its entity is stored in the caravan. */
    private static void stash(ServerLevel level, Caravan c, Villager v) {
        CompoundTag tag = Materializer.stash(v);
        if (tag == null) return;
        c.leaderData = tag;
        c.live = false;
        ColonyManager.markDirty(level);
        LOGGER.info("[caravan] {} leaves the loaded world at ({}, {})", shortId(c), (int) c.x, (int) c.z);
    }

    /** The caravan enters the loaded world: its leader walks again as a real villager. */
    private static void materialize(ServerLevel level, Caravan c, Colony home) {
        BlockPos pos = surface(level, (int) Math.floor(c.x), (int) Math.floor(c.z));
        Villager v = spawn(level, c, home, pos);
        if (v == null) return;
        c.live = true;
        c.progressTick = 0;
        c.progressDist = Double.MAX_VALUE;
        LOGGER.info("[caravan] {} materializes at {}", shortId(c), pos.toShortString());
    }

    /**
     * Spawns the leader from its stored data, or (when its entity never left its unloaded home chunk) a stand-in with
     * its id and trade; the original is dropped if it ever loads while the caravan is on the road.
     */
    private static Villager spawn(ServerLevel level, Caravan c, Colony home, BlockPos pos) {
        boolean standIn = c.leaderData == null;
        Villager v = Materializer.spawnVillager(level, c.leaderData, c.leader, pos, home);
        if (v == null) return null;
        if (standIn) c.standIn = true;
        c.leaderData = null;
        return v;
    }

    /**
     * Join listener: a caravan leader's entity that is not walking with its caravan (loaded with its old chunk) is
     * kept out of the world; its data is stored in the caravan if it has none. Returns true to cancel the join.
     */
    public static boolean onJoin(ServerLevel level, Entity e) {
        if (Materializer.spawning() || !(e instanceof Villager v) || ColonyManager.data(level).caravans.isEmpty()) return false;
        Caravan c = ofLeader(level, v.getUUID());
        if (c == null) return false;
        // The original is dropped: from now on the stand-in (or its stored data) is the villager.
        c.standIn = false;
        Entity present = level.getEntity(v.getUUID());
        if (present != null && present != v) return true;
        if (c.leaderData == null) {
            CompoundTag tag = new CompoundTag();
            if (v.saveAsPassenger(tag)) c.leaderData = tag;
        }
        c.live = false;
        ColonyManager.markDirty(level);
        return true;
    }

    /** The leader died on the road: the cargo lies where it fell, the caravan is over. */
    public static void onDeath(ServerLevel level, Villager v) {
        Caravan c = ofLeader(level, v.getUUID());
        if (c == null) return;
        dropCargo(level, c, v.blockPosition());
        remove(level, c);
        LOGGER.info("[caravan] {} lost: its leader died at {}", shortId(c), v.blockPosition().toShortString());
    }

    private static void dropCargo(ServerLevel level, Caravan c, BlockPos pos) {
        for (int i = 0; i < c.cargo.getContainerSize(); i++) {
            ItemStack s = c.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
        }
    }

    // ---------------------------------------------------------------- arrival

    private static void arrive(ServerLevel level, Caravan c, Colony home, long now) {
        if (c.phase == Caravan.Phase.OUTBOUND) {
            Colony target = ColonyManager.data(level).colony(c.target);
            if (target != null) {
                if (c.kind == Caravan.Kind.TRADE) trade(level, c, home, target);
                else deliver(level, c, home, target);
            }
            c.phase = Caravan.Phase.RETURNING;
            c.arrived = now;
            c.progressTick = 0;
            c.progressDist = Double.MAX_VALUE;
            ColonyManager.markDirty(level);
            return;
        }
        // Back home: the cargo goes into the treasury.
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, home);
        List<ItemStack> goods = new ArrayList<>();
        for (int i = 0; i < c.cargo.getContainerSize(); i++) {
            ItemStack s = c.cargo.removeItemNoUpdate(i);
            if (!s.isEmpty()) goods.add(s);
        }
        List<ItemStack> rest = Chests.put(treasury, goods);
        if (!rest.isEmpty()) {
            for (ItemStack s : rest) c.cargo.addItem(s);
            if (level.isLoaded(home.core())) dropCargo(level, c, home.core().above());
        }
        LOGGER.info("[caravan] {} is back in {} (sold {}, bought {}, unloaded {} stacks)", shortId(c), Countries.villageName(home),
                c.sold, c.bought, goods.size() - rest.size());
        if (c.standIn) {
            // The leader's original never left its home chunk: the stand-in (walking or stored) is no longer needed.
            if (c.live && level.getEntity(c.leader) instanceof Villager standIn) Materializer.discard(standIn);
            remove(level, c);
            return;
        }
        if (c.live || c.leaderData == null || ColonyManager.isLive(level, home)) comeHome(level, c, home);
        else {
            c.phase = Caravan.Phase.HOME;
            ColonyManager.markDirty(level);
        }
    }

    /** The journey is over: the leader lives at home again (spawned from its stored data if it has to be). */
    private static void comeHome(ServerLevel level, Caravan c, Colony home) {
        if (c.leaderData != null && level.getEntity(c.leader) == null) {
            BlockPos spot = SpawnSpots.find(level, home, level.getGameTime());
            if (spot == null) return;
            if (spawn(level, c, home, spot) == null) return;
        }
        if (level.getEntity(c.leader) instanceof Villager v) Nav.reset(v);
        remove(level, c);
    }

    /** Trade with the destination's treasury: what it lacks is sold to it, what home wants is bought from it. */
    private static void trade(ServerLevel level, Caravan c, Colony home, Colony target) {
        List<ColonyStorage.Slot> dest = ColonyStorage.treasury(level, target);
        if (dest.isEmpty()) {
            LOGGER.info("[caravan] {}: {} has no treasury to trade with", shortId(c), Countries.villageName(target));
            return;
        }
        List<ColonyStorage.Slot> cargo = cargo(c, home);
        double factor = Diplomacy.priceFactor(level, home.country, target.country);
        EnumMap<Family, Integer> held = holdings(dest);
        EnumMap<Family, Integer> reserve = Needs.treasury(target, 0);
        String homeName = "Treasury " + home.core().getX() + "," + home.core().getZ();
        String destName = "Treasury " + target.core().getX() + "," + target.core().getZ();
        Debt.Party homeParty = Debt.Party.treasury(home.id()), destParty = Debt.Party.treasury(target.id());
        int sold = 0, bought = 0, credit = 0;
        // Sell what the destination lacks.
        for (int i = 0; i < c.cargo.getContainerSize(); i++) {
            ItemStack s = c.cargo.getItem(i);
            Family f = Values.family(s);
            if (f == null) continue;
            int want = reserve.getOrDefault(f, 0) - held.getOrDefault(f, 0);
            // Not needed there, but dearer than at home: sold for the profit.
            if (want <= 0 && Market.index(target, f) > Market.index(home, f) * ARBITRAGE * factor) want = CARRY_CAP;
            if (want <= 0) continue;
            int per = Math.max(1, Values.units(s, f));
            int n = Math.min(s.getCount(), (want + per - 1) / per);
            n = Math.min(n, Chests.room(dest, s));
            if (n <= 0) continue;
            double unit = Values.base(s) * Market.index(target, f) / factor;
            int total = Math.max(1, (int) Math.round(n * unit));
            int paid = moveEmeralds(dest, cargo, total);
            int owed = total - paid;
            if (owed > 0 && !(owedBetween(level, destParty, homeParty) + owed <= CREDIT_CAP
                    && Credit.lend(level, homeParty, destParty, owed, cargo, homeName, destName) != null)) {
                moveEmeralds(cargo, dest, paid);
                continue;
            }
            ItemStack lot = s.split(n);
            c.cargo.setChanged();
            List<ItemStack> rest = Chests.put(dest, List.of(lot));
            if (!rest.isEmpty()) c.cargo.addItem(rest.get(0));
            held.merge(f, n * per, Integer::sum);
            sold += total;
            credit += owed;
        }
        // Buy what home wants from the destination's surplus.
        for (Map.Entry<String, Integer> e : new LinkedHashMap<>(c.wants).entrySet()) {
            Family f;
            try {
                f = Family.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            int left = Math.min(e.getValue(), held.getOrDefault(f, 0) - reserve.getOrDefault(f, 0));
            for (ColonyStorage.Slot slot : dest) {
                Container box = slot.container();
                for (int i = 0; i < box.getContainerSize() && left > 0; i++) {
                    ItemStack s = box.getItem(i);
                    if (Values.family(s) != f) continue;
                    int per = Math.max(1, Values.units(s, f));
                    int n = Math.min(s.getCount(), (left + per - 1) / per);
                    n = Math.min(n, Chests.room(cargo, s));
                    if (n <= 0) continue;
                    double unit = Values.base(s) * Market.index(target, f) * factor;
                    int total = Math.max(1, (int) Math.round(n * unit));
                    int paid = moveEmeralds(cargo, dest, total);
                    int owed = total - paid;
                    if (owed > 0 && !(owedBetween(level, homeParty, destParty) + owed <= CREDIT_CAP
                            && Credit.lend(level, destParty, homeParty, owed, dest, destName, homeName) != null)) {
                        moveEmeralds(dest, cargo, paid);
                        left = 0;
                        continue;
                    }
                    ItemStack lot = s.split(n);
                    box.setChanged();
                    Chests.put(cargo, List.of(lot));
                    left -= n * per;
                    held.merge(f, -n * per, Integer::sum);
                    bought += total;
                    credit += owed;
                }
            }
        }
        c.sold += sold;
        c.bought += bought;
        if (sold > 0 || bought > 0) Diplomacy.traded(level, home.country, target.country);
        LOGGER.info("[caravan] {} trades in {}: sold for {}, bought for {}, on credit {}", shortId(c), Countries.villageName(target),
                sold, bought, credit);
    }

    /** An order's caravan hands over its whole cargo for free. */
    private static void deliver(ServerLevel level, Caravan c, Colony home, Colony target) {
        List<ColonyStorage.Slot> dest = ColonyStorage.treasury(level, target);
        int value = 0;
        List<ItemStack> goods = new ArrayList<>();
        for (int i = 0; i < c.cargo.getContainerSize(); i++) {
            ItemStack s = c.cargo.removeItemNoUpdate(i);
            if (s.isEmpty()) continue;
            value += (int) Math.round(s.is(Items.EMERALD) ? s.getCount() : Values.base(s) * s.getCount());
            goods.add(s);
        }
        List<ItemStack> rest = Chests.put(dest, goods);
        for (ItemStack s : rest) {
            c.cargo.addItem(s);
            value -= (int) Math.round(s.is(Items.EMERALD) ? s.getCount() : Values.base(s) * s.getCount());
        }
        net.betterthanvillage.politics.Orders.delivered(level, c.order, home, target, Math.max(0, value));
        LOGGER.info("[caravan] {} delivers {} worth {} to {}", shortId(c), c.kind.key(), value, Countries.villageName(target));
    }

    private static int owedBetween(ServerLevel level, Debt.Party debtor, Debt.Party creditor) {
        int n = 0;
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (d.debtor.equals(debtor) && d.creditor.equals(creditor)) n += d.owed();
        }
        return n;
    }

    private static int moveEmeralds(List<ColonyStorage.Slot> from, List<ColonyStorage.Slot> to, int n) {
        int k = Math.min(n, Math.min(Chests.count(from, s -> s.is(Items.EMERALD)), Chests.room(to, new ItemStack(Items.EMERALD))));
        if (k <= 0) return 0;
        List<ItemStack> rest = Chests.put(to, Chests.take(from, s -> s.is(Items.EMERALD), k));
        int back = Chests.total(rest);
        if (back > 0) Chests.put(from, rest);
        return k - back;
    }

    // ---------------------------------------------------------------- status

    static String shortId(Caravan c) {
        return "caravan " + c.id.toString().substring(0, 8);
    }

    static String describe(Container cargo) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.getItem(i);
            if (!s.isEmpty()) out.merge(Values.name(s.getItem()), s.getCount(), Integer::sum);
        }
        return out.toString();
    }

    /** Debug lines: every caravan on the road. */
    public static List<String> status(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (Caravan c : ColonyManager.data(level).caravans) {
            Colony home = ColonyManager.data(level).colony(c.home), target = ColonyManager.data(level).colony(c.target);
            out.add(shortId(c) + " " + c.kind.key() + " " + c.phase.key() + (c.live ? " live" : " abstract") + " at (" + (int) c.x + ", "
                    + (int) c.z + ") from " + (home == null ? "-" : Countries.villageName(home)) + " to "
                    + (target == null ? "-" : Countries.villageName(target)) + ", cargo " + describe(c.cargo) + ", sold " + c.sold
                    + ", bought " + c.bought + (c.leaderData != null ? ", leader stored" : ""));
        }
        return out;
    }

    /** Debug: plans caravans now (the interval is ignored once). */
    public static void forcePlan(ServerLevel level) {
        for (Colony c : ColonyManager.data(level).colonies()) c.lastCaravan = Long.MIN_VALUE;
        plan(level, level.getGameTime());
    }
}
