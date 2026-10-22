package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Household bookkeeping (docs_v2/03-economy.md §Households): one household per adult member, its bed, its home
 * chests and the starting capital handed out when a generated village is settled. Home chests are not part of the
 * village stock; the stock that is left is the treasury.
 */
public final class Households {
    /** Starting emeralds per profession before noise and scale (03 §Currency). */
    private static final int BASE_CAPITAL_DEFAULT = 8;
    /** Home chests are searched this far from the owner's bed (horizontal, vertical). */
    private static final int HOUSE_XZ = 6;
    private static final int HOUSE_Y = 3;

    /** A household owns at most this many home chests. */
    private static final int MAX_CHESTS = 3;

    private Households() {
    }

    private static int freeSlots(ServerLevel level, Household h) {
        int free = 0;
        for (ColonyStorage.Slot slot : ColonyStorage.usable(level, h)) {
            for (int i = 0; i < slot.container().getContainerSize(); i++) {
                if (slot.container().getItem(i).isEmpty()) free++;
            }
        }
        return free;
    }

    // ---------------------------------------------------------------- queries

    public static Household of(Colony colony, UUID villager) {
        return colony.households.get(villager);
    }

    /** The household owning a home chest at pos (either half of a double chest), or null. */
    public static Household ownerOf(Colony colony, BlockPos pos) {
        for (Household h : colony.households.values()) {
            if (h.chests.contains(pos)) {
                return h;
            }
        }
        return null;
    }

    /** The colony whose household owns the chest at pos, or null. */
    public static Colony colonyOfChest(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (ownerOf(colony, pos) != null) {
                return colony;
            }
        }
        return null;
    }

    public static boolean isHomeChest(ServerLevel level, BlockPos pos) {
        return colonyOfChest(level, pos) != null;
    }

    /** Emeralds in the household's home chests (loaded chests only). */
    public static int emeralds(ServerLevel level, Household h) {
        int total = 0;
        for (BlockPos pos : h.chests) {
            Container c = ColonyStorage.container(level, pos);
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(Items.EMERALD)) total += s.getCount();
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- census step

    /**
     * Creates households for adults without one, keeps beds and chests up to date, gives chests to households
     * without one and pays out pending starting capital. Runs once the generated village is fully populated.
     */
    public static void step(ServerLevel level, Colony colony, List<Villager> adults) {
        if (!colony.professionBootstrapComplete) {
            return;
        }
        long now = level.getGameTime();
        boolean settling = !colony.householdsSettled;
        List<Household> created = new ArrayList<>();
        for (Villager v : adults) {
            if (colony.isFounderParty(v.getUUID()) || !colony.roleOf(v.getUUID()).isProfession()) continue;
            Household h = colony.households.get(v.getUUID());
            if (h == null) {
                h = new Household(v.getUUID());
                h.greed = (level.random.nextFloat() * 2f - 1f) * 0.3f;
                h.founded = now;
                h.lastIncomeDay = Math.floorDiv(level.getDayTime(), 24000L);
                colony.households.put(h.owner, h);
                created.add(h);
            }
            BlockPos bed = ownedBed(level, colony, v);
            // HOME can be cleared for a moment (vanilla POI validation); a bed that still stands stays owned.
            if (bed != null || h.bed == null || (level.isLoaded(h.bed) && !level.getBlockState(h.bed).is(BlockTags.BEDS))) {
                h.bed = bed;
            }
        }
        for (Household h : colony.households.values()) {
            h.chests.removeIf(pos -> level.isLoaded(pos) && !isContainer(level, pos));
        }
        if (settling) {
            houseTheElder(level, colony, adults);
            handOutStartingCapital(level, colony, created);
            colony.householdsSettled = true;
        }
        boolean terrain = ColonyManager.terrainChangesAllowed(level);
        int placed = 0;
        List<Household> order = new ArrayList<>(colony.households.values());
        order.sort(Comparator.comparingLong((Household h) -> h.founded).thenComparing(h -> h.owner));
        for (Household h : order) {
            if (!h.hasChest() && h.bed != null) {
                claimHouseChest(level, colony, h);
            }
            if (!h.hasChest() && terrain && placed < 2 && placeHomeChest(level, colony, h, settling)) {
                placed++;
            } else if (h.hasChest() && h.chests.size() < MAX_CHESTS && terrain && placed < 2 && freeSlots(level, h) < 3
                    && placeHomeChest(level, colony, h, false)) {
                placed++;
            }
            payPendingCapital(level, colony, h);
        }
        ColonyManager.markDirty(level);
    }

    private static BlockPos ownedBed(ServerLevel level, Colony colony, Villager v) {
        Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
        if (home.isEmpty() || home.get().dimension() != level.dimension()) return null;
        BlockPos pos = home.get().pos();
        return colony.withinXZ(pos, 0) ? pos : null;
    }

    private static boolean isContainer(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity;
    }

    // ---------------------------------------------------------------- starting capital

    /**
     * Emeralds and starting kits created once when a generated village is settled ("the world existed before the
     * player"): each household gets its profession's base with +-25 % noise and the tools and food of its trade,
     * the treasury about a fifth of the emeralds.
     */
    private static void handOutStartingCapital(ServerLevel level, Colony colony, List<Household> created) {
        ColonyConfig.Economy cfg = ColonyConfig.get().economy;
        int total = 0;
        for (Household h : created) {
            ColonyRole role = colony.tradeOf(h.owner);
            double noise = 0.75 + level.random.nextDouble() * 0.5;
            h.pendingCapital = (int) Math.round(baseCapital(role) * noise * cfg.starting_capital_scale);
            h.pendingKit = true;
            total += h.pendingCapital;
        }
        int treasury = (int) Math.round(total * cfg.treasury_share / Math.max(0.05, 1 - cfg.treasury_share));
        while (treasury > 0) {
            ItemStack stack = new ItemStack(Items.EMERALD, Math.min(64, treasury));
            int n = stack.getCount();
            ColonyStorage.deposit(level, colony, stack);
            treasury -= n;
            if (!stack.isEmpty()) break;
        }
    }

    static int baseCapital(ColonyRole role) {
        return switch (role) {
            case ELDER -> 24;
            case SMITH -> 16;
            case LIBRARIAN, HEALER -> 14;
            case MASON, MINER -> 12;
            case CARPENTER -> 10;
            case FARMER, HERDER -> 8;
            case WARRIOR -> 6;
            default -> BASE_CAPITAL_DEFAULT;
        };
    }

    /** Tools, inputs and a few days of food a household of this trade starts with. */
    static List<ItemStack> startingKit(ColonyRole role) {
        List<ItemStack> kit = new ArrayList<>();
        kit.add(new ItemStack(Items.BREAD, 6));
        switch (role) {
            case FARMER -> {
                kit.add(new ItemStack(Items.STONE_HOE));
                kit.add(new ItemStack(Items.WHEAT_SEEDS, 16));
                // Pumpkins for the village's golems (and the herder's hay is wheat).
                kit.add(new ItemStack(Items.PUMPKIN_SEEDS, 4));
            }
            case HERDER -> {
                kit.add(new ItemStack(Items.SHEARS));
                kit.add(new ItemStack(Items.BUCKET));
                kit.add(new ItemStack(Items.FISHING_ROD));
                kit.add(new ItemStack(Items.WHEAT, 16));
            }
            case CARPENTER -> {
                kit.add(new ItemStack(Items.STONE_AXE));
                kit.add(new ItemStack(Items.OAK_SAPLING, 4));
            }
            case MINER -> {
                kit.add(new ItemStack(Items.STONE_PICKAXE));
                kit.add(new ItemStack(Items.TORCH, 16));
            }
            default -> {
            }
        }
        return kit;
    }

    private static void payPendingCapital(ServerLevel level, Colony colony, Household h) {
        if ((h.pendingCapital <= 0 && !h.pendingKit) || !h.hasChest()) return;
        Container c = ColonyStorage.container(level, h.chests.get(0));
        if (c == null) return;
        if (h.pendingCapital > 0) {
            ItemStack stack = new ItemStack(Items.EMERALD, Math.min(64, h.pendingCapital));
            int before = stack.getCount();
            ColonyStorage.insert(c, stack);
            h.pendingCapital -= before - stack.getCount();
        }
        if (h.pendingKit) {
            h.pendingKit = false;
            for (ItemStack stack : startingKit(colony.tradeOf(h.owner))) {
                ColonyStorage.insert(c, stack);
            }
        }
    }

    // ---------------------------------------------------------------- home chests

    /** Takes an unowned village chest inside the owner's house (near its bed), both halves of a double chest. */
    private static void claimHouseChest(ServerLevel level, Colony colony, Household h) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : colony.looseChests) {
            if (colony.managedStorages.containsKey(pos) || !level.isLoaded(pos)) continue;
            if (Math.abs(pos.getX() - h.bed.getX()) > HOUSE_XZ || Math.abs(pos.getZ() - h.bed.getZ()) > HOUSE_XZ
                    || Math.abs(pos.getY() - h.bed.getY()) > HOUSE_Y) continue;
            if (isHomeChest(level, pos) || Protection.isProtectedContainer(level, pos) || Protection.playerProperty(level, pos)) continue;
            double d = pos.distSqr(h.bed);
            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        if (best == null) return;
        claim(colony, h, best.immutable());
        BlockPos other = Protection.otherChestHalf(level, best);
        if (other != null) claim(colony, h, other.immutable());
    }

    private static void claim(Colony colony, Household h, BlockPos pos) {
        if (!h.chests.contains(pos)) h.chests.add(pos);
        colony.storages.remove(pos);
        colony.looseChests.remove(pos);
    }

    /**
     * Places a single chest for the household: next to its bed, or by the village centre while it is homeless.
     * The first chests of a generated village are free; later ones cost the treasury two logs.
     */
    private static boolean placeHomeChest(ServerLevel level, Colony colony, Household h, boolean free) {
        BlockPos site = h.bed != null ? bedsideSite(level, colony, h.bed) : null;
        if (site == null) site = centreSite(level, colony);
        if (site == null) return false;
        if (!free) {
            // A household pays for its own extra chest; a homeless one's first chest is paid by the treasury.
            List<ColonyStorage.Slot> payer = h.hasChest() ? ColonyStorage.usable(level, h) : ColonyStorage.treasury(level, colony);
            if (Chests.take(payer, s -> s.is(ItemTags.LOGS), 2).isEmpty() && Chests.take(payer, s -> s.is(ItemTags.PLANKS), 8).isEmpty()) return false;
        }
        Direction facing = h.bed != null ? facingAway(site, h.bed) : Direction.NORTH;
        BlockPos at = site;
        SafeEditScope.run(() -> level.setBlock(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 3));
        if (!isContainer(level, site)) return false;
        claim(colony, h, site.immutable());
        return true;
    }

    private static Direction facingAway(BlockPos chest, BlockPos bed) {
        int dx = chest.getX() - bed.getX();
        int dz = chest.getZ() - bed.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** A free floor cell beside the bed (head side first) that keeps the bed reachable and the doors clear. */
    private static BlockPos bedsideSite(ServerLevel level, Colony colony, BlockPos bed) {
        if (!level.isLoaded(bed)) return null;
        BlockState state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)) return null;
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? bed : bed.relative(facing);
        BlockPos foot = head.relative(facing.getOpposite());
        for (BlockPos half : new BlockPos[]{head, foot}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = half.relative(d);
                if (c.equals(head) || c.equals(foot)) continue;
                if (!chestFits(level, colony, c)) continue;
                if (freeNeighbours(level, head, foot, c) == 0) continue;
                return c;
            }
        }
        return null;
    }

    /** Walkable cells next to the bed that remain once a chest stands at taken. */
    private static int freeNeighbours(ServerLevel level, BlockPos head, BlockPos foot, BlockPos taken) {
        int n = 0;
        for (BlockPos half : new BlockPos[]{head, foot}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = half.relative(d);
                if (c.equals(head) || c.equals(foot) || c.equals(taken)) continue;
                if (level.getBlockState(c).getCollisionShape(level, c).isEmpty()
                        && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()) n++;
            }
        }
        return n;
    }

    /** Square rings around the core for a homeless household's chest. */
    private static BlockPos centreSite(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        for (int d = 3; d <= 20; d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != d) continue;
                    int px = core.getX() + x;
                    int pz = core.getZ() + z;
                    if (!level.hasChunk(px >> 4, pz >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                    if (Math.abs(y - core.getY()) > 3) continue;
                    BlockPos pos = new BlockPos(px, y, pz);
                    BlockState ground = level.getBlockState(pos.below());
                    // Natural ground only: the heightmap also finds roofs and market stalls nobody can walk onto.
                    if (!(ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(BlockTags.BASE_STONE_OVERWORLD)
                            || ground.is(Blocks.GRAVEL)) || ground.is(Blocks.FARMLAND)) continue;
                    if (chestFits(level, colony, pos)) return pos;
                }
            }
        }
        return null;
    }

    /** Air with air above on sturdy ground, modifiable, not by a door, bed, bell or another chest. */
    static boolean chestFits(ServerLevel level, Colony colony, BlockPos c) {
        if (!level.isLoaded(c) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()) return false;
        if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) return false;
        if (!Protection.terrainModifiable(level, c) || colony.repairs.containsKey(c)) return false;
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(c) || Protection.inPlayerOwnedJob(level, c)) return false;
        for (ConstructionJob job : colony.jobs) {
            if (job.isActive() && Protection.inJobBox(job, c)) return false;
        }
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, 0, -1), c.offset(1, 1, 1))) {
            if (p.equals(c)) continue;
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.DOORS) || s.getBlock() instanceof BellBlock || s.getBlock() instanceof ChestBlock) return false;
        }
        return true;
    }

    /** A newborn's household: the bed of its paid-for house and a home chest beside it (03 §Births). */
    static void createChild(ServerLevel level, Colony colony, UUID child, BlockPos bed, float greed, int lineage) {
        Household h = new Household(child);
        h.lineage = lineage;
        h.bed = bed;
        h.greed = greed;
        h.founded = level.getGameTime();
        h.lastIncomeDay = Math.floorDiv(level.getDayTime(), 24000L);
        colony.households.put(child, h);
        if (ColonyManager.terrainChangesAllowed(level)) placeHomeChest(level, colony, h, true);
    }

    /**
     * The workplace bought with the child's house includes the basic tools and inputs of its trade; its first
     * days of food come from the treasury's stock (03 §Births).
     */
    static void furnishChild(ServerLevel level, Colony colony, UUID child, ColonyRole trade) {
        Household h = colony.households.get(child);
        if (h == null || !h.hasChest()) return;
        List<ColonyStorage.Slot> home = ColonyStorage.usable(level, h);
        for (ItemStack stack : startingKit(trade)) {
            if (stack.is(Items.BREAD)) {
                List<ItemStack> food = Chests.take(ColonyStorage.treasury(level, colony), ColonyStorage::isFood, 3);
                Chests.put(home, food);
            } else {
                Chests.put(home, List.of(stack));
            }
        }
    }

    // ---------------------------------------------------------------- end of a household

    /**
     * A villager left for good: what its home chests hold passes to the treasury, the chests stay in the house for
     * the next household there, and the household ends.
     */
    public static boolean dissolve(ServerLevel level, Colony colony, UUID villager) {
        Household h = colony.households.remove(villager);
        if (h == null) return false;
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        for (BlockPos pos : h.chests) {
            Container c = ColonyStorage.container(level, pos);
            if (c != null) TreasuryHall.empty(c, treasury);
            if (!colony.looseChests.contains(pos)) colony.looseChests.add(pos);
        }
        return true;
    }

    /**
     * Settlement (03 §Households): the elder lives in a house, since the treasury is kept there. A homeless elder
     * takes the bed of another household, which looks for a new bed itself.
     */
    private static void houseTheElder(ServerLevel level, Colony colony, List<Villager> adults) {
        Household elder = colony.elder == null ? null : colony.households.get(colony.elder);
        if (elder == null || elder.bed != null) return;
        Villager elderV = null, otherV = null;
        Household other = null;
        for (Villager v : adults) {
            if (v.getUUID().equals(colony.elder)) elderV = v;
        }
        for (Villager v : adults) {
            Household h = colony.households.get(v.getUUID());
            if (h != null && h != elder && h.bed != null) {
                other = h;
                otherV = v;
                break;
            }
        }
        if (elderV == null || other == null) return;
        GlobalPos home = GlobalPos.of(level.dimension(), other.bed);
        otherV.getBrain().eraseMemory(MemoryModuleType.HOME);
        elderV.getBrain().setMemory(MemoryModuleType.HOME, home);
        elder.bed = other.bed;
        other.bed = null;
    }
}
