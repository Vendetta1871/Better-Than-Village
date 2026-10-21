package net.betterthanvillage.politics;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.Chests;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Countries (docs_v2/06-politics.md §Countries): every generated village starts as its own country and capital,
 * villages founded by a search party join their home country. The capital is the oldest village of the country by
 * founding order; when it is lost the next oldest takes over. The ruler is the capital's elder.
 */
public final class Countries {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Countries() {
    }

    /** Villages of a country, oldest first. */
    public static List<Colony> villages(ServerLevel level, UUID country) {
        List<Colony> out = new ArrayList<>();
        if (country == null) return out;
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (country.equals(c.country)) out.add(c);
        }
        out.sort(Comparator.comparingLong(c -> c.foundingIndex));
        return out;
    }

    /** The country's record, created on first use; null when the country has no village left. */
    public static Country get(ServerLevel level, UUID id) {
        if (id == null) return null;
        ColonySavedData data = ColonyManager.data(level);
        Country country = data.countries.get(id);
        List<Colony> villages = villages(level, id);
        if (villages.isEmpty()) return null;
        if (country == null) {
            country = new Country(id);
            country.capital = villages.get(0).id();
            country.founded = level.getGameTime();
            data.countries.put(id, country);
            data.setDirty();
        }
        validateCapital(level, country, villages);
        return country;
    }

    /** The capital village, or null when the country is gone. */
    public static Colony capital(ServerLevel level, UUID country) {
        Country c = get(level, country);
        return c == null ? null : ColonyManager.data(level).colony(c.capital);
    }

    public static boolean isCapital(ServerLevel level, Colony colony) {
        Colony capital = capital(level, colony.country);
        return capital == colony;
    }

    /** The ruler: the capital's elder (may be null between elders). */
    public static UUID ruler(ServerLevel level, UUID country) {
        Colony capital = capital(level, country);
        return capital == null ? null : capital.elder;
    }

    /** Succession: a capital that left the country or is gone passes to the oldest village of the country. */
    private static void validateCapital(ServerLevel level, Country country, List<Colony> villages) {
        Colony capital = ColonyManager.data(level).colony(country.capital);
        if (capital != null && country.id.equals(capital.country)) return;
        Colony next = villages.get(0);
        country.capital = next.id();
        next.loyalty = 100;
        ColonyManager.markDirty(level);
        LOGGER.info("[politics] the capital of {} passes to {}", shortId(country.id), villageName(next));
    }

    /** The village joins a country with the given loyalty (a founded daughter village, a conquest). */
    public static void join(ServerLevel level, Colony colony, UUID country, double loyalty) {
        UUID old = colony.country;
        colony.country = country;
        colony.joined = level.getGameTime();
        colony.loyalty = Math.max(0, Math.min(100, loyalty));
        colony.ignoredOrders = 0;
        ColonyManager.markDirty(level);
        get(level, country);
        if (old != null && !old.equals(country)) {
            ColonyManager.data(level).orders.removeIf(o -> o.colony.equals(colony.id()));
            prune(level);
        }
        LOGGER.info("[politics] {} joins {} (loyalty {})", villageName(colony), shortId(country), Math.round(colony.loyalty));
    }

    /** The village becomes an independent country with itself as the capital (a successful rebellion). */
    public static Country secede(ServerLevel level, Colony colony) {
        UUID id = UUID.randomUUID();
        UUID old = colony.country;
        colony.country = id;
        colony.joined = level.getGameTime();
        colony.loyalty = 100;
        colony.ignoredOrders = 0;
        ColonyManager.data(level).orders.removeIf(o -> o.colony.equals(colony.id()));
        Country country = get(level, id);
        if (old != null) Diplomacy.relation(level, old, id).score = -40;
        prune(level);
        ColonyManager.markDirty(level);
        LOGGER.info("[politics] {} leaves {} and becomes a country of its own", villageName(colony), shortId(old));
        return country;
    }

    /** Drops the records of countries without villages, with their relations and offers. */
    public static void prune(ServerLevel level) {
        ColonySavedData data = ColonyManager.data(level);
        java.util.Set<UUID> alive = new java.util.HashSet<>();
        for (Colony c : data.colonies()) if (c.country != null) alive.add(c.country);
        boolean changed = data.countries.keySet().removeIf(id -> !alive.contains(id));
        changed |= data.relations.values().removeIf(r -> !alive.contains(r.a) || !alive.contains(r.b));
        changed |= data.borderOffers.removeIf(o -> !alive.contains(o.buyer) || !alive.contains(o.owner));
        changed |= data.orders.removeIf(o -> !alive.contains(o.country));
        if (changed) data.setDirty();
    }

    // ---------------------------------------------------------------- strength

    /**
     * Military strength of a country (docs_v2/06-politics.md §Deciding on war): warriors count most, then wall
     * rings, golems near a live village, the treasury and the population.
     */
    public static int strength(ServerLevel level, UUID country) {
        int strength = 1;
        for (Colony c : villages(level, country)) strength += villageStrength(level, c);
        return strength;
    }

    public static int villageStrength(ServerLevel level, Colony c) {
        int warriors = 0;
        for (UUID id : c.roles.keySet()) {
            if (c.roleOf(id) == ColonyRole.WARRIOR) warriors++;
        }
        int rings = (c.fortifications.radius > 0 ? 1 : 0) + c.fortifications.oldRings.size();
        int golems = ColonyManager.isLive(level, c) ? Math.min(1, net.betterthanvillage.defense.GolemManager.golems(level, c).size()) : 0;
        int treasury = Chests.count(ColonyStorage.treasury(level, c), s -> s.is(Items.EMERALD));
        return 4 * warriors + 3 * rings + 4 * golems + treasury / 16 + c.lastPopulation / 2;
    }

    // ---------------------------------------------------------------- names

    public static String villageName(Colony colony) {
        return "village (" + colony.core().getX() + ", " + colony.core().getZ() + ")";
    }

    /** "the country of village (x, z)", named after its capital. */
    public static Component name(ServerLevel level, UUID country) {
        Colony capital = capital(level, country);
        if (capital == null) return Component.literal(shortId(country));
        return Component.translatable("message.better_than_village.politics.country", capital.core().getX(), capital.core().getZ());
    }

    public static String plainName(ServerLevel level, UUID country) {
        Colony capital = capital(level, country);
        return capital == null ? shortId(country) : "country of (" + capital.core().getX() + ", " + capital.core().getZ() + ")";
    }

    public static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
