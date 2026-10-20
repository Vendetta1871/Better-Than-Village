package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.colony.Territory;
import net.betterthanvillage.colony.Protection;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Property (house) deeds, land deeds and the land notice item (06 §2). All are paper with custom data. */
public final class Deeds {
    public static final String LAND = "better_than_village_land";
    public static final String LAND_COLONY = "colony";
    public static final String LAND_DIMENSION = "dimension";
    public static final String LAND_USED = "used";
    public static final String LAND_CENTER = "center";
    public static final String P_LEVEL = "better_than_village_property_level";
    public static final String P_COLONY = "better_than_village_property_colony";
    public static final String P_DIMENSION = "better_than_village_property_dimension";
    public static final String P_USED = "better_than_village_property_used";
    public static final String P_JOB = "better_than_village_property_job";
    public static final String P_ORIGIN = "better_than_village_property_origin";
    public static final String P_COMPLETE = "better_than_village_property_complete";
    public static final int LAND_RADIUS = 8;

    /** Commission site search: returns a job with template, origin, size and rotation set, or null. */
    @FunctionalInterface
    public interface CommissionSiteFinder {
        ConstructionJob find(ServerLevel level, Colony colony, ResourceLocation templateId, StructureTemplate template);
    }

    /** Replaceable by the construction subsystem with the real house-site search (05 §2.2 commissioned house). */
    public static volatile CommissionSiteFinder siteFinder = CommissionSites::find;

    private Deeds() {
    }

    // ---------------------------------------------------------------- item helpers

    private static CompoundTag data(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd == null ? new CompoundTag() : cd.copyTag();
    }

    private static void setData(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void setName(ItemStack stack, Component name, List<Component> lore) {
        if (!Objects.equals(stack.get(DataComponents.CUSTOM_NAME), name)) {
            stack.set(DataComponents.CUSTOM_NAME, name);
        }
        ItemLore itemLore = new ItemLore(lore);
        if (!Objects.equals(stack.get(DataComponents.LORE), itemLore)) {
            stack.set(DataComponents.LORE, itemLore);
        }
    }

    public static ItemStack propertyDeed(int level, UUID colony, String dimension) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = new CompoundTag();
        tag.putInt(P_LEVEL, level);
        tag.putString(P_COLONY, colony.toString());
        tag.putString(P_DIMENSION, dimension);
        setData(stack, tag);
        setName(stack, Msg.item("property_deed"), List.of(sizeLore(level)));
        return stack;
    }

    private static Component sizeLore(int level) {
        return Msg.item(level >= 2 ? "property_deed.medium" : "property_deed.small");
    }

    public static ItemStack landDeed(UUID colony, String dimension) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LAND, true);
        tag.putString(LAND_COLONY, colony.toString());
        tag.putString(LAND_DIMENSION, dimension);
        setData(stack, tag);
        setName(stack, Msg.item("land_deed"), List.of(Msg.item("land_deed.use", 2 * LAND_RADIUS + 1, 2 * LAND_RADIUS + 1)));
        return stack;
    }

    public static ItemStack landNotice(int price) {
        ItemStack stack = new ItemStack(Items.PAPER);
        setName(stack, Msg.item("land_notice"), List.of(Msg.item("land_notice.price", price), Msg.item("land_notice.warning")));
        return stack;
    }

    /** Property-deed level 0..3 (0 for anything that is not a property deed). */
    public static int propertyLevel(ItemStack stack) {
        if (!stack.is(Items.PAPER)) return 0;
        CompoundTag tag = data(stack);
        return tag.contains(P_LEVEL) ? Math.max(0, Math.min(3, tag.getInt(P_LEVEL))) : 0;
    }

    public static boolean isPropertyUsed(ItemStack stack) {
        CompoundTag tag = data(stack);
        return tag.getBoolean(P_USED) || !tag.getString(P_JOB).isEmpty();
    }

    public static boolean isLandDeed(ItemStack stack) {
        return stack.is(Items.PAPER) && data(stack).getBoolean(LAND);
    }

    /** The held stack loses one item; the used copy goes to the hand, the rest back to the inventory or the ground. */
    private static void replaceOne(Player player, InteractionHand hand, ItemStack held, ItemStack used) {
        ItemStack rest = held.copy();
        rest.shrink(1);
        player.setItemInHand(hand, used);
        if (!rest.isEmpty() && !player.getInventory().add(rest)) {
            player.drop(rest, false);
        }
    }

    // ---------------------------------------------------------------- house deed -> builder (06 §2.4)

    public static void builderOrder(ServerLevel level, Colony colony, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (isPropertyUsed(held)) {
            refresh(level.getServer(), held);
            Msg.actionBar(player, Msg.message("deed.already_used"));
            return;
        }
        ConstructionJob job = placeOrder(level, colony, player, held);
        if (job == null) {
            Msg.actionBar(player, Msg.message("deed.rejected"));
            return;
        }
        int lvl = propertyLevel(held);
        ItemStack used = held.copyWithCount(1);
        CompoundTag tag = data(used);
        tag.putBoolean(P_USED, true);
        tag.putString(P_JOB, job.id.toString());
        tag.putString(P_COLONY, colony.id().toString());
        tag.putString(P_DIMENSION, level.dimension().location().toString());
        tag.putLong(P_ORIGIN, job.origin.asLong());
        tag.putBoolean(P_COMPLETE, false);
        setData(used, tag);
        setName(used, Msg.item("property_deed.building", Msg.xyz(job.origin)), List.of(sizeLore(lvl), Msg.item("property_deed.used")));
        replaceOne(player, hand, held, used);
        Msg.actionBar(player, Msg.message("deed.accepted", Msg.xyz(job.origin)));
    }

    /** Places a commissioned house order; returns the new job or null. */
    static ConstructionJob placeOrder(ServerLevel level, Colony colony, Player player, ItemStack deed) {
        CompoundTag tag = data(deed);
        String deedColony = tag.getString(P_COLONY);
        if (!deedColony.isEmpty() && !deedColony.equals(colony.id().toString())) return null;
        if (!level.dimension().location().toString().equals(tag.getString(P_DIMENSION))) return null;
        int lvl = propertyLevel(deed);
        if (lvl < 1 || lvl > 3 || colony.propertyOwner != null || !colony.roadQueue.isEmpty() || !ColonyManager.terrainChangesAllowed(level)) {
            return null;
        }
        for (ConstructionJob job : colony.jobs) {
            if (player.getUUID().equals(job.owner) && job.status != JobStatus.COMPLETE) return null;
        }
        ResourceLocation templateId = commissionTemplate(level, colony, lvl);
        Optional<StructureTemplate> template = level.getStructureManager().get(templateId);
        if (template.isEmpty()) return null;
        ConstructionJob job = siteFinder.find(level, colony, templateId, template.get());
        if (job == null) return null;
        job.id = UUID.randomUUID();
        job.template = templateId;
        job.owner = player.getUUID();
        job.propertyLevel = lvl;
        job.catchup = false;
        job.status = JobStatus.QUEUED;
        colony.jobs.add(job);
        ColonyManager.markDirty(level);
        return job;
    }

    /** Biome group of the colony core (desert, savanna, snowy, taiga, else plains). */
    public static String biomeGroup(ServerLevel level, Colony colony) {
        String path = level.getBiome(colony.core()).unwrapKey().map(k -> k.location().getPath()).orElse("plains");
        if (path.contains("desert")) return "desert";
        if (path.contains("savanna")) return "savanna";
        if (path.contains("snow") || path.contains("frozen")) return "snowy";
        if (path.contains("taiga")) return "taiga";
        return "plains";
    }

    static ResourceLocation commissionTemplate(ServerLevel level, Colony colony, int lvl) {
        String biome = biomeGroup(level, colony);
        String name;
        if (lvl <= 1) {
            name = "small_house_1";
        } else {
            name = switch (biome) {
                case "taiga", "snowy" -> "medium_house_3";
                case "plains" -> "big_house_1";
                default -> "medium_house_2";
            };
        }
        return ResourceLocation.withDefaultNamespace("village/" + biome + "/houses/" + biome + "_" + name);
    }

    // ---------------------------------------------------------------- deed refresh

    /** Server tick: property-deed item refresh every 20 ticks for every online player. */
    public static void serverTick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshContainer(server, player.getInventory());
            refreshContainer(server, player.getEnderChestInventory());
            if (player.containerMenu != null) {
                for (Slot slot : player.containerMenu.slots) {
                    refreshIfUsed(server, slot.getItem());
                }
                refreshIfUsed(server, player.containerMenu.getCarried());
            }
        }
    }

    private static void refreshContainer(MinecraftServer server, Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            refreshIfUsed(server, container.getItem(i));
        }
    }

    private static void refreshIfUsed(MinecraftServer server, ItemStack stack) {
        if (!stack.isEmpty() && propertyLevel(stack) > 0 && isPropertyUsed(stack)) {
            refresh(server, stack);
        }
    }

    /** Re-syncs a used deed's completion flag, name and lore from its job. */
    static void refresh(MinecraftServer server, ItemStack stack) {
        CompoundTag tag = data(stack);
        boolean complete = tag.getBoolean(P_COMPLETE);
        ConstructionJob job = findJob(server, tag);
        if (job != null && job.countsAsCompleted() && !complete) {
            complete = true;
            tag.putBoolean(P_COMPLETE, true);
            setData(stack, tag);
        }
        BlockPos origin = tag.contains(P_ORIGIN) ? BlockPos.of(tag.getLong(P_ORIGIN)) : job != null ? job.origin : BlockPos.ZERO;
        Component name = Msg.item(complete ? "property_deed.complete" : "property_deed.building", Msg.xyz(origin));
        setName(stack, name, List.of(sizeLore(propertyLevel(stack)), Msg.item("property_deed.used")));
    }

    private static ConstructionJob findJob(MinecraftServer server, CompoundTag tag) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString(P_DIMENSION));
        if (dim == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (level == null) return null;
        try {
            UUID colonyId = UUID.fromString(tag.getString(P_COLONY));
            UUID jobId = UUID.fromString(tag.getString(P_JOB));
            Colony colony = ColonyManager.data(level).colony(colonyId);
            if (colony == null) return null;
            for (ConstructionJob job : colony.jobs) {
                if (job.id.equals(jobId)) return job;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------- land deed (06 §2.5)

    /** Right-click on a block with a land deed; returns true if handled (cancel vanilla). */
    public static boolean onUseOnBlock(ServerLevel level, Player player, InteractionHand hand, BlockPos pos) {
        ItemStack held = player.getItemInHand(hand);
        if (!isLandDeed(held)) {
            return false;
        }
        CompoundTag tag = data(held);
        if (tag.getBoolean(LAND_USED)) {
            Msg.chat(player, Msg.item("land_deed.used"));
            return true;
        }
        UUID colonyId;
        try {
            colonyId = UUID.fromString(tag.getString(LAND_COLONY));
        } catch (IllegalArgumentException e) {
            return true;
        }
        ColonySavedData data = ColonyManager.data(level);
        Colony colony = data.colony(colonyId);
        if (colony == null || !level.dimension().location().toString().equals(tag.getString(LAND_DIMENSION))
                || !plotValid(level, colony, player.getUUID(), pos)) {
            Msg.chat(player, Msg.message("land_unavailable"));
            return true;
        }
        LandPlot plot = new LandPlot(player.getUUID(), colony.id(), pos.immutable(), LAND_RADIUS);
        data.land.add(plot);
        data.landWarnings.removeIf(w -> player.getUUID().equals(w.player));
        data.setDirty();
        // The paper becomes the deed map of the plot (docs_v2/04-land.md §Plots and houses).
        replaceOne(player, hand, held, LandSales.deedMap(level, plot, Msg.item("land_deed.complete", Msg.xyz(pos))));
        Protection.sanitizeStorages(level);
        return true;
    }

    /** Plot validity for a land deed centred at c (06 §2.5). */
    public static boolean plotValid(ServerLevel level, Colony colony, UUID buyer, BlockPos c) {
        BlockPos core = colony.core();
        int r = LAND_RADIUS;
        if (!Territory.containsBox(colony, c.getX() - r, c.getZ() - r, c.getX() + r, c.getZ() + r, 0)) return false;
        if (Math.max(Math.abs(c.getX() - core.getX()), Math.abs(c.getZ() - core.getZ())) < 14) return false;
        ColonySavedData data = ColonyManager.data(level);
        if (foreignBlockIn(data, buyer, c.getX() - r, c.getZ() - r, c.getX() + r, c.getZ() + r, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            return false;
        }
        for (LandPlot plot : data.land) {
            if (plot.overlaps(c, r, r, 0)) {
                return false;
            }
        }
        BoundingBox square = new BoundingBox(c.getX() - r, level.getMinBuildHeight(), c.getZ() - r, c.getX() + r, level.getMaxBuildHeight(), c.getZ() + r);
        for (Colony other : data.colonies()) {
            for (ConstructionJob job : other.jobs) {
                BoundingBox b = job.box();
                if (b.maxX() >= square.minX() && b.minX() <= square.maxX() && b.maxZ() >= square.minZ() && b.minZ() <= square.maxZ()) {
                    return false;
                }
            }
        }
        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) return false;
                BlockPos column = new BlockPos(x, c.getY(), z);
                if (!level.getWorldBorder().isWithinBounds(column)) return false;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos surface = new BlockPos(x, y, z);
                if (VillagePieces.inSettlementPiece(level, surface) || isColonyStorage(level, surface)) return false;
                BlockState s = level.getBlockState(surface);
                if (s.is(Blocks.FARMLAND) || s.is(Blocks.BELL) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.SMOOTH_SANDSTONE)) return false;
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos p = surface.above(dy);
                    UUID owner = data.blockOwner(p);
                    if ((owner != null && !owner.equals(buyer)) || isColonyStorage(level, p)) return false;
                }
            }
        }
        return true;
    }

    private static boolean foreignBlockIn(ColonySavedData data, UUID buyer, int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LongSet set = data.playerBlocksInChunk(cx, cz);
                if (set == null) continue;
                for (long key : set) {
                    int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
                    if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) continue;
                    UUID owner = data.blockOwner(BlockPos.of(key));
                    if (owner != null && !owner.equals(buyer)) return true;
                }
            }
        }
        return false;
    }

    static boolean isColonyStorage(ServerLevel level, BlockPos pos) {
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (c.managedStorages.containsKey(pos) || c.storages.contains(pos)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- ownership (06 §2.6)

    /** Player may build here without warning: own land plot or own completed house. */
    public static boolean ownsPosition(ServerLevel level, UUID player, BlockPos pos) {
        for (LandPlot plot : ColonyManager.data(level).land) {
            if (plot.owner().equals(player) && plot.contains(pos)) return true;
        }
        for (Colony colony : ColonyManager.data(level).colonies()) {
            for (ConstructionJob job : colony.jobs) {
                if (player.equals(job.owner) && job.countsAsCompleted() && job.contains(pos)) return true;
            }
        }
        return false;
    }

    static boolean isRoadBlock(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.SMOOTH_SANDSTONE);
    }

    static boolean naturalGround(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.TUFF);
    }

    static String dimensionId(Level level) {
        return level.dimension().location().toString();
    }
}
