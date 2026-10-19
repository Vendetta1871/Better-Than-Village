package net.betterthanvillage.food;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.ToolSupply;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Farmer (04 §2): harvest/replant, till, plant, village hay, seed gathering, new fields, bread baking. */
public final class FarmerTask extends ColonyTask {
    public static final String STAT_HARVEST = "сбор и пересев";
    public static final String STAT_PLANT = "посадка";
    public static final String STATUS_RELEASED = "цель фермера освобождена для стройплощадки";

    private enum State { PREPARE, SCAN, HARVEST, HAY, TILL_PLANT, GATHER_SEEDS, FILL_BUCKET, NEW_FIELD, DELIVER }

    private static final int SPAN = 128;
    /** Static offset list over [-128, 128]², sorted by distance from the origin (stable). */
    private static final int[] OX;
    private static final int[] OZ;
    private static final int[] OD;

    static {
        List<int[]> list = new ArrayList<>((2 * SPAN + 1) * (2 * SPAN + 1));
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                list.add(new int[]{dx, dz, dx * dx + dz * dz});
            }
        }
        list.sort(Comparator.comparingInt(a -> a[2]));
        OX = new int[list.size()];
        OZ = new int[list.size()];
        OD = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            OX[i] = list.get(i)[0];
            OZ[i] = list.get(i)[1];
            OD[i] = list.get(i)[2];
        }
    }

    private State state;
    private boolean done;
    private int actions;
    private boolean sweepComplete;
    private BlockPos target;
    private BlockPos moveTarget;
    private double bestDist;
    private long lastMoveProgress;
    private int cursor;
    private int fieldCursor;
    private int step;
    private long nextAct;
    private long cooldown;
    private BlockPos restoreC, emptyC, harvestC, hayC, expandC, waterC, grassC;
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    /** Pumpkin fruit spots reserved by this task instance; never cleared (04 §2.7). */
    private final Set<BlockPos> pumpkinSpots = new HashSet<>();
    private final BlockWorkTimer timer = new BlockWorkTimer();

    public FarmerTask() {
        super(12000);
    }

    // ---------------------------------------------------------------- item sets

    public static boolean isFarmerSeed(ItemStack s) {
        return s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.PUMPKIN_SEEDS);
    }

    private static Block cropFor(ItemStack s) {
        if (s.is(Items.CARROT)) return Blocks.CARROTS;
        if (s.is(Items.POTATO)) return Blocks.POTATOES;
        if (s.is(Items.BEETROOT_SEEDS)) return Blocks.BEETROOTS;
        if (s.is(Items.PUMPKIN_SEEDS)) return Blocks.PUMPKIN_STEM;
        return Blocks.WHEAT;
    }

    private static Item productOf(Block crop) {
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT;
        return Items.WHEAT;
    }

    private static Item seedOf(Block crop) {
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return Items.WHEAT_SEEDS;
    }

    private static int seedCount(Villager v) {
        return FoodUtil.count(v.getInventory(), FarmerTask::isFarmerSeed);
    }

    public String debugState() {
        return state == null ? "ожидание" : state.name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- lifecycle (04 §2.3)

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && v.getTradingPlayer() == null && level.getGameTime() >= cooldown
                && ColonyManager.terrainChangesAllowed(level)
                && colony.roleOf(v.getUUID()) == ColonyRole.FARMER && colony.hasStorage();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        return !v.isSleeping() && v.getTradingPlayer() == null && colony != null
                && colony.roleOf(v.getUUID()) == ColonyRole.FARMER && !done;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        state = State.PREPARE;
        done = false;
        actions = 0;
        sweepComplete = false;
        target = null;
        moveTarget = null;
        clearCandidates();
        cursor = 0;
        nextAct = now;
        blacklist.values().removeIf(exp -> exp <= now);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
        v.getNavigation().stop();
        v.getBrain().eraseMemory(ModRegistries.FARM_TARGET_POS.get());
        target = null;
        moveTarget = null;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (now % 200 == 0) net.betterthanvillage.BetterThanVillage.LOGGER.info("DBGFOOD " + "farmer "+debugState()+" t="+target+" actions="+actions + " @" + v.blockPosition() + " storage=" + net.betterthanvillage.colony.ColonyStorage.nearest(level, colony(level, v), v.blockPosition()));
        if (now < nextAct) return;
        nextAct = now + 2;
        if (!ColonyManager.terrainChangesAllowed(level)) return;
        Colony colony = colony(level, v);
        if (colony == null) return;
        if (state == State.PREPARE || state == State.DELIVER) {
            storageVisit(level, v, colony, now);
        } else if (actions >= 64) {
            state = State.DELIVER;
        } else if (state == State.SCAN) {
            int result = scan(level, v, colony, now);
            if (result == 2) {
                if (seedCount(v) > 0) {
                    BlockPos site = findFieldSite(level, v, colony, now);
                    if (site != null) {
                        state = State.NEW_FIELD;
                        step = 0;
                        target = site;
                        return;
                    }
                }
                state = State.DELIVER;
            }
        } else if (state == State.NEW_FIELD) {
            newField(level, v, colony, now);
        } else if (target == null) {
            state = State.SCAN;
        } else if (FarmTerritory.nearPlot(colony, target, 3)) {
            blacklist.put(target, now + 1200);
            target = null;
            state = State.SCAN;
            WorkTracker.status(v, STATUS_RELEASED);
        } else {
            v.getBrain().setMemory(ModRegistries.FARM_TARGET_POS.get(), target);
            if (!walk(level, v, target, now)) return;
            if (!timer.tickAction(level, v, target, ColonyConfig.get().work.farming_ticks)) return;
            timer.reset();
            perform(level, v, colony, target);
            v.swing(InteractionHand.MAIN_HAND);
            target = null;
            if (state != State.DELIVER) state = State.SCAN;
        }
    }

    // ---------------------------------------------------------------- movement (04 §2.10)

    /** Returns true on arrival (sq dist <= 9); blacklists the target after 200 ticks without progress. */
    private boolean walk(ServerLevel level, Villager v, BlockPos pos, long now) {
        double d = v.blockPosition().distSqr(pos);
        if (d <= 9) return true;
        if (!pos.equals(moveTarget)) {
            moveTarget = pos.immutable();
            bestDist = d;
            lastMoveProgress = now;
        } else if (d < bestDist - 1.0) {
            bestDist = d;
            lastMoveProgress = now;
        }
        if (now - lastMoveProgress <= 200) {
            Nav.approach(level, v, pos, 0.9);
            return false;
        }
        blacklist.put(pos.immutable(), now + 1200);
        moveTarget = null;
        if (state == State.PREPARE || state == State.DELIVER) {
            done = true;
            cooldown = now + 100;
        } else {
            if (state == State.NEW_FIELD && target != null) blacklist.put(target, now + 1200);
            state = State.SCAN;
            target = null;
        }
        return false;
    }

    private boolean blacklisted(BlockPos pos, long now) {
        Long exp = blacklist.get(pos);
        return exp != null && exp > now;
    }

    // ---------------------------------------------------------------- storage visit (04 §2.4)

    private BlockPos chooseStorage(ServerLevel level, Villager v, Colony colony) {
        if (state == State.PREPARE && seedCount(v) < 8) {
            BlockPos best = null;
            boolean bestNonPumpkin = false;
            double bestD = Double.MAX_VALUE;
            for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
                Container c = slot.container();
                boolean any = false, nonPumpkin = false;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty() || !isFarmerSeed(s)) continue;
                    any = true;
                    if (!s.is(Items.PUMPKIN_SEEDS)) nonPumpkin = true;
                }
                if (!any) continue;
                double d = slot.pos().distSqr(v.blockPosition());
                if (best == null || (nonPumpkin && !bestNonPumpkin) || (nonPumpkin == bestNonPumpkin && d < bestD)) {
                    best = slot.pos();
                    bestNonPumpkin = nonPumpkin;
                    bestD = d;
                }
            }
            if (best != null) return best;
        }
        return ColonyStorage.nearestFor(level, colony, v, StorageKind.FOOD);
    }

    private void storageVisit(ServerLevel level, Villager v, Colony colony, long now) {
        BlockPos storage = chooseStorage(level, v, colony);
        if (storage == null) {
            done = true;
            return;
        }
        if (!walk(level, v, storage, now)) return;
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            int keep;
            if (isFarmerSeed(s)) keep = 8;
            else if (s.is(Items.BREAD)) keep = 1;
            else if (s.is(Items.WHEAT) || s.is(Items.BEETROOT) || s.is(Items.PUMPKIN) || s.is(Items.MELON_SLICE)) keep = 0;
            else continue;
            if (s.getCount() <= keep) continue;
            ItemStack excess = s.split(s.getCount() - keep);
            FoodUtil.deposit(level, colony, v, excess);
            if (!excess.isEmpty()) {
                if (s.isEmpty()) inv.setItem(i, excess);
                else s.grow(excess.getCount());
            }
        }
        inv.setChanged();
        for (int i = 0; i < 32 && ColonyStorage.count(level, colony, s -> s.is(Items.WHEAT)) >= 19; i++) {
            if (!ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, s -> s.is(Items.WHEAT), 3),
                    List.of(new ItemStack(Items.BREAD)))) break;
        }
        if (!FoodUtil.carries(inv, ColonyStorage::isFood)) {
            FoodUtil.takeOne(level, colony, v, ColonyStorage::isFood);
        }
        if (state == State.DELIVER) {
            done = true;
            cooldown = now + 20;
            return;
        }
        ToolSupply.ensureTool(level, colony, v, ToolSupply.Kind.HOE);
        ToolSupply.ensureBucket(level, colony, v);
        if (!ColonyStorage.has(level, colony, s -> s.is(Items.PUMPKIN_SEEDS))
                && ColonyStorage.count(level, colony, s -> s.is(Items.PUMPKIN)) >= 2) {
            ColonyStorage.transaction(level, colony, () -> ColonyStorage.consume(level, colony, s -> s.is(Items.PUMPKIN), 1),
                    List.of(new ItemStack(Items.PUMPKIN_SEEDS, 4)));
        }
        int seeds = seedCount(v);
        if (seeds < 32) {
            ItemStack taken = FoodUtil.takeFirstSlot(level, colony, storage, s -> isFarmerSeed(s) && !s.is(Items.PUMPKIN_SEEDS), 32 - seeds);
            if (taken.isEmpty()) {
                taken = FoodUtil.takeFirstSlot(level, colony, storage, FarmerTask::isFarmerSeed, Math.min(4, 32 - seeds));
            }
            FoodUtil.give(level, v, taken);
        }
        state = State.SCAN;
        cursor = 0;
        sweepComplete = false;
        clearCandidates();
    }

    // ---------------------------------------------------------------- territory scan (04 §2.5)

    private void clearCandidates() {
        restoreC = emptyC = harvestC = hayC = expandC = waterC = grassC = null;
    }

    /** 0 = still scanning, 1 = sweep ended with a target, 2 = sweep ended without a target. */
    private int scan(ServerLevel level, Villager v, Colony colony, long now) {
        int r = Math.min(colony.radius() - 4, SPAN);
        long r2 = (long) r * r;
        int seeds = seedCount(v);
        boolean bucket = FoodUtil.carries(v.getInventory(), s -> s.is(Items.BUCKET));
        for (int n = 0; n < 2048; n++) {
            if (cursor >= OD.length || OD[cursor] > r2) {
                cursor = 0;
                sweepComplete = true;
                return pickTarget(v, seeds) ? 1 : 2;
            }
            column(level, v, colony, colony.core().getX() + OX[cursor], colony.core().getZ() + OZ[cursor], seeds, bucket, now);
            cursor++;
        }
        return 0;
    }

    private boolean pickTarget(Villager v, int seeds) {
        boolean waterBucket = FoodUtil.carries(v.getInventory(), s -> s.is(Items.WATER_BUCKET));
        BlockPos t = null;
        State next = null;
        if (seeds > 0 && restoreC != null) {
            t = restoreC;
            next = State.TILL_PLANT;
        } else if (seeds > 0 && emptyC != null) {
            t = emptyC;
            next = State.TILL_PLANT;
        } else if (harvestC != null) {
            t = harvestC;
            next = State.HARVEST;
        } else if (hayC != null) {
            t = hayC;
            next = State.HAY;
        } else if (seeds > 0 && expandC != null) {
            t = expandC;
            next = State.TILL_PLANT;
        } else if (seeds > 0 && waterC != null && !waterBucket) {
            t = waterC;
            next = State.FILL_BUCKET;
        } else if (seeds == 0 && grassC != null) {
            t = grassC;
            next = State.GATHER_SEEDS;
        }
        clearCandidates();
        if (t == null) return false;
        target = t;
        state = next;
        return true;
    }

    private static boolean closer(Villager v, BlockPos current, BlockPos candidate) {
        return current == null || candidate.distSqr(v.blockPosition()) < current.distSqr(v.blockPosition());
    }

    /**
     * Examines one column. Every predicate of the spec is a pure conjunction, so cheap tests run
     * first and the expensive territory tests only when a candidate slot would actually change.
     */
    private void column(ServerLevel level, Villager v, Colony colony, int x, int z, int seeds, boolean bucket, long now) {
        if (!level.hasChunk(x >> 4, z >> 4)) return;
        int yTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int y = Math.max(level.getMinBuildHeight(), yTop - 3); y <= yTop + 2; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            boolean crop = s.getBlock() instanceof CropBlock;
            boolean farmland = s.is(Blocks.FARMLAND);
            boolean hay = s.is(Blocks.HAY_BLOCK);
            boolean melon = s.is(Blocks.MELON);
            boolean pumpkin = s.is(Blocks.PUMPKIN);
            boolean water = s.is(Blocks.WATER);
            boolean grass = FarmTerritory.isGrassPlant(s);
            BlockPos above = pos.above();
            BlockState aboveState = level.getBlockState(above);
            boolean soil = FarmTerritory.isSoil(s) && FarmTerritory.isAirOrGrass(aboveState);
            if (!(crop || farmland || hay || melon || pumpkin || water || grass || soil)) continue;
            Qualifier q = new Qualifier(level, colony, pos);
            if (farmland && q.ok()) FarmTerritory.registerFarmland(level, colony, pos);
            if (blacklisted(pos, now) || blacklisted(above, now)) continue;
            if (hay && closer(v, hayC, pos) && FarmTerritory.isVillageHay(level, colony, pos) && q.ok()) {
                hayC = pos;
            }
            boolean mature = crop && ((CropBlock) s.getBlock()).isMaxAge(s);
            if ((melon || mature || pumpkin) && closer(v, harvestC, pos)) {
                boolean harvestable = melon
                        || (mature && FarmTerritory.soilOk(level, colony, pos.below()))
                        || (pumpkin && (attachedStemNear(level, pos) || FarmTerritory.soilOk(level, colony, pos.below())));
                if (harvestable && q.ok()) harvestC = pos;
            }
            if (seeds > 0 && (farmland || soil) && FarmTerritory.isAirOrGrass(aboveState)) {
                if (farmland && closer(v, emptyC, above) && plantSlot(level, colony, v, above) >= 0
                        && FarmTerritory.soilOk(level, colony, pos) && q.ok()) {
                    emptyC = above;
                }
                if (soil && closer(v, expandC, above) && farmlandAround(level, pos) && plantSlot(level, colony, v, above) >= 0
                        && FarmTerritory.waterWithin4(level, pos) && FarmTerritory.soilOk(level, colony, pos) && q.ok()) {
                    expandC = above;
                }
                if (soil && (colony.farmland.contains(pos) || FarmTerritory.wasTemplateFarmland(level, pos))
                        && plantSlot(level, colony, v, above) >= 0 && FarmTerritory.waterWithin4(level, pos)
                        && FarmTerritory.soilOk(level, colony, pos) && q.ok()) {
                    FarmTerritory.registerFarmland(level, colony, pos);
                    if (closer(v, restoreC, above)) restoreC = above;
                }
            }
            if (seeds == 0 && grass && grassC == null && q.ok()) grassC = pos;
            if (bucket && water && waterC == null && stillWaterSource(level, pos) && q.ok()) waterC = pos;
        }
    }

    /** Lazily evaluated base qualification of a scanned block (04 §2.5). */
    private static final class Qualifier {
        private final ServerLevel level;
        private final Colony colony;
        private final BlockPos pos;
        private Boolean result;

        Qualifier(ServerLevel level, Colony colony, BlockPos pos) {
            this.level = level;
            this.colony = colony;
            this.pos = pos;
        }

        boolean ok() {
            if (result == null) {
                result = level.getBlockEntity(pos) == null && !ColonyManager.data(level).isPlayerBlock(pos)
                        && !FarmTerritory.nearPlot(colony, pos, 3) && FarmTerritory.territoryOk(level, colony, pos);
            }
            return result;
        }
    }

    private static boolean attachedStemNear(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).is(Blocks.ATTACHED_PUMPKIN_STEM)) return true;
        }
        return false;
    }

    private static boolean farmlandAround(ServerLevel level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && level.getBlockState(pos.offset(dx, 0, dz)).is(Blocks.FARMLAND)) return true;
            }
        }
        return false;
    }

    /** A still water source with at least 2 horizontally adjacent water blocks. */
    private static boolean stillWaterSource(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.WATER) || !level.getFluidState(pos).isSource()) return false;
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).is(Blocks.WATER)) n++;
        }
        return n >= 2;
    }

    // ---------------------------------------------------------------- actions (04 §2.6)

    /** Gives an item; overflow is dropped and forces DELIVER. */
    private void giveOrDeliver(ServerLevel level, Villager v, ItemStack stack) {
        if (!FoodUtil.give(level, v, stack)) state = State.DELIVER;
    }

    private void perform(ServerLevel level, Villager v, Colony colony, BlockPos t) {
        BlockState s = level.getBlockState(t);
        switch (state) {
            case HARVEST -> {
                if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
                    BlockState fresh = crop.defaultBlockState();
                    if (!FarmTerritory.soilOk(level, colony, t.below()) || !fresh.canSurvive(level, t) || !SafeEdit.place(level, t, fresh, 3)) return;
                    FarmTerritory.registerFarmland(level, colony, t.below());
                    Item product = productOf(crop);
                    Item seed = seedOf(crop);
                    if (product == seed) {
                        giveOrDeliver(level, v, new ItemStack(product, 2));
                    } else {
                        giveOrDeliver(level, v, new ItemStack(product));
                        giveOrDeliver(level, v, new ItemStack(seed, 2));
                    }
                    WorkTracker.credit(v, STAT_HARVEST, 1);
                    actions++;
                } else if (s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)) {
                    boolean isPumpkin = s.is(Blocks.PUMPKIN);
                    if (!FarmTerritory.soilOk(level, colony, t.below()) || !SafeEdit.breakBlock(level, v, t)) return;
                    giveOrDeliver(level, v, new ItemStack(isPumpkin ? Items.PUMPKIN : Items.MELON_SLICE));
                    actions++;
                }
            }
            case HAY -> {
                if (s.is(Blocks.HAY_BLOCK) && FarmTerritory.isVillageHay(level, colony, t) && SafeEdit.breakBlock(level, v, t)) {
                    giveOrDeliver(level, v, new ItemStack(Items.WHEAT, 9));
                    actions++;
                }
            }
            case TILL_PLANT -> till(level, v, colony, t.below());
            case GATHER_SEEDS -> {
                if (FarmTerritory.isGrassPlant(s) && SafeEdit.breakBlock(level, v, t) && level.random.nextBoolean()) {
                    giveOrDeliver(level, v, new ItemStack(Items.WHEAT_SEEDS));
                }
            }
            case FILL_BUCKET -> {
                if (stillWaterSource(level, t) && FoodUtil.removeOne(v.getInventory(), x -> x.is(Items.BUCKET))) {
                    giveOrDeliver(level, v, new ItemStack(Items.WATER_BUCKET));
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- tilling and planting (04 §2.7)

    private static boolean lightOk(ServerLevel level, BlockPos c) {
        return level.getMaxLocalRawBrightness(c) >= 8 || level.canSeeSky(c);
    }

    /** Planting slot at c, or -1. A pumpkin-seed slot only counts with a free reservation and spot. */
    private int plantSlot(ServerLevel level, Colony colony, Villager v, BlockPos c) {
        if (!lightOk(level, c)) return -1;
        SimpleContainer inv = v.getInventory();
        Boolean spot = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !isFarmerSeed(s)) continue;
            if (s.is(Items.PUMPKIN_SEEDS)) {
                if (pumpkinSpots.size() >= 2) continue;
                if (spot == null) spot = pumpkinSpot(level, colony, c) != null;
                if (!spot) continue;
            }
            return i;
        }
        return -1;
    }

    /** First horizontal neighbour of c.below() usable as a pumpkin fruit spot. */
    private BlockPos pumpkinSpot(ServerLevel level, Colony colony, BlockPos c) {
        BlockPos soil = c.below();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = soil.relative(d);
            if (pumpkinSpots.contains(q)) continue;
            if (!FarmTerritory.isSoil(level.getBlockState(q)) || !FarmTerritory.isAirOrGrass(level.getBlockState(q.above()))) continue;
            if (FarmTerritory.soilOk(level, colony, q)) return q;
        }
        return null;
    }

    private boolean till(ServerLevel level, Villager v, Colony colony, BlockPos p) {
        if (seedCount(v) <= 0) return false;
        BlockPos above = p.above();
        if (!FarmTerritory.isAirOrGrass(level.getBlockState(above)) || !FarmTerritory.soilOk(level, colony, p)
                || plantSlot(level, colony, v, above) < 0) return false;
        BlockState ps = level.getBlockState(p);
        boolean farmland = ps.is(Blocks.FARMLAND);
        if (!farmland && !(FarmTerritory.isSoil(ps) && FarmTerritory.waterWithin4(level, p))) return false;
        if (!level.getBlockState(above).isAir()) SafeEdit.breakBlock(level, v, above);
        if (!farmland) {
            if (!Protection.terrainModifiable(level, p)) return false;
            AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.9375, p.getZ() + 1);
            if (!level.getEntitiesOfClass(LivingEntity.class, box).isEmpty()) return false;
            SafeEditScope.run(() -> level.setBlock(p, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 3));
        }
        FarmTerritory.registerFarmland(level, colony, p);
        return plant(level, v, colony, above);
    }

    private boolean plant(ServerLevel level, Villager v, Colony colony, BlockPos c) {
        if (!FarmTerritory.soilOk(level, colony, c.below()) || !level.getBlockState(c.below()).is(Blocks.FARMLAND)
                || !level.getBlockState(c).isAir() || !lightOk(level, c)) return false;
        SimpleContainer inv = v.getInventory();
        int slot = -1;
        BlockPos spot = null;
        if (pumpkinSpots.size() < 2 && FoodUtil.carries(inv, s -> s.is(Items.PUMPKIN_SEEDS))) {
            spot = pumpkinSpot(level, colony, c);
            if (spot != null) {
                for (int i = 0; i < inv.getContainerSize() && slot < 0; i++) {
                    if (inv.getItem(i).is(Items.PUMPKIN_SEEDS)) slot = i;
                }
            }
        }
        if (slot < 0) slot = plantSlot(level, colony, v, c);
        if (slot < 0) return false;
        ItemStack seed = inv.getItem(slot);
        Block crop = cropFor(seed);
        if (!SafeEdit.place(level, c, crop.defaultBlockState(), 3)) return false;
        if (crop == Blocks.PUMPKIN_STEM) {
            if (spot == null) spot = pumpkinSpot(level, colony, c);
            if (spot != null) pumpkinSpots.add(spot);
        }
        seed.shrink(1);
        inv.setChanged();
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STAT_PLANT, 1);
        return true;
    }

    // ---------------------------------------------------------------- new field (04 §2.9)

    private static boolean fieldCell(ServerLevel level, Colony colony, BlockPos p, boolean waterBucket) {
        return FarmTerritory.isSoil(level.getBlockState(p)) && FarmTerritory.isAirOrGrass(level.getBlockState(p.above()))
                && (waterBucket || FarmTerritory.waterWithin4(level, p)) && FarmTerritory.soilOk(level, colony, p);
    }

    private BlockPos findFieldSite(ServerLevel level, Villager v, Colony colony, long now) {
        int r = Math.min(colony.radius() - 4, SPAN);
        long r2 = (long) r * r;
        boolean waterBucket = FoodUtil.carries(v.getInventory(), s -> s.is(Items.WATER_BUCKET));
        for (int n = 0; n < 1024; n++) {
            fieldCursor = Math.floorMod(fieldCursor, OD.length);
            if (OD[fieldCursor] > r2) {
                fieldCursor = 0;
                continue;
            }
            int x = colony.core().getX() + OX[fieldCursor];
            int z = colony.core().getZ() + OZ[fieldCursor];
            fieldCursor++;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos b = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
            BlockState bs = level.getBlockState(b);
            if (blacklisted(b, now) || !FarmTerritory.isAirOrGrass(level.getBlockState(b.above()))) continue;
            if (!FarmTerritory.isSoil(bs) && !bs.is(Blocks.WATER)) continue;
            if (!waterBucket && !FarmTerritory.waterWithin4(level, b)) continue;
            if (!FarmTerritory.soilOk(level, colony, b)) continue;
            int count = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if ((dx != 0 || dz != 0) && fieldCell(level, colony, b.offset(dx, 0, dz), waterBucket)) count++;
                }
            }
            if (count >= 4) return b;
        }
        return null;
    }

    private void newField(ServerLevel level, Villager v, Colony colony, long now) {
        BlockPos center = target;
        if (center == null || !FarmTerritory.soilOk(level, colony, center)) {
            if (center != null) blacklist.put(center, now + 1200);
            state = State.SCAN;
            target = null;
            return;
        }
        SimpleContainer inv = v.getInventory();
        while (step < 25 && seedCount(v) > 0) {
            if (step == 0 && !FarmTerritory.waterWithin4(level, center)) {
                if (!FoodUtil.carries(inv, s -> s.is(Items.WATER_BUCKET)) || !FarmTerritory.isSoil(level.getBlockState(center))
                        || !FarmTerritory.isAirOrGrass(level.getBlockState(center.above()))) {
                    state = State.DELIVER;
                    return;
                }
                if (!walk(level, v, center.above(), now)) return;
                if (!level.getBlockState(center.above()).isAir()) SafeEdit.breakBlock(level, v, center.above());
                if (SafeEdit.place(level, center, Blocks.WATER.defaultBlockState(), 3)) {
                    FoodUtil.removeOne(inv, s -> s.is(Items.WATER_BUCKET));
                    FoodUtil.give(level, v, new ItemStack(Items.BUCKET));
                }
            }
            BlockPos p = center.offset(step % 5 - 2, 0, step / 5 - 2);
            if (pumpkinSpots.contains(p) || !FarmTerritory.isSoil(level.getBlockState(p))
                    || !FarmTerritory.isAirOrGrass(level.getBlockState(p.above())) || !FarmTerritory.waterWithin4(level, p)
                    || !FarmTerritory.soilOk(level, colony, p)) {
                step++;
                continue;
            }
            v.getBrain().setMemory(ModRegistries.FARM_TARGET_POS.get(), p.above());
            if (!walk(level, v, p.above(), now)) return;
            if (!timer.tickAction(level, v, p, ColonyConfig.get().work.farming_ticks)) return;
            timer.reset();
            till(level, v, colony, p);
            step++;
            return;
        }
        state = State.SCAN;
        target = null;
        clearCandidates();
    }
}
