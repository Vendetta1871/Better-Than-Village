package net.betterthanvillage.society;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Abstract market (06 §1.4): at most one buy or sell against an invisible outside market per run. */
public final class Economy {
    public static final String SAVING = "не хватает еды/древесины: копим изумруды на снабжение";

    private Economy() {
    }

    /** Called at every census; runs every 200 ticks when the colony has storage. */
    public static void step(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now < colony.nextEconomyTick || colony.storages.isEmpty()) {
            return;
        }
        colony.nextEconomyTick = now + 200;
        List<Villager> workers = new ArrayList<>();
        for (Villager v : ColonyManager.adultMembers(level, colony)) {
            if (colony.roleOf(v.getUUID()) != ColonyRole.CITIZEN) workers.add(v);
        }
        if (workers.isEmpty()) {
            return;
        }
        workers.sort(Comparator.comparing(Villager::getUUID));
        ColonyStorage.recount(level, colony);
        if (colony.cachedFood < 32 || colony.cachedLogs < 8) {
            emergency(level, colony, workers);
        } else {
            normal(level, colony, workers);
        }
    }

    private static void emergency(ServerLevel level, Colony colony, List<Villager> workers) {
        Villager agent = workers.get(0);
        boolean lowFood = colony.cachedFood < 32;
        if (lowFood && buy(level, colony, agent, new ItemStack(Items.BREAD, 8), 2, "аварийный запас еды")) return;
        if (colony.cachedLogs < 8 && !(lowFood && colony.cachedLogs >= 5)
                && buy(level, colony, agent, new ItemStack(Items.OAK_LOG, 8), 1, "древесина для запуска хозяйства")) return;
        for (Villager w : workers) {
            ColonyRole role = colony.roleOf(w.getUUID());
            if ((role == ColonyRole.MINER || role == ColonyRole.MASON)
                    && sell(level, colony, w, role, Goods.COBBLE, 32, 64, 1, "камень на снабжение")) return;
            if (surplus(level, colony, w, role)) return;
        }
        WorkTracker.status(agent, SAVING);
    }

    private static void normal(ServerLevel level, Colony colony, List<Villager> workers) {
        Villager agent = workers.get(0);
        for (Villager w : workers) {
            ColonyRole r = colony.roleOf(w.getUUID());
            if (r == ColonyRole.WARRIOR || r == ColonyRole.MASON) {
                agent = w;
                break;
            }
        }
        int golems = golemCount(level, colony);
        int desired = Math.max(4, (colony.population() + 5) / 6);
        if (golems < desired) {
            if (ColonyStorage.count(level, colony, Goods.item(Items.PUMPKIN_SEEDS)) == 0
                    && buy(level, colony, agent, new ItemStack(Items.PUMPKIN_SEEDS, 4), 1, "семена тыквы для фермы")) return;
            int need = desired - golems;
            int pumpkins = ColonyStorage.count(level, colony, Goods.PUMPKINS);
            if (pumpkins < need && buy(level, colony, agent, new ItemStack(Items.PUMPKIN, Math.min(2, need - pumpkins)), 1, "тыква для голема")) return;
            int iron = ColonyStorage.count(level, colony, Goods.item(Items.IRON_INGOT));
            if (iron < 36 && buy(level, colony, agent, new ItemStack(Items.IRON_INGOT, Math.min(8, 36 - iron)), 2, "железо для голема")) return;
        }
        int n = workers.size();
        int start = Math.floorMod(colony.economyCursor, n);
        colony.economyCursor++;
        for (int i = 0; i < n; i++) {
            Villager w = workers.get((start + i) % n);
            ColonyRole role = colony.roleOf(w.getUUID());
            if (needs(level, colony, w, role)) return;
            if (surplus(level, colony, w, role)) return;
        }
    }

    /** Table A: purchases a role needs; a failed purchase falls through to the next line. */
    private static boolean needs(ServerLevel level, Colony colony, Villager w, ColonyRole role) {
        switch (role) {
            case MASON -> {
                // Beds cost wool: without a sheep pen the colony would never furnish its houses.
                boolean bedsWanted = colony.repairs.values().stream().anyMatch(st -> st.is(BlockTags.BEDS));
                if (bedsWanted && ColonyStorage.count(level, colony, Goods.WOOL) < 3
                        && buy(level, colony, w, new ItemStack(Items.WHITE_WOOL, 6), 1, "шерсть для кроватей")) return true;
                if (colony.cachedLogs < 48 && buy(level, colony, w, new ItemStack(Items.OAK_LOG, 16), 2, "брёвна")) return true;
                return colony.cachedStone < 96 && buy(level, colony, w, new ItemStack(Items.COBBLESTONE, 32), 2, "камень");
            }
            case MINER -> {
                if (colony.cachedLogs < 16 && buy(level, colony, w, new ItemStack(Items.OAK_LOG, 8), 1, "крепи шахты")) return true;
                return ColonyStorage.count(level, colony, Goods.FUEL_COAL) < 16 && buy(level, colony, w, new ItemStack(Items.COAL, 8), 1, "уголь");
            }
            case FARMER -> {
                if (ColonyStorage.count(level, colony, Goods.item(Items.PUMPKIN_SEEDS)) < 4
                        && buy(level, colony, w, new ItemStack(Items.PUMPKIN_SEEDS, 4), 1, "семена тыквы")) return true;
                if (ColonyStorage.count(level, colony, Goods.SEEDS) < 16 && buy(level, colony, w, new ItemStack(Items.WHEAT_SEEDS, 16), 1, "семена")) return true;
                return colony.cachedFood < Math.max(64, colony.population() * 8) && buy(level, colony, w, new ItemStack(Items.BREAD, 8), 2, "еда");
            }
            case CARPENTER -> {
                return colony.cachedSaplings < 8 && buy(level, colony, w, new ItemStack(Items.OAK_SAPLING, 8), 1, "саженцы");
            }
            case HERDER -> {
                if (ColonyStorage.count(level, colony, Goods.item(Items.STRING)) < 3 && buy(level, colony, w, new ItemStack(Items.STRING, 3), 1, "леска")) return true;
                return ColonyStorage.count(level, colony, Goods.item(Items.WHEAT)) < 16 && buy(level, colony, w, new ItemStack(Items.WHEAT, 16), 2, "корм");
            }
            case WARRIOR -> {
                if (ColonyStorage.count(level, colony, Goods.item(Items.ARROW)) < 32 && buy(level, colony, w, new ItemStack(Items.ARROW, 32), 2, "стрелы")) return true;
                return colony.cachedIron < 24 && buy(level, colony, w, new ItemStack(Items.IRON_INGOT, 8), 3, "железо для снаряжения");
            }
            default -> {
                return false;
            }
        }
    }

    /** Table B: surplus sales of a role; the first success ends the run. */
    private static boolean surplus(ServerLevel level, Colony colony, Villager w, ColonyRole role) {
        return switch (role) {
            case MASON -> sell(level, colony, w, role, Goods.PLANKS, 32, 128, 1, "доски")
                    || sell(level, colony, w, role, Goods.STONE, 32, 256, 1, "строительный камень");
            case MINER -> sell(level, colony, w, role, Goods.COBBLE, 32, 256, 1, "камень")
                    || sell(level, colony, w, role, Goods.item(Items.COPPER_INGOT), 16, 32, 1, "медь")
                    || sell(level, colony, w, role, Goods.item(Items.IRON_INGOT), 8, 48, 2, "железо")
                    || sell(level, colony, w, role, Goods.item(Items.DIAMOND), 2, 8, 3, "алмазы");
            case FARMER -> sell(level, colony, w, role, Goods.item(Items.WHEAT), 20, 64, 1, "пшеница")
                    || sell(level, colony, w, role, Goods.item(Items.BREAD), 12, 32, 1, "хлеб")
                    || sell(level, colony, w, role, Goods.ROOTS, 24, 48, 1, "урожай");
            case CARPENTER -> sell(level, colony, w, role, Goods.LOGS, 16, 128, 1, "брёвна")
                    || sell(level, colony, w, role, Goods.SAPLINGS, 16, 32, 1, "саженцы");
            case HERDER -> sell(level, colony, w, role, Goods.item(Items.COD), 15, 32, 1, "треска")
                    || sell(level, colony, w, role, Goods.item(Items.SALMON), 12, 24, 1, "лосось")
                    || sell(level, colony, w, role, Goods.item(Items.EGG), 16, 16, 1, "яйца")
                    || sell(level, colony, w, role, Goods.item(Items.LEATHER), 8, 16, 1, "кожа")
                    || sell(level, colony, w, role, Goods.item(Items.FEATHER), 16, 24, 1, "перья")
                    || sell(level, colony, w, role, Goods.WOOL, 12, 24, 1, "шерсть");
            default -> false;
        };
    }

    /** Atomic: remove price emeralds from any storage, add the stack. */
    static boolean buy(ServerLevel level, Colony colony, Villager agent, ItemStack stack, int price, String label) {
        int count = stack.getCount();
        boolean ok = ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, Goods.EMERALD, price), List.of(stack));
        if (ok) {
            colony.boughtTrades++;
            ColonyManager.markDirty(level);
            WorkTracker.credit(agent, "покупка: " + label, count);
        }
        return ok;
    }

    /** Atomic: remove a batch of matching items from role storages, add price emeralds. Needs stock >= reserve + batch. */
    static boolean sell(ServerLevel level, Colony colony, Villager agent, ColonyRole role, Predicate<ItemStack> pred, int batch, int reserve,
                        int price, String label) {
        List<ColonyStorage.Slot> slots = ColonyStorage.usableFor(level, colony, role, false);
        if (ColonyStorage.count(slots, pred) < reserve + batch) {
            return false;
        }
        boolean ok = ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, slots, pred, batch),
                List.of(new ItemStack(Items.EMERALD, price)));
        if (ok) {
            colony.soldTrades++;
            ColonyManager.markDirty(level);
            WorkTracker.credit(agent, "продажа: " + label, batch);
        }
        return ok;
    }

    /** Alive iron golems standing in this colony's territory (colony box, +-12 Y). */
    public static int golemCount(ServerLevel level, Colony colony) {
        BlockPos c = colony.core();
        int r = colony.radius();
        AABB box = new AABB(c.getX() - r, c.getY() - 12, c.getZ() - r, c.getX() + r + 1, c.getY() + 13, c.getZ() + r + 1);
        int n = 0;
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, box, IronGolem::isAlive)) {
            if (ColonyManager.colonyAt(level, golem.blockPosition()) == colony) n++;
        }
        return n;
    }
}
