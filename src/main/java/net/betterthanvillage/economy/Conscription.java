package net.betterthanvillage.economy;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.economy.Values.Family;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary military service of idle adults (docs_v2/02-professions.md §Conscription): an adult whose trade has
 * earned nothing for several days serves as a warrior and keeps its trade; it returns to the trade when the trade
 * pays again. The army never grows beyond what the treasury can pay for several days; conscripts go first when it
 * cannot. Idle adults the treasury cannot afford are the idle poor.
 */
public final class Conscription {
    private Conscription() {
    }

    private static ColonyConfig.Economy cfg() {
        return ColonyConfig.get().economy;
    }

    /** Morning step after wages. */
    public static void step(ServerLevel level, Colony colony, Map<UUID, Villager> members) {
        long today = Credit.day(level);
        int wage = Math.max(1, cfg().warrior_wage);
        int days = cfg().conscription_budget_days;
        int treasury = ColonyStorage.count(ColonyStorage.treasury(level, colony), s -> s.is(Items.EMERALD));
        int warriors = 0;
        for (Household h : colony.households.values()) {
            if (colony.roleOf(h.owner) == ColonyRole.WARRIOR) warriors++;
        }
        // Over budget: the latest conscripts are released first, professional warriors stay.
        List<UUID> byService = new ArrayList<>(colony.conscripts.keySet());
        byService.sort(Comparator.comparingLong((UUID id) -> colony.conscripts.get(id)).reversed());
        for (UUID id : byService) {
            if ((long) warriors * wage * days <= treasury) break;
            release(level, colony, id, members.get(id));
            warriors--;
        }
        for (UUID id : new ArrayList<>(colony.conscripts.keySet())) {
            Long since = colony.conscripts.get(id);
            if (since != null && today - since >= 2 && tradePays(colony, colony.tradeOf(id))) {
                release(level, colony, id, members.get(id));
                warriors--;
            }
        }
        List<Household> idle = new ArrayList<>();
        for (Household h : colony.households.values()) {
            h.idlePoor = false;
            ColonyRole trade = colony.tradeOf(h.owner);
            Villager v = members.get(h.owner);
            if (v == null || v.isBaby() || colony.conscripts.containsKey(h.owner) || colony.isFounderParty(h.owner)) continue;
            if (trade == ColonyRole.WARRIOR || trade == ColonyRole.ELDER || !trade.isProfession()) continue;
            if (today - h.lastIncomeDay >= cfg().conscription_idle_days) idle.add(h);
        }
        idle.sort(Comparator.comparing((Household h) -> !h.inDefault).thenComparingLong(h -> h.lastIncomeDay));
        java.util.EnumMap<ColonyRole, Integer> working = new java.util.EnumMap<>(ColonyRole.class);
        for (Household h : colony.households.values()) {
            if (!colony.conscripts.containsKey(h.owner)) working.merge(colony.tradeOf(h.owner), 1, Integer::sum);
        }
        boolean hungry = colony.households.values().stream().anyMatch(x -> x.hungryDays > 0);
        for (Household h : idle) {
            ColonyRole trade = colony.tradeOf(h.owner);
            // Farmers are never taken while anyone goes hungry.
            if (hungry && trade == ColonyRole.FARMER) continue;
            // The last holder of a trade keeps it, so the village never loses a trade to the army.
            if (working.getOrDefault(trade, 0) <= 1) continue;
            if ((long) (warriors + 1) * wage * days <= treasury) {
                conscript(level, colony, h.owner, members.get(h.owner), today);
                working.merge(trade, -1, Integer::sum);
                warriors++;
            } else {
                h.idlePoor = true;
            }
        }
    }

    /** True when the trade's goods are wanted again (demand at least supply) or, for masons, public work waits. */
    static boolean tradePays(Colony colony, ColonyRole trade) {
        Family f = switch (trade) {
            case FARMER, HERDER -> Family.FOOD;
            case CARPENTER -> Family.LOGS;
            case MINER -> Family.COBBLE;
            case SMITH -> Family.IRON;
            case LIBRARIAN -> Family.PAPER;
            default -> null;
        };
        if (trade == ColonyRole.MASON) {
            return colony.hasActiveConstruction() || !colony.repairs.isEmpty() || !colony.roadQueue.isEmpty() || Fortifications.hasPendingWall(colony);
        }
        return f != null && Market.index(colony, f) >= 1.0;
    }

    static void conscript(ServerLevel level, Colony colony, UUID id, Villager v, long today) {
        colony.conscripts.put(id, today);
        if (v != null) {
            VillagerUtil.stopModTasks(level, v);
            Nav.reset(v);
            Professions.applyRole(level, colony, v);
        }
        ColonyManager.markDirty(level);
    }

    /** Ends the service; the warrior gear goes back to the treasury when the villager next sees its trade. */
    public static void release(ServerLevel level, Colony colony, UUID id, Villager v) {
        if (colony.conscripts.remove(id) == null) return;
        Household h = colony.households.get(id);
        if (h != null) h.lastIncomeDay = Credit.day(level);
        if (v != null) {
            VillagerUtil.stopModTasks(level, v);
            Nav.reset(v);
            Professions.applyRole(level, colony, v);
        }
        ColonyManager.markDirty(level);
    }
}
