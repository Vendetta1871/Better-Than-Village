package net.betterthanvillage.economy;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gatherings at the bell (docs_v2/03-economy.md §Gatherings): in the morning after waking and in the evening
 * before going home, villagers walk to the bell; once the window closes, those who came trade with each other
 * and with the treasury. The morning gathering also starts the day: every household eats its daily food.
 */
public final class Gathering {
    public static final long MORNING_START = 0;
    public static final long MORNING_END = 1000;
    public static final long EVENING_START = 9000;
    public static final long EVENING_END = 11000;
    /** Villagers this close to the bell (horizontally) count as attending. */
    public static final int ATTEND_RADIUS = 16;
    private static final String ATTENDEES = "economy.gathering.attendees";
    private static final String REPORT = "economy.gathering.report";

    private Gathering() {
    }

    private record Attendance(long index, Set<UUID> villagers) {
    }

    /** Index of the gathering window containing this day time (day * 2 + window), or -1 outside the windows. */
    public static long windowIndex(long dayTime) {
        long day = Math.floorDiv(dayTime, 24000L);
        long t = Math.floorMod(dayTime, 24000L);
        if (t >= MORNING_START && t < MORNING_END) return day * 2;
        if (t >= EVENING_START && t < EVENING_END) return day * 2 + 1;
        return -1;
    }

    /** Index of the last window that has closed by this day time. */
    static long lastClosed(long dayTime) {
        long day = Math.floorDiv(dayTime, 24000L);
        long t = Math.floorMod(dayTime, 24000L);
        if (t >= EVENING_END) return day * 2 + 1;
        if (t >= MORNING_END) return day * 2;
        return day * 2 - 1;
    }

    // ---------------------------------------------------------------- attendance (villager step)

    /**
     * During a gathering window an adult with a household walks to the bell; returns true while it is walking (the
     * controller then skips its other activities). Close to the bell it is marked as attending.
     */
    public static boolean attend(ServerLevel level, Colony colony, Villager v) {
        long index = windowIndex(level.getDayTime());
        if (index < 0 || v.isBaby() || v.isSleeping() || v.getTradingPlayer() != null) return false;
        if (!colony.households.containsKey(v.getUUID()) || colony.isFounderParty(v.getUUID())) return false;
        BlockPos core = colony.core();
        double dx = v.getX() - (core.getX() + 0.5), dz = v.getZ() - (core.getZ() + 0.5);
        double d2 = dx * dx + dz * dz;
        if (d2 <= ATTEND_RADIUS * ATTEND_RADIUS) {
            Attendance a = colony.runtime(ATTENDEES, () -> new Attendance(index, new HashSet<>()));
            if (a.index() != index) {
                a = new Attendance(index, new HashSet<>());
                colony.runtime.put(ATTENDEES, a);
            }
            a.villagers().add(v.getUUID());
            return false;
        }
        if ((level.getGameTime() + v.getId()) % 20 == 0) {
            // Work stops for the gathering; whatever the worker carries is delivered after it.
            VillagerUtil.stopModTasks(level, v);
            VillagerUtil.doExclusively(v, net.minecraft.world.entity.schedule.Activity.IDLE);
            v.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
            BlockPos spot = meetingSpot(level, colony, v);
            if (spot != null) Nav.approach(level, v, spot, 0.75);
        }
        return true;
    }

    private static final String SPOTS = "economy.gathering.spots";

    private record Spots(long computed, List<BlockPos> list) {
    }

    /**
     * A standable cell around the bell for this villager: the bell block itself is not a walk target, and spreading
     * the villagers over several cells keeps one blocked approach from stopping everyone. Recomputed every minute.
     */
    static BlockPos meetingSpot(ServerLevel level, Colony colony, Villager v) {
        long now = level.getGameTime();
        Spots spots = colony.runtime.get(SPOTS) instanceof Spots s && now - s.computed() < 1200 && now >= s.computed() ? s : null;
        if (spots == null) {
            List<BlockPos> list = new ArrayList<>();
            for (int seed = 0; seed < 8; seed++) {
                BlockPos p = net.betterthanvillage.colony.SpawnSpots.find(level, colony, seed * 3L);
                if (p != null && !list.contains(p)) list.add(p);
            }
            spots = new Spots(now, list);
            colony.runtime.put(SPOTS, spots);
        }
        if (spots.list().isEmpty()) return null;
        long now20 = now / 400;
        return spots.list().get((int) Math.floorMod(v.getId() + now20, (long) spots.list().size()));
    }

    // ---------------------------------------------------------------- clearing (census)

    /** Called at every census: clears the last closed gathering once. */
    public static void step(ServerLevel level, Colony colony, List<Villager> members) {
        if (!colony.householdsSettled) return;
        long closed = lastClosed(level.getDayTime());
        if (closed <= colony.lastGathering) return;
        boolean first = colony.lastGathering == Long.MIN_VALUE;
        colony.lastGathering = closed;
        ColonyManager.markDirty(level);
        // Without a record of the window (first run, time skipped) nobody attended.
        Attendance a = colony.runtime.get(ATTENDEES) instanceof Attendance att && att.index() == closed ? att : null;
        Set<UUID> attendees = a != null ? a.villagers() : Set.of();
        boolean morning = Math.floorMod(closed, 2L) == 0;
        Map<UUID, Villager> byId = new HashMap<>();
        int children = 0;
        for (Villager v : members) {
            byId.put(v.getUUID(), v);
            if (v.isBaby()) children++;
        }
        if (morning && !first) {
            dailyMeals(level, colony);
            Treasury.payWages(level, colony);
            Credit.accrue(level, colony);
            Credit.defaults(level, colony, byId);
            Conscription.step(level, colony, byId);
            Births.step(level, colony, byId);
        }
        // Debts due are paid first, by every household (the elder collects them), before anyone buys.
        int repaid = 0;
        for (Household h : colony.households.values()) {
            if (h.hasChest()) {
                repaid += Credit.repayDue(level, colony, Debt.Party.household(h.owner, colony.id()), ColonyStorage.usable(level, h));
            }
        }
        repaid += Credit.repayDue(level, colony, Debt.Party.treasury(colony.id()), ColonyStorage.treasury(level, colony));
        List<Market.Party> parties = new ArrayList<>();
        Villager elder = colony.elder != null ? byId.get(colony.elder) : null;
        parties.add(new Market.Party(null, ColonyRole.ELDER, ColonyStorage.treasury(level, colony), elder, 0f,
                Needs.treasury(colony, children)));
        for (UUID id : attendees) {
            Household h = colony.households.get(id);
            Villager v = byId.get(id);
            if (h == null || v == null || !h.hasChest()) continue;
            List<ColonyStorage.Slot> slots = ColonyStorage.usable(level, h);
            if (slots.isEmpty()) continue;
            int credit = Credit.available(level, colony, h, goodsValue(colony, slots));
            ColonyRole trade = colony.tradeOf(id);
            parties.add(new Market.Party(id, trade, slots, v, h.greed, Needs.household(trade)).withCredit(credit)
                    .withQuality(Skill.quality(colony, id)));
        }
        Market.Lender lender = (seller, buyer, emeralds) -> {
            Debt.Party creditor = seller.id() == null ? Debt.Party.treasury(colony.id()) : Debt.Party.household(seller.id(), colony.id());
            Debt.Party debtor = Debt.Party.household(buyer.id(), colony.id());
            return Credit.lend(level, creditor, debtor, emeralds, seller.slots(),
                    Credit.name(level, colony, creditor), Credit.name(level, colony, debtor)) != null;
        };
        Market.Report report = parties.size() > 1 ? Market.clear(colony, parties, level.random, lender) : new Market.Report();
        report.repaid = repaid;
        for (Market.Party p : parties) {
            Household h = p.id() == null ? null : colony.households.get(p.id());
            if (h != null) {
                h.income += p.earned();
                if (p.earned() > 0) h.lastIncomeDay = Math.floorDiv(level.getDayTime(), 24000L);
            }
        }
        if (!morning) {
            Treasury.collectTaxes(level, colony);
        }
        colony.runtime.put(REPORT, new LastReport(closed, attendees.size(), report));
        ColonyStorage.recount(level, colony);
    }

    /** Market value of the tradable goods in these chests (credit limit). */
    static int goodsValue(Colony colony, List<ColonyStorage.Slot> slots) {
        double value = 0;
        for (ColonyStorage.Slot slot : slots) {
            var c = slot.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                var s = c.getItem(i);
                Values.Family f = Values.family(s);
                if (f != null) value += Values.base(s) * Market.index(colony, f) * s.getCount();
            }
        }
        return (int) value;
    }

    /** Every household eats its daily food from its home chests; a household short of food goes hungry. */
    private static void dailyMeals(ServerLevel level, Colony colony) {
        for (Household h : colony.households.values()) {
            // Children are fed by the village (they eat what they carry from the treasury).
            if (!h.hasChest() || colony.roleOf(h.owner) == ColonyRole.CITIZEN) continue;
            boolean fed = StockScope.household(h.owner, () -> ColonyStorage.consumeNutrition(level, colony, Needs.DAILY_FOOD));
            h.hungryDays = fed ? 0 : h.hungryDays + 1;
        }
    }

    // ---------------------------------------------------------------- status

    public record LastReport(long index, int attendees, Market.Report report) {
    }

    public static LastReport lastReport(Colony colony) {
        return colony.runtime.get(REPORT) instanceof LastReport r ? r : null;
    }
}
