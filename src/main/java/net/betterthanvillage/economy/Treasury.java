package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Taxes and payments of the treasury (docs_v2/03-economy.md §Taxes and treasury): a money tax on income collected
 * at the evening gathering, a levy in kind on produced goods, and in the morning the elder's salary, the
 * warriors' wages and pay for public work. What the treasury cannot pay is owed; what a household cannot pay
 * becomes its tax debt.
 */
public final class Treasury {
    /** Goods worth less than this per item pay no levy in kind. */
    private static final double LEVY_MIN_VALUE = 0.05 * Values.SCALE;
    /** A household keeps at most this much of one kind of cheap bulk (stone, gravel, sand). */
    public static final int BULK_CAP = 256;

    /** Cheap bulk: tradable goods worth less than the levy threshold. */
    public static boolean isBulk(ItemStack stack) {
        return Values.tradable(stack) && Values.base(stack) < LEVY_MIN_VALUE;
    }

    /** The treasury adds a chest when fewer slots than this are free. */
    private static final int MIN_FREE_SLOTS = 9;

    private Treasury() {
    }

    /**
     * Gives a full treasury another double chest next to the warehouse, paid with its own planks or logs.
     * Returns true if a chest was placed.
     */
    public static boolean expand(ServerLevel level, Colony colony) {
        if (colony.storages.isEmpty() || !ColonyManager.terrainChangesAllowed(level)) return false;
        List<ColonyStorage.Slot> slots = ColonyStorage.treasury(level, colony);
        int free = 0;
        for (ColonyStorage.Slot slot : slots) {
            for (int i = 0; i < slot.container().getContainerSize(); i++) {
                if (slot.container().getItem(i).isEmpty()) free++;
            }
        }
        if (free >= MIN_FREE_SLOTS) return false;
        net.minecraft.core.BlockPos site = net.betterthanvillage.colony.StoragePlacer.findSite(level, colony);
        if (site == null) return false;
        boolean paid = Chests.take(slots, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 16).size() > 0
                || Chests.take(slots, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 4).size() > 0;
        if (!paid) return false;
        return net.betterthanvillage.colony.StoragePlacer.placeDoubleChest(level, colony, site, net.betterthanvillage.colony.StorageKind.GENERAL);
    }

    private static ColonyConfig.Economy cfg() {
        return ColonyConfig.get().economy;
    }

    // ---------------------------------------------------------------- money tax

    /** Evening: a share of each household's income since the last collection, plus older tax debt. */
    public static void collectTaxes(ServerLevel level, Colony colony) {
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        for (Household h : colony.households.values()) {
            h.incomeAvg = h.incomeAvg * 0.7 + h.income * 0.3;
            h.taxDue += h.income * cfg().tax_rate;
            h.income = 0;
            int due = (int) Math.floor(h.taxDue + 1e-9);
            if (due <= 0 || !h.hasChest()) continue;
            int paid = move(ColonyStorage.usable(level, h), treasury, due);
            h.taxDue -= paid;
        }
    }

    // ---------------------------------------------------------------- levy in kind

    /**
     * Production delivered by a household's worker: the levy share (accumulated across deliveries) is split off
     * the stack and put into the treasury. The stack keeps the household's part.
     */
    public static void levy(ServerLevel level, Colony colony, Household h, ItemStack stack) {
        // Cheap bulk (stone, sand, gravel) is not worth carrying to the treasury and would only fill its chests.
        if (stack.isEmpty() || cfg().levy_rate <= 0 || !Values.tradable(stack) || Values.base(stack) < LEVY_MIN_VALUE) return;
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        double credit = h.levyCredit.getOrDefault(key, 0.0) + stack.getCount() * cfg().levy_rate;
        int whole = (int) Math.min(stack.getCount(), Math.floor(credit + 1e-9));
        if (whole > 0) {
            ItemStack part = stack.split(whole);
            StockScope.treasury(() -> ColonyStorage.deposit(level, colony, part));
            // What does not fit into the treasury stays with the household.
            if (!part.isEmpty()) stack.grow(part.getCount());
            whole -= part.getCount();
        }
        h.levyCredit.put(key, credit - whole);
    }

    // ---------------------------------------------------------------- wages

    /** Morning: salary, wages and public labour pay, plus anything still owed from earlier days. */
    public static void payWages(ServerLevel level, Colony colony) {
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        for (Household h : colony.households.values()) {
            ColonyRole role = colony.roleOf(h.owner);
            int due = h.wagesOwed;
            if (role == ColonyRole.ELDER) due += cfg().elder_salary;
            if (role == ColonyRole.WARRIOR) due += cfg().warrior_wage;
            int units = cfg().labor_units_per_emerald;
            boolean earned = role == ColonyRole.ELDER;
            if (h.labor >= units) {
                due += h.labor / units;
                h.labor %= units;
                earned = true;
            }
            // Salary and public work pay count as the trade paying (conscription); a conscript's wage does not.
            if (earned) h.lastIncomeDay = Credit.day(level);
            if (due <= 0 || !h.hasChest()) {
                h.wagesOwed = due;
                continue;
            }
            int paid = move(treasury, ColonyStorage.usable(level, h), due);
            h.wagesOwed = due - paid;
            h.income += paid;
        }
    }

    /** Public work progress of a worker (called inside a public-work task), paid in the morning. */
    public static void creditLabor(ServerLevel level, Villager v, int count) {
        if (count <= 0) return;
        Colony colony = ColonyManager.colonyOf(level, v);
        // Warriors are paid wages; their duty is not extra labour.
        if (colony == null || colony.roleOf(v.getUUID()) == ColonyRole.WARRIOR) return;
        Household h = colony.households.get(v.getUUID());
        if (h != null) h.labor = Math.min(1_000_000, h.labor + count);
    }

    /** Wages the treasury can pay for the coming days: emeralds over the daily wage bill (conscription budget). */
    public static int dailyWageBill(Colony colony) {
        int bill = 0;
        for (Household h : colony.households.values()) {
            ColonyRole role = colony.roleOf(h.owner);
            if (role == ColonyRole.ELDER) bill += cfg().elder_salary;
            if (role == ColonyRole.WARRIOR) bill += cfg().warrior_wage;
        }
        return bill;
    }

    // ---------------------------------------------------------------- helpers

    /** Moves up to n emeralds between chest lists; returns how many moved. */
    static int move(List<ColonyStorage.Slot> from, List<ColonyStorage.Slot> to, int n) {
        int available = Chests.count(from, s -> s.is(Items.EMERALD));
        int k = Math.min(n, Math.min(available, Chests.room(to, new ItemStack(Items.EMERALD))));
        if (k <= 0) return 0;
        List<ItemStack> money = Chests.take(from, s -> s.is(Items.EMERALD), k);
        List<ItemStack> rest = Chests.put(to, money);
        int back = Chests.total(rest);
        if (back > 0) Chests.put(from, rest);
        return k - back;
    }
}
