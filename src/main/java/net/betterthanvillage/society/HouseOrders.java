package net.betterthanvillage.society;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.BuildPlan;
import net.betterthanvillage.construction.HouseCatalog;
import net.betterthanvillage.construction.HousePlanner;
import net.betterthanvillage.construction.SiteSearch;
import net.betterthanvillage.politics.Countries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Houses a player orders on its own plot (docs_v2/04-land.md §Plots and houses): the elder offers the largest
 * village house that fits the plot, for the materials and the mason's work. The player pays the treasury and gets a
 * written contract; the village's masons build the house on the plot as public work, and it is the player's. A
 * plot is never bound to an order: the player may build on it freely instead.
 */
public final class HouseOrders {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Custom data of a contract: where and what to build. */
    public static final String ORDER = "better_than_village_house_order";

    /** A house that fits the plot, with its price. */
    public record Candidate(LandPlot plot, ResourceLocation template, BlockPos origin, int rotation, int w, int d, int price) {
    }

    private HouseOrders() {
    }

    /** The houses the elder offers to this player: one per plot of the player in the village without a house job. */
    public static List<Candidate> offers(ServerLevel level, Colony colony, UUID player) {
        List<Candidate> out = new ArrayList<>();
        if (!ColonyManager.terrainChangesAllowed(level)) return out;
        for (LandPlot plot : ColonyManager.data(level).land) {
            if (!player.equals(plot.owner()) || !colony.id().equals(plot.colony()) || plot.house() || ordered(colony, plot)) continue;
            Candidate c = plan(level, colony, plot);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** An unfinished house job of the plot's owner stands on the plot. */
    private static boolean ordered(Colony colony, LandPlot plot) {
        for (ConstructionJob job : colony.jobs) {
            if (plot.owner().equals(job.owner) && job.status != JobStatus.COMPLETE && plot.contains(job.box().getCenter())) return true;
        }
        return false;
    }

    /** The largest dwelling of the biome that fits inside the plot on clear ground, entrance toward the bell. */
    static Candidate plan(ServerLevel level, Colony colony, LandPlot plot) {
        String biome = HousePlanner.biomeKey(level, colony.core());
        List<HouseCatalog.Entry> homes = new ArrayList<>(HouseCatalog.homes(level, biome));
        homes.sort(Comparator.comparingInt((HouseCatalog.Entry e) -> -e.size().getX() * e.size().getZ()));
        int dx = plot.center().getX() - colony.core().getX(), dz = plot.center().getZ() - colony.core().getZ();
        for (HouseCatalog.Entry entry : homes) {
            SiteSearch.Spec spec = HouseCatalog.spec(level, entry.id());
            if (spec == null) continue;
            int facing = SiteSearch.rotationFacing(spec, dx, dz);
            for (int k = 0; k < 4; k++) {
                int rotation = (facing + k) % 4;
                boolean quarter = rotation % 2 == 1;
                int w = quarter ? spec.d() : spec.w(), d = quarter ? spec.w() : spec.d();
                int x0 = plot.center().getX() - w / 2, z0 = plot.center().getZ() - d / 2;
                if (x0 < plot.minX() || x0 + w - 1 > plot.maxX() || z0 < plot.minZ() || z0 + d - 1 > plot.maxZ()) continue;
                BlockPos origin = SiteSearch.fit(level, colony, x0, z0, w, d, spec.h(), plot);
                if (origin == null) continue;
                ConstructionJob job = HousePlanner.jobAt(entry.id(), spec, origin, rotation, w, d);
                List<BuildPlan.Step> steps = BuildPlan.load(level, job);
                if (steps == null) continue;
                int blocks = 0;
                for (BuildPlan.Step s : steps) if (!s.clear()) blocks++;
                int price = Math.min(128, 4 + (int) Math.ceil(blocks / (double) ColonyConfig.get().land.house_order_blocks_per_emerald));
                return new Candidate(plot, entry.id(), origin, rotation, w, d, price);
            }
        }
        return null;
    }

    /** The written contract the player takes home: what is built where, for how much. */
    public static ItemStack contract(ServerLevel level, Colony colony, Candidate c) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        String house = c.template().getPath().substring(c.template().getPath().lastIndexOf('/') + 1).replace('_', ' ');
        Component page = Component.translatable("message.better_than_village.land.order.page", Countries.villageName(colony), house,
                c.origin().toShortString(), c.price());
        String title = Component.translatable("message.better_than_village.land.order.title").getString();
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title.length() > 32 ? title.substring(0, 32) : title),
                Countries.villageName(colony), 0, List.of(Filterable.passThrough(page)), true));
        CompoundTag tag = new CompoundTag();
        tag.putLong("plot", c.plot().center().asLong());
        tag.putString("template", c.template().toString());
        tag.putLong("origin", c.origin().asLong());
        tag.putInt("rotation", c.rotation());
        CompoundTag root = new CompoundTag();
        root.put(ORDER, tag);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return book;
    }

    private static CompoundTag order(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || !stack.is(Items.WRITTEN_BOOK)) return null;
        CompoundTag root = cd.copyTag();
        return root.contains(ORDER) ? root.getCompound(ORDER) : null;
    }

    public static boolean isContract(ItemStack stack) {
        return order(stack) != null;
    }

    /** The contract's house still fits where it was offered (the player may have built on the plot meanwhile). */
    public static Candidate stillValid(ServerLevel level, Colony colony, UUID player, ItemStack contract) {
        CompoundTag tag = order(contract);
        if (tag == null || player == null) return null;
        BlockPos centre = BlockPos.of(tag.getLong("plot"));
        ColonySavedData data = ColonyManager.data(level);
        LandPlot plot = null;
        for (LandPlot p : data.land) {
            if (p.center().equals(centre) && player.equals(p.owner()) && colony.id().equals(p.colony())) plot = p;
        }
        if (plot == null || ordered(colony, plot)) return null;
        Candidate c = plan(level, colony, plot);
        if (c == null || !c.origin().equals(BlockPos.of(tag.getLong("origin"))) || !c.template().toString().equals(tag.getString("template"))) return null;
        return c;
    }

    /** Paid: the job goes to the masons, owned by the player. */
    public static boolean place(ServerLevel level, Colony colony, UUID player, ItemStack contract) {
        Candidate c = stillValid(level, colony, player, contract);
        if (c == null) return false;
        SiteSearch.Spec spec = HouseCatalog.spec(level, c.template());
        if (spec == null) return false;
        ConstructionJob job = HousePlanner.jobAt(c.template(), spec, c.origin(), c.rotation(), c.w(), c.d());
        job.owner = player;
        colony.jobs.add(job);
        ColonyManager.markDirty(level);
        LOGGER.info("[village] {} builds a {} for a player on its plot at {} ({} emeralds)", Countries.villageName(colony), c.template().getPath(),
                c.origin().toShortString(), c.price());
        return true;
    }
}
