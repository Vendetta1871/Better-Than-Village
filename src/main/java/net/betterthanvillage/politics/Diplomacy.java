package net.betterthanvillage.politics;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Debt;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Diplomacy between countries (docs_v2/06-politics.md §Diplomacy): relations scores fed by trade, debts and
 * incidents; alliance, peace, hostility and war; the causes of war and the ruler's decision by strength. Wars are
 * declared here and fought in phase 5; until then a war ends when its cause is gone or after a while without battles.
 */
public final class Diplomacy {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Trade days kept for the "steady trade" test. */
    private static final int TRADE_WINDOW_DAYS = 10;

    private Diplomacy() {
    }

    private static ColonyConfig.Politics cfg() {
        return ColonyConfig.get().politics;
    }

    public static String key(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** The relation between two different countries, created at peace on first use. */
    public static Relation relation(ServerLevel level, UUID a, UUID b) {
        ColonySavedData data = ColonyManager.data(level);
        String key = key(a, b);
        Relation r = data.relations.get(key);
        if (r == null) {
            boolean order = a.compareTo(b) <= 0;
            r = new Relation(order ? a : b, order ? b : a);
            r.since = level.getGameTime();
            data.relations.put(key, r);
            data.setDirty();
        }
        return r;
    }

    public static Relation.State state(ServerLevel level, UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return Relation.State.ALLIANCE;
        Relation r = ColonyManager.data(level).relations.get(key(a, b));
        return r == null ? Relation.State.PEACE : r.state;
    }

    public static boolean atWar(ServerLevel level, UUID a, UUID b) {
        return state(level, a, b) == Relation.State.WAR;
    }

    /** Caravans go between villages of the same country, of allies and of countries at peace. */
    public static boolean trades(ServerLevel level, UUID a, UUID b) {
        Relation.State s = state(level, a, b);
        return s == Relation.State.PEACE || s == Relation.State.ALLIANCE;
    }

    /** Markup on prices between two countries: 1 between friends, up to 1.5 when relations are bad. */
    public static double priceFactor(ServerLevel level, UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return 1.0;
        Relation r = ColonyManager.data(level).relations.get(key(a, b));
        return r == null ? 1.0 : 1.0 + Math.max(0, -r.score) / 200.0;
    }

    // ---------------------------------------------------------------- events feeding relations

    /** A caravan traded between the two countries. */
    public static void traded(ServerLevel level, UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return;
        Relation r = relation(level, a, b);
        adjust(r, 2);
        long day = net.betterthanvillage.economy.Credit.day(level);
        if (r.tradeDays.isEmpty() || r.tradeDays.get(r.tradeDays.size() - 1) != day) r.tradeDays.add(day);
        while (r.tradeDays.size() > 16) r.tradeDays.remove(0);
        ColonyManager.markDirty(level);
    }

    /** An incident between the countries (agents caught, a refusal, a broken promise): relations drop. */
    public static void incident(ServerLevel level, UUID a, UUID b, double amount) {
        if (a == null || b == null || a.equals(b)) return;
        adjust(relation(level, a, b), -amount);
        ColonyManager.markDirty(level);
    }

    private static void adjust(Relation r, double delta) {
        r.score = Math.max(-100, Math.min(100, r.score + delta));
    }

    /**
     * A village treasury is overdue on a debt to a village of another country (docs_v2/03-economy.md §Default):
     * relations suffer at the first strike, the default at the second is a cause of war.
     */
    public static void debtDefault(ServerLevel level, Debt d, int strike) {
        ColonySavedData data = ColonyManager.data(level);
        Colony debtor = data.colony(d.debtor.colony()), creditor = data.colony(d.creditor.colony());
        if (debtor == null || creditor == null || debtor.country == null || debtor.country.equals(creditor.country)) return;
        incident(level, debtor.country, creditor.country, strike >= 2 ? 30 : 10);
        LOGGER.info("[politics] {} is {} on its debt of {} to {}", Countries.villageName(debtor), strike >= 2 ? "in default" : "overdue",
                d.owed(), Countries.villageName(creditor));
    }

    // ---------------------------------------------------------------- war and peace

    public static void declareWar(ServerLevel level, UUID aggressor, UUID target, Relation.Cause cause) {
        Relation r = relation(level, aggressor, target);
        if (r.state == Relation.State.WAR) return;
        r.state = Relation.State.WAR;
        r.since = level.getGameTime();
        r.cause = cause;
        r.aggressor = aggressor;
        adjust(r, -30);
        ColonyManager.markDirty(level);
        LOGGER.info("[politics] {} declares war on {} ({})", Countries.plainName(level, aggressor), Countries.plainName(level, target), cause.key());
        announce(level, Component.translatable("message.better_than_village.politics.war", Countries.name(level, aggressor),
                Countries.name(level, target), Component.translatable("message.better_than_village.politics.cause." + cause.key())),
                aggressor, target);
        Orders.mobilize(level, aggressor, target);
        Orders.mobilize(level, target, aggressor);
    }

    /** Peace between the countries with a plain truce (tribute and land transfers come with campaigns, phase 5). */
    public static void makePeace(ServerLevel level, Relation r, String terms) {
        if (r.state != Relation.State.WAR) return;
        r.state = Relation.State.PEACE;
        r.since = level.getGameTime();
        r.cause = null;
        r.aggressor = null;
        r.truceUntil = level.getGameTime() + cfg().truce_days * 24000L;
        r.score = Math.max(r.score, cfg().hostility_score + 10);
        ColonyManager.data(level).orders.removeIf(o -> o.kind == Order.Kind.MOBILIZE && (o.country.equals(r.a) || o.country.equals(r.b)));
        // A peace settles the border dispute too: the buyer gives the land up.
        ColonyManager.data(level).borderOffers.removeIf(o -> r.involves(o.buyer) && r.involves(o.owner));
        ColonyManager.markDirty(level);
        LOGGER.info("[politics] peace between {} and {} ({})", Countries.plainName(level, r.a), Countries.plainName(level, r.b), terms);
        announce(level, Component.translatable("message.better_than_village.politics.peace", Countries.name(level, r.a),
                Countries.name(level, r.b)), r.a, r.b);
    }

    /** Tells the players inside the villages of either country. */
    private static void announce(ServerLevel level, Component text, UUID a, UUID b) {
        for (ServerPlayer p : level.players()) {
            Colony c = ColonyManager.colonyAt(level, p.blockPosition());
            if (c != null && (a.equals(c.country) || b.equals(c.country))) p.displayClientMessage(text, false);
        }
    }

    public static List<UUID> allies(ServerLevel level, UUID country) {
        List<UUID> out = new ArrayList<>();
        for (Relation r : ColonyManager.data(level).relations.values()) {
            if (r.involves(country) && r.state == Relation.State.ALLIANCE) out.add(r.other(country));
        }
        return out;
    }

    public static List<UUID> enemies(ServerLevel level, UUID country) {
        List<UUID> out = new ArrayList<>();
        for (Relation r : ColonyManager.data(level).relations.values()) {
            if (r.involves(country) && r.state == Relation.State.WAR) out.add(r.other(country));
        }
        return out;
    }

    private static boolean commonEnemy(ServerLevel level, Relation r) {
        Set<UUID> a = new HashSet<>(enemies(level, r.a));
        for (UUID e : enemies(level, r.b)) if (a.contains(e)) return true;
        return false;
    }

    // ---------------------------------------------------------------- daily

    /** Once a day: relations drift back toward neutral, states follow the scores, wars end or are declared. */
    static void daily(ServerLevel level, long day) {
        ColonySavedData data = ColonyManager.data(level);
        long now = level.getGameTime();
        for (Relation r : new ArrayList<>(data.relations.values())) {
            r.score *= 0.98;
            r.tradeDays.removeIf(d -> day - d > TRADE_WINDOW_DAYS);
            switch (r.state) {
                case WAR -> {
                    if (!causeStands(level, r)) makePeace(level, r, "cause resolved");
                    else if ((now - r.since) / 24000L >= cfg().war_max_days) makePeace(level, r, "plain truce");
                }
                case PEACE -> {
                    if (r.score <= cfg().hostility_score) setState(level, r, Relation.State.HOSTILITY);
                    else if ((r.score >= cfg().alliance_score && r.tradeDays.size() >= 3 && !defaulted(level, r.a, r.b)
                            && !defaulted(level, r.b, r.a)) || commonEnemy(level, r)) setState(level, r, Relation.State.ALLIANCE);
                }
                case HOSTILITY -> {
                    if (r.score >= cfg().hostility_score + 20) setState(level, r, Relation.State.PEACE);
                }
                case ALLIANCE -> {
                    if (r.score < 20 && !commonEnemy(level, r)) setState(level, r, Relation.State.PEACE);
                }
            }
        }
        for (java.util.UUID country : new ArrayList<>(data.countries.keySet())) decideWar(level, country);
        data.setDirty();
    }

    private static void setState(ServerLevel level, Relation r, Relation.State state) {
        LOGGER.info("[politics] {} and {}: {} -> {} (score {})", Countries.plainName(level, r.a), Countries.plainName(level, r.b),
                r.state.key(), state.key(), Math.round(r.score));
        r.state = state;
        r.since = level.getGameTime();
    }

    /** The cause of a war still holds: an unpaid debt still unpaid, a border still refused. */
    private static boolean causeStands(ServerLevel level, Relation r) {
        if (r.cause == null || r.aggressor == null) return true;
        UUID victim = r.other(r.aggressor);
        return switch (r.cause) {
            case DEBT -> defaulted(level, victim, r.aggressor);
            case BORDER -> borderDispute(level, r.aggressor, victim);
            case ALLY -> !enemies(level, victim).isEmpty();
            default -> true;
        };
    }

    /** A village of debtorCountry is in default on a debt to a village of creditorCountry. */
    static boolean defaulted(ServerLevel level, UUID debtorCountry, UUID creditorCountry) {
        ColonySavedData data = ColonyManager.data(level);
        for (Debt d : data.debts.values()) {
            if (d.strikes < 2 || d.debtor.kind() != Debt.Kind.TREASURY || d.creditor.kind() == Debt.Kind.PLAYER) continue;
            Colony debtor = data.colony(d.debtor.colony()), creditor = data.colony(d.creditor.colony());
            if (debtor != null && creditor != null && debtorCountry.equals(debtor.country) && creditorCountry.equals(creditor.country)) return true;
        }
        return false;
    }

    static boolean borderDispute(ServerLevel level, UUID buyer, UUID owner) {
        for (BorderOffer o : ColonyManager.data(level).borderOffers) {
            if (o.buyer.equals(buyer) && o.owner.equals(owner)) return true;
        }
        return false;
    }

    /**
     * The ruler weighs one cause of war against each other country: an unpaid debt, a refused border, an ally under
     * attack, terrible relations (repeated incidents), or resources it lacks. A conquest for resources needs a clear
     * advantage over the target and its allies; the other causes only need not to be weaker. One declaration a day.
     */
    private static void decideWar(ServerLevel level, UUID country) {
        if (Countries.get(level, country) == null) return;
        long now = level.getGameTime();
        int mine = Countries.strength(level, country);
        for (UUID allied : allies(level, country)) mine += Countries.strength(level, allied) / 2;
        for (UUID other : new ArrayList<>(ColonyManager.data(level).countries.keySet())) {
            if (other.equals(country)) continue;
            Relation r = relation(level, country, other);
            if (r.state == Relation.State.WAR || now < r.truceUntil) continue;
            Relation.Cause cause = null;
            if (defaulted(level, other, country)) cause = Relation.Cause.DEBT;
            else if (borderDispute(level, country, other)) cause = Relation.Cause.BORDER;
            else if (r.state != Relation.State.ALLIANCE && allyAttackedBy(level, country, other)) cause = Relation.Cause.ALLY;
            else if (r.state == Relation.State.HOSTILITY && r.score <= -80) cause = Relation.Cause.INCIDENTS;
            else if (r.state == Relation.State.HOSTILITY && lacksMiners(level, country) && !lacksMiners(level, other)) cause = Relation.Cause.RESOURCES;
            if (cause == null) continue;
            int theirs = Countries.strength(level, other);
            for (UUID allied : allies(level, other)) theirs += Countries.strength(level, allied);
            double needed = cause == Relation.Cause.RESOURCES ? cfg().conquest_advantage : 1.0;
            if (mine >= needed * theirs) {
                declareWar(level, country, other, cause);
                return;
            }
            if (cause == Relation.Cause.ALLY) {
                // Refusing to defend an ally hurts the alliance.
                for (UUID ally : allies(level, country)) {
                    if (atWar(level, ally, other)) incident(level, country, ally, 30);
                }
            }
        }
    }

    private static boolean allyAttackedBy(ServerLevel level, UUID country, UUID attacker) {
        for (UUID ally : allies(level, country)) {
            Relation r = ColonyManager.data(level).relations.get(key(ally, attacker));
            if (r != null && r.state == Relation.State.WAR && attacker.equals(r.aggressor)) return true;
        }
        return false;
    }

    private static boolean lacksMiners(ServerLevel level, UUID country) {
        for (Colony c : Countries.villages(level, country)) {
            for (UUID id : c.roles.keySet()) if (c.tradeOf(id) == ColonyRole.MINER) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- status

    public static List<String> status(ServerLevel level, UUID country) {
        List<String> out = new ArrayList<>();
        long now = level.getGameTime();
        for (Relation r : ColonyManager.data(level).relations.values()) {
            if (!r.involves(country)) continue;
            out.add(Countries.plainName(level, r.other(country)) + ": " + r.state.key() + " (score " + Math.round(r.score) + ", trades "
                    + r.tradeDays.size() + (r.cause != null ? ", cause " + r.cause.key() : "")
                    + (r.aggressor != null ? ", declared by " + Countries.plainName(level, r.aggressor) : "")
                    + (now < r.truceUntil ? ", truce " + (r.truceUntil - now) / 20 + "s" : "") + ")");
        }
        return out;
    }
}
