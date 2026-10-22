package net.betterthanvillage.politics;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Credit;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.UUID;

/**
 * The daily politics of every country (docs_v2/06-politics.md), run by the world scheduler whether or not any village
 * is loaded: capital succession, loyalty of the villages, the ruler's orders and patience, diplomacy.
 */
public final class Politics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static long lastDay = Long.MIN_VALUE;

    private Politics() {
    }

    private static ColonyConfig.Politics cfg() {
        return ColonyConfig.get().politics;
    }

    public static void clearCaches() {
        lastDay = Long.MIN_VALUE;
    }

    public static void tick(ServerLevel level, long now, long deadline) {
        if (now % 100 != 0) return;
        long day = Credit.day(level);
        if (day == lastDay) return;
        lastDay = day;
        daily(level, day);
    }

    /** Debug: the daily step runs now. */
    public static void forceDay(ServerLevel level) {
        lastDay = Credit.day(level);
        daily(level, lastDay);
    }

    private static void daily(ServerLevel level, long day) {
        ColonySavedData data = ColonyManager.data(level);
        Countries.prune(level);
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        for (Colony c : data.colonies()) if (c.country != null) ids.add(c.country);
        for (UUID id : ids) {
            Country country = Countries.get(level, id);
            if (country == null || country.lastDay == day) continue;
            country.lastDay = day;
            for (Colony v : Countries.villages(level, id)) loyalty(level, country, v);
            // Tolerance: the ruler's patience slowly comes back.
            country.patience = Math.min(100, country.patience + cfg().patience_recovery);
            Orders.daily(level, country);
        }
        Diplomacy.daily(level, day);
        for (Colony c : new ArrayList<>(data.colonies())) net.betterthanvillage.warfare.Army.daily(level, c, day);
        data.setDirty();
    }

    /**
     * Daily loyalty of a non-capital village (06 §Loyalty and rebellion): distance from the capital lowers it, long
     * peace and the time since joining raise it. Orders, wars and victories move it when they happen.
     */
    private static void loyalty(ServerLevel level, Country country, Colony v) {
        if (v.id().equals(country.capital)) {
            v.loyalty = 100;
            return;
        }
        Colony capital = ColonyManager.data(level).colony(country.capital);
        double delta = 0;
        if (capital != null) {
            double dx = v.core().getX() - capital.core().getX(), dz = v.core().getZ() - capital.core().getZ();
            delta -= Math.min(1.5, Math.max(0, Math.sqrt(dx * dx + dz * dz) - 256) / 400.0);
        }
        if (Diplomacy.enemies(level, country.id).isEmpty()) delta += 0.5;
        long days = Math.max(0, (level.getGameTime() - v.joined) / 24000L);
        delta += Math.min(0.5, days / 40.0);
        adjustLoyalty(level, v, delta);
    }

    public static void adjustLoyalty(ServerLevel level, Colony v, double delta) {
        v.loyalty = Math.max(0, Math.min(100, v.loyalty + delta));
        ColonyManager.markDirty(level);
    }

    /**
     * The ruler's response to an ignored order: while patience lasts the disobedience is tolerated. When it runs out
     * the ruler suppresses the village (06 §Loyalty and rebellion): the village rises, becomes a country of its own
     * and the ruler goes to war on it. Conquered back it rejoins with low loyalty; if the ruler fails, it stays free.
     * A ruler too weak for it (or with campaigns off) tolerates and its patience starts over.
     */
    static void rulerResponds(ServerLevel level, Country country, Colony rebel) {
        if (country.patience > 0) {
            LOGGER.info("[politics] the ruler of {} tolerates the disobedience of {}", Countries.plainName(level, country.id),
                    Countries.villageName(rebel));
            return;
        }
        country.patience = 50;
        ColonyManager.markDirty(level);
        int rest = Countries.strength(level, country.id) - Countries.villageStrength(level, rebel);
        if (!ColonyConfig.get().warfare.campaigns || rest < Countries.villageStrength(level, rebel) + 1) {
            LOGGER.info("[politics] the ruler of {} has lost patience with {} but is too weak to suppress it", Countries.plainName(level, country.id),
                    Countries.villageName(rebel));
            return;
        }
        LOGGER.info("[politics] the ruler of {} suppresses the rebellion of {}", Countries.plainName(level, country.id), Countries.villageName(rebel));
        Country free = Countries.secede(level, rebel);
        Diplomacy.declareWar(level, country.id, free.id, Relation.Cause.REBELLION);
    }

    /** Debug lines: the country of the village. */
    public static java.util.List<String> status(ServerLevel level, Colony colony) {
        java.util.List<String> out = new ArrayList<>();
        Country country = Countries.get(level, colony.country);
        if (country == null) return out;
        Colony capital = ColonyManager.data(level).colony(country.capital);
        out.add(Countries.plainName(level, country.id) + " [" + Countries.shortId(country.id) + "]: capital "
                + (capital == null ? "-" : Countries.villageName(capital)) + ", ruler " + Countries.shortId(Countries.ruler(level, country.id))
                + ", strength " + Countries.strength(level, country.id) + ", patience " + Math.round(country.patience)
                + (level.getGameTime() < country.taxUntil ? ", tax " + Math.round(country.taxRate * 100) + "%" : ""));
        for (Colony v : Countries.villages(level, country.id)) {
            out.add("  " + Countries.villageName(v) + (v.id().equals(country.capital) ? " (capital)" : "") + ": founded #" + v.foundingIndex
                    + ", loyalty " + Math.round(v.loyalty) + ", ignored orders " + v.ignoredOrders + ", strength "
                    + Countries.villageStrength(level, v) + ", " + (ColonyManager.isLive(level, v) ? "live" : "abstract"));
        }
        for (String s : Diplomacy.status(level, country.id)) out.add("  relation with " + s);
        for (String s : Orders.status(level, country.id)) out.add("  order " + s);
        return out;
    }
}
