package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.LandPlot;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.economy.BirthOrder;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.mixin.society.MapDataAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The elder sells land to the player (docs_v2/04-land.md §Plots and houses): plots, placed with a land deed, and
 * empty houses. The player gets a deed map: a filled map centred on the property with red X markers at its
 * corners. Ownership lives on the server ({@link ColonySavedData#land}); the map is a receipt.
 */
public final class LandSales {
    /** Custom data of a house offer / deed map: the house box. */
    public static final String HOUSE = "better_than_village_house";
    /** Custom data of a deed map: the property centre. */
    public static final String DEED = "better_than_village_deed";
    /** Houses the elder offers at once. */
    private static final int HOUSE_OFFERS = 2;
    /** A sold house's plot reaches this far around the house. */
    private static final int HOUSE_MARGIN = 1;

    /** A house: its box and its beds. */
    public record House(BoundingBox box, List<BlockPos> beds) {
        BlockPos center() {
            return box.getCenter();
        }
    }

    /** Offer maps per house (box) per level, so reopening the trade screen does not make new maps. */
    private static final Map<ServerLevel, Map<BoundingBox, ItemStack>> OFFER_MAPS = new java.util.WeakHashMap<>();

    private LandSales() {
    }

    // ---------------------------------------------------------------- deed maps

    /** A deed map of the property: centred on it, red X markers at its corners, named after it. */
    public static ItemStack deedMap(ServerLevel level, LandPlot plot, Component name) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        MapId id = level.getFreeMapId();
        MapItemSavedData data = MapDataAccess.btv$create(plot.center().getX(), plot.center().getZ(), (byte) 0, true, false, false, level.dimension());
        level.setMapData(id, data);
        map.set(DataComponents.MAP_ID, id);
        int[][] corners = {{plot.minX(), plot.minZ()}, {plot.maxX(), plot.minZ()}, {plot.minX(), plot.maxZ()}, {plot.maxX(), plot.maxZ()}};
        for (int i = 0; i < corners.length; i++) {
            MapItemSavedData.addTargetDecoration(map, new BlockPos(corners[i][0], 0, corners[i][1]), "btv_corner_" + i, MapDecorationTypes.RED_X);
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong(DEED, plot.center().asLong());
        map.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        map.set(DataComponents.CUSTOM_NAME, name);
        return map;
    }

    // ---------------------------------------------------------------- empty houses

    /**
     * Houses nobody lives in (the owner died, or left over from worldgen): pieces of the generated village and
     * houses built by the village that have beds, where no household sleeps, no villager has its home, nothing
     * of the village is stored, and that no birth is waiting for.
     */
    public static List<House> emptyHouses(ServerLevel level, Colony colony) {
        List<BoundingBox> boxes = new ArrayList<>();
        StructureStart start = level.structureManager().getStructureWithPieceAt(colony.core(), StructureTags.VILLAGE);
        if (start.isValid()) {
            for (StructurePiece piece : start.getPieces()) boxes.add(piece.getBoundingBox());
        }
        for (ConstructionJob job : colony.jobs) {
            if (job.owner != null || !job.isComplete() || reservedForBirth(colony, job)) continue;
            boxes.add(job.box());
        }
        Map<BoundingBox, List<BlockPos>> beds = new HashMap<>();
        PoiManager poi = level.getPoiManager();
        poi.getInRange(t -> t.is(PoiTypes.HOME), colony.core(), colony.radius(), PoiManager.Occupancy.ANY).forEach(record -> {
            BlockPos bed = record.getPos();
            if (!colony.containsXZ(bed)) return;
            for (BoundingBox box : boxes) {
                if (box.isInside(bed)) {
                    beds.computeIfAbsent(box, b -> new ArrayList<>()).add(bed);
                    break;
                }
            }
        });
        List<House> out = new ArrayList<>();
        ColonySavedData data = ColonyManager.data(level);
        for (Map.Entry<BoundingBox, List<BlockPos>> e : beds.entrySet()) {
            BoundingBox box = e.getKey();
            if (lived(level, colony, box) || Protection.inPlayerOwnedJob(level, box.getCenter())) continue;
            boolean owned = false;
            for (LandPlot plot : data.land) {
                if (plot.intersects(box)) owned = true;
            }
            if (!owned) out.add(new House(box, e.getValue()));
        }
        out.sort(Comparator.comparingDouble(h -> h.center().distSqr(colony.core())));
        return out;
    }

    private static boolean reservedForBirth(Colony colony, ConstructionJob job) {
        for (BirthOrder order : colony.birthOrders) {
            if (job.id.equals(order.job)) return true;
        }
        return false;
    }

    /** Someone lives or keeps things in the box: a household bed or chest, a villager's home, a treasury chest, the hall. */
    private static boolean lived(ServerLevel level, Colony colony, BoundingBox box) {
        for (Household h : colony.households.values()) {
            if (h.bed != null && box.isInside(h.bed)) return true;
            for (BlockPos chest : h.chests) {
                if (box.isInside(chest)) return true;
            }
        }
        if (colony.hallBed != null && box.inflatedBy(6).isInside(colony.hallBed)) return true;
        for (BlockPos pos : colony.managedStorages.keySet()) {
            if (box.isInside(pos)) return true;
        }
        for (Villager v : ColonyManager.members(level, colony)) {
            GlobalPos home = v.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
            if (home != null && box.isInside(home.pos())) return true;
        }
        return false;
    }

    /** An empty house on offer: its deed map and its number of beds. */
    public record HouseOffer(ItemStack map, int beds) {
    }

    /** Elder offers for empty houses: the nearest ones to the bell, a deed map each. */
    public static List<HouseOffer> houseOffers(ServerLevel level, Colony colony) {
        List<HouseOffer> out = new ArrayList<>();
        Map<BoundingBox, ItemStack> maps = OFFER_MAPS.computeIfAbsent(level, l -> new HashMap<>());
        for (House house : emptyHouses(level, colony)) {
            if (out.size() >= HOUSE_OFFERS) break;
            ItemStack map = maps.computeIfAbsent(house.box(), b -> {
                LandPlot plot = LandPlot.ofBox(UUID.randomUUID(), colony.id(), b, HOUSE_MARGIN);
                ItemStack m = deedMap(level, plot, Msg.item("house_deed", Msg.xyz(b.getCenter())));
                CompoundTag tag = m.get(DataComponents.CUSTOM_DATA).copyTag();
                tag.putIntArray(HOUSE, new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
                m.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                m.set(DataComponents.LORE, new ItemLore(List.of(Msg.item("house_deed.beds", house.beds().size()))));
                return m;
            });
            out.add(new HouseOffer(map.copy(), house.beds().size()));
        }
        return out;
    }

    /** The house box of a house offer or deed map, or null. */
    public static BoundingBox houseBox(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || !stack.is(Items.FILLED_MAP)) return null;
        int[] b = cd.copyTag().getIntArray(HOUSE);
        return b.length == 6 ? new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]) : null;
    }

    /** True while the house of the offer is still for sale. */
    public static boolean forSale(ServerLevel level, Colony colony, ItemStack offer) {
        BoundingBox box = houseBox(offer);
        if (box == null) return true;
        for (House house : emptyHouses(level, colony)) {
            if (house.box().equals(box)) return true;
        }
        return false;
    }

    /** A house offer was paid: the house becomes the player's property. */
    public static void sellHouse(ServerLevel level, Colony colony, UUID buyer, ItemStack offer) {
        BoundingBox box = houseBox(offer);
        if (box == null || buyer == null) return;
        ColonySavedData data = ColonyManager.data(level);
        data.land.add(LandPlot.ofBox(buyer, colony.id(), box, HOUSE_MARGIN));
        data.landWarnings.removeIf(w -> buyer.equals(w.player));
        Map<BoundingBox, ItemStack> maps = OFFER_MAPS.get(level);
        if (maps != null) maps.remove(box);
        data.setDirty();
        Protection.sanitizeStorages(level);
    }
}
