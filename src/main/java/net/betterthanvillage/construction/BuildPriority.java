package net.betterthanvillage.construction;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.economy.BirthOrder;
import net.betterthanvillage.law.PlayerWar;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Relation;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * What the masons build first (docs_v2/04-land.md §Layered walls): the elder decides every morning between the
 * houses paid for (by parents or a player) and the walls. Danger (a war, hostile neighbours, a war on a player, a
 * raid) puts the walls first; otherwise paid houses come first, and walls go up when no house waits. Walls that did
 * not grow for a day give way to a paid house.
 */
public final class BuildPriority {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BUILT_KEY = "btv_priority_walls_built";

    public enum Choice { HOUSES, WALLS }

    private BuildPriority() {
    }

    /** Morning decision of the elder (no elder: houses first, as before). */
    public static void decide(ServerLevel level, Colony colony) {
        String why = danger(level, colony);
        Choice choice;
        // Walls first only while they go up: a day without a new wall block lets the houses go first again.
        int built = colony.fortifications.managed.size();
        Integer before = (Integer) colony.runtime.put(BUILT_KEY, built);
        boolean stuck = colony.buildPriority == Choice.WALLS && before != null && built <= before;
        if (colony.elder == null || !Fortifications.hasPendingWall(colony)) {
            choice = Choice.HOUSES;
            why = colony.elder == null ? "no elder" : "no wall to build";
        } else if (stuck && paidHouseWaiting(colony)) {
            choice = Choice.HOUSES;
            why = "the walls do not go up; a paid house waits";
        } else if (why != null) {
            choice = Choice.WALLS;
        } else if (paidHouseWaiting(colony)) {
            choice = Choice.HOUSES;
            why = "parents paid for a house";
        } else {
            choice = Choice.WALLS;
            why = "no house waits";
        }
        if (choice != colony.buildPriority) {
            LOGGER.info("[village] the elder of {} puts {} first: {}", Countries.villageName(colony), choice.name().toLowerCase(java.util.Locale.ROOT), why);
            colony.buildPriority = choice;
            ColonyManager.markDirty(level);
        }
    }

    /** Why the village feels in danger, or null. */
    private static String danger(ServerLevel level, Colony colony) {
        // Monsters come every night; only a raid counts.
        int r = Math.min(1024, colony.radius() + 32);
        var raid = level.getRaids().getNearbyRaid(colony.core(), r * r);
        if (raid != null && raid.isActive()) return "a raid";
        UUID country = colony.country;
        if (country == null) return null;
        ColonySavedData data = ColonyManager.data(level);
        for (UUID other : data.countries.keySet()) {
            if (other.equals(country)) continue;
            Relation.State state = Diplomacy.state(level, country, other);
            if (state == Relation.State.WAR) return "war with " + Countries.plainName(level, other);
            if (state == Relation.State.HOSTILITY) return "hostile " + Countries.plainName(level, other);
        }
        for (PlayerWar w : data.wars) {
            if (country.equals(w.country)) return "war on " + w.playerName;
        }
        return null;
    }

    /** A house job someone paid for: a player's order, or the house of a birth order the parents paid into. */
    static boolean paid(Colony colony, net.betterthanvillage.colony.ConstructionJob job) {
        if (job.owner != null) return true;
        for (BirthOrder o : colony.birthOrders) {
            if (job.id.equals(o.job) && o.paidA + o.paidB + o.paidTreasury > 0) return true;
        }
        return false;
    }

    private static boolean paidHouseWaiting(Colony colony) {
        for (BirthOrder o : colony.birthOrders) {
            if (o.paidA + o.paidB + o.paidTreasury > 0) return true;
        }
        if (colony.propertyOwner != null) return true;
        // A house a player ordered and paid for.
        for (var job : colony.jobs) {
            if (job.owner != null && !job.countsAsCompleted()) return true;
        }
        return false;
    }
}
