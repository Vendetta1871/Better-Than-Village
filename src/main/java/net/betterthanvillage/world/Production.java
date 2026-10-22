package net.betterthanvillage.world;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Treasury;
import net.betterthanvillage.economy.Values;
import net.betterthanvillage.economy.Values.Family;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.List;
import java.util.function.Predicate;

/**
 * A day of work of an abstract household (docs_v2/08-simulation.md §Two modes): what its worker would have made,
 * from the inputs and with the tool of its trade found in its own (virtual) chests. Products pay the levy in kind
 * like real deliveries; tools wear out every few days. Masons put their day into the village's construction, which
 * is placed when the village loads.
 */
public final class Production {
    /** A tool of the trade wears out after this many days of work. */
    private static final int TOOL_DAYS = 8;
    /** Blocks a mason places per day. */
    static final int MASON_BLOCKS = 240;

    private Production() {
    }

    /** One day of the household's worker. Returns the number of items produced. */
    public static int day(ServerLevel level, Colony colony, Household h, long day) {
        if (!h.hasChest() || Caravans.away(level, h.owner) || colony.isFounderParty(h.owner)) return 0;
        List<ColonyStorage.Slot> home = ColonyStorage.usable(level, h);
        if (home.isEmpty()) return 0;
        Work w = new Work(level, colony, h, home, day);
        switch (colony.roleOf(h.owner)) {
            case FARMER -> {
                double f = w.tool(Family.HOE) ? 1 : 0.4;
                w.give(Items.BREAD, 4 * f);
                w.give(Items.WHEAT, 6 * f);
                w.give(Items.WHEAT_SEEDS, 4 * f);
            }
            case HERDER -> {
                double f = w.use(s -> s.is(Items.WHEAT), 2) ? 1 : 0.5;
                w.give(Items.COOKED_BEEF, 2 * f);
                w.give(Items.LEATHER, 1 * f);
                w.give(Items.EGG, 1);
                if (w.tool(Family.SHEARS)) w.give(Items.WHITE_WOOL, 2 * f);
                if (w.tool(Family.FISHING_ROD)) w.give(Items.COOKED_COD, 2);
            }
            case CARPENTER -> {
                double f = (w.tool(Family.AXE) ? 1 : 0.4) * (w.use(s -> s.is(ItemTags.SAPLINGS), 1) ? 1 : 0.6);
                w.give(log(level, colony), 8 * f);
                w.give(Items.STICK, 8 * f);
                w.give(Items.OAK_PLANKS, 8 * f);
            }
            case MINER -> {
                double f = (w.tool(Family.PICKAXE) ? 1 : 0.3) * (w.use(s -> s.is(Items.TORCH), 2) ? 1 : 0.7);
                w.bulk(Items.COBBLESTONE, 48 * f);
                w.give(Items.COAL, 3 * f);
                w.give(Items.RAW_IRON, 2 * f);
                w.give(Items.RAW_COPPER, 2 * f);
                w.give(Items.REDSTONE, 1 * f);
                if (w.every(20)) w.give(Items.DIAMOND, 1);
            }
            case SMITH -> {
                int iron = Math.min(4, Chests.count(home, s -> s.is(Items.RAW_IRON)));
                if (iron > 0 && w.use(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), (iron + 1) / 2)) {
                    w.use(s -> s.is(Items.RAW_IRON), iron);
                    w.give(Items.IRON_INGOT, iron);
                }
                Item[] stone = {Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_HOE};
                Item[] irons = {Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_HOE};
                int pick = (int) Math.floorMod(day, 3L);
                if (w.every(2) && Chests.count(home, s -> s.is(Items.IRON_INGOT)) >= 3 && Chests.count(home, s -> s.is(Items.STICK)) >= 2) {
                    w.use(s -> s.is(Items.IRON_INGOT), 3);
                    w.use(s -> s.is(Items.STICK), 2);
                    w.give(irons[pick], 1);
                } else if (Chests.count(home, s -> s.is(Items.COBBLESTONE)) >= 3 && w.use(s -> s.is(Items.STICK), 2)) {
                    w.use(s -> s.is(Items.COBBLESTONE), 3);
                    w.give(stone[pick], 1);
                }
            }
            case LIBRARIAN -> {
                int cane = Math.min(3, Chests.count(home, s -> s.is(Items.SUGAR_CANE)));
                if (cane > 0 && w.use(s -> s.is(Items.SUGAR_CANE), cane)) w.give(Items.PAPER, cane);
                if (Chests.count(home, s -> s.is(Items.PAPER)) >= 3 && w.use(s -> s.is(Items.LEATHER), 1)) {
                    w.use(s -> s.is(Items.PAPER), 3);
                    w.give(Items.BOOK, 1);
                }
            }
            case HEALER -> {
                if (w.use(s -> s.is(Items.GLASS) || s.is(Items.GLASS_BOTTLE), 1)) {
                    w.giveStack(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
                }
            }
            case MASON -> {
                // Birth houses first (paid by the parents), then the village's unfinished plots.
                int used = net.betterthanvillage.economy.Births.build(level, colony, MASON_BLOCKS);
                if (used == 0 && construction(colony) > 0) {
                    colony.catchupLabor = Math.min(12000, colony.catchupLabor + MASON_BLOCKS);
                    used = MASON_BLOCKS;
                }
                h.labor = Math.min(1_000_000, h.labor + used);
            }
            default -> {
            }
        }
        return w.produced;
    }

    /**
     * Unfinished village construction the masons' abstract days go to: the jobs are flagged for the catch-up
     * builder, which places their blocks when the village loads. Returns the number of jobs.
     */
    public static int construction(Colony colony) {
        int n = 0;
        for (ConstructionJob job : colony.jobs) {
            if (job.owner != null || job.completedOnce || job.status == JobStatus.COMPLETE) continue;
            if (job.status != JobStatus.QUEUED && job.status != JobStatus.BUILDING) continue;
            if (net.betterthanvillage.construction.CatchupConstruction.handedOver(colony).contains(job.id)) continue;
            job.catchup = true;
            if (job.builder != null) job.releaseBuilder();
            n++;
        }
        colony.catchupHouses = Math.min(4, n);
        return n;
    }

    private static Item log(ServerLevel level, Colony colony) {
        String path = level.getBiome(colony.core()).unwrapKey().map(k -> k.location().getPath()).orElse("plains");
        if (path.contains("savanna")) return Items.ACACIA_LOG;
        if (path.contains("taiga") || path.contains("snow")) return Items.SPRUCE_LOG;
        return Items.OAK_LOG;
    }

    /** One household's day: inputs taken from and products put into its own chests. */
    private static final class Work {
        final ServerLevel level;
        final Colony colony;
        final Household h;
        final List<ColonyStorage.Slot> home;
        final long day;
        int produced;

        Work(ServerLevel level, Colony colony, Household h, List<ColonyStorage.Slot> home, long day) {
            this.level = level;
            this.colony = colony;
            this.h = h;
            this.home = home;
            this.day = day;
        }

        /** True once every n days for this household. */
        boolean every(int n) {
            return Math.floorMod(day + h.owner.hashCode(), (long) n) == 0;
        }

        /** The household holds a tool of the family; it wears out every few days. */
        boolean tool(Family family) {
            Predicate<ItemStack> pred = s -> Values.family(s) == family;
            if (Chests.count(home, pred) <= 0) return false;
            if (every(TOOL_DAYS)) Chests.take(home, pred, 1);
            return true;
        }

        /** Takes n matching items; false (and nothing taken) when fewer are held. */
        boolean use(Predicate<ItemStack> pred, int n) {
            return n <= 0 || Chests.total(Chests.take(home, pred, n)) >= n;
        }

        void give(Item item, double amount) {
            int n = (int) Math.floor(amount + 1e-9);
            // The fraction is produced on a share of the days.
            double frac = amount - n;
            if (frac > 0 && Math.floorMod(day * 31 + h.owner.hashCode() + item.hashCode(), 100L) < frac * 100) n++;
            while (n > 0) {
                int k = Math.min(n, item.getDefaultMaxStackSize());
                giveStack(new ItemStack(item, k));
                n -= k;
            }
        }

        /** Cheap bulk only up to what a household keeps. */
        void bulk(Item item, double amount) {
            ItemStack like = new ItemStack(item);
            int room = Treasury.bulkCap(like) - Chests.count(home, s -> s.is(item));
            give(item, Math.min(amount, Math.max(0, room)));
        }

        void giveStack(ItemStack stack) {
            int n = stack.getCount();
            Treasury.levy(level, colony, h, stack);
            List<ItemStack> rest = Chests.put(home, List.of(stack));
            produced += n - Chests.total(rest);
        }
    }

    /** True for a duty that produces goods in the abstract economy. */
    public static boolean producer(ColonyRole role) {
        return switch (role) {
            case FARMER, HERDER, CARPENTER, MINER, SMITH, LIBRARIAN, HEALER, MASON -> true;
            default -> false;
        };
    }
}
