package net.betterthanvillage.food;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Animal herder (04 §4): pen construction, luring animals into it, shearing, eggs, milking and breeding. */
public final class RancherTask extends ColonyTask {
    public static final int PEN_HALF = 5;
    public static final int EGG_CAP = 32;
    private static final int SPECIES_CAP = 10;
    private static final int PEN_CAP = 40;
    private static final int ACTION_BUDGET = 12;
    private static final int PRODUCT_BUDGET = 16;

    public static final String STAT_PEN = "загон";
    public static final String STAT_LED = "животное приведено в загон";
    public static final String STAT_SHEAR = "стрижка";
    public static final String STAT_EGGS = "сбор яиц";
    public static final String STAT_MILK = "дойка";
    public static final String STAT_BREED = "разведение животных";
    public static final String STATUS_PEN_BLOCKED = "периметр загона заблокирован";
    public static final String STATUS_NO_PATH = "животному недоступен путь в загон: выбран другой маршрут/животное";

    private enum State { PREPARE, BUILD_PEN, CHOOSE, LEAD, HARVEST, DELIVER }

    private State state;
    private Animal target;
    private Item lure;
    private long nextRun;
    private long cooldown;
    private long stateStart;
    private long lastProgress;
    private double bestDist;
    private int actions;
    private int stateTicks;
    private State lastSeen;
    private boolean leftPen;
    private boolean done;
    private final Map<UUID, Long> productCooldowns = new HashMap<>();
    private final Map<UUID, Long> lureCooldowns = new HashMap<>();

    public RancherTask() {
        super(12000);
    }

    public String debugState() {
        StringBuilder sb = new StringBuilder(state == null ? "ожидание" : state.name().toLowerCase(Locale.ROOT));
        if (lure != null) sb.append(", приманка=").append(BuiltInRegistries.ITEM.getKey(lure).getPath());
        return sb.append(", собрано=").append(actions).toString();
    }

    // ---------------------------------------------------------------- species data (04 §4.3)

    public static boolean managed(Entity e) {
        return (e instanceof Cow && !(e instanceof MushroomCow)) || e instanceof Sheep || e instanceof Chicken
                || e instanceof Pig || e instanceof Rabbit || e instanceof Goat;
    }

    public static Item lureItem(Entity e) {
        if (e instanceof Chicken) return Items.WHEAT_SEEDS;
        if (e instanceof Pig || e instanceof Rabbit) return Items.CARROT;
        if ((e instanceof Cow && !(e instanceof MushroomCow)) || e instanceof Sheep || e instanceof Goat) return Items.WHEAT;
        return null;
    }

    public static boolean isProduct(ItemStack s) {
        return !s.isEmpty() && (s.is(Items.EGG) || s.is(Items.MILK_BUCKET) || s.is(Items.LEATHER) || s.is(Items.FEATHER)
                || s.is(Items.RABBIT_HIDE) || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_wool"));
    }

    private static Item wool(Sheep sheep) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(sheep.getColor().getName() + "_wool"));
    }

    private static boolean milkable(Entity e) {
        return (e instanceof Cow && !(e instanceof MushroomCow)) || e instanceof Goat;
    }

    // ---------------------------------------------------------------- pen geometry (04 §4.4)

    private static BlockPos gate(Colony colony) {
        return colony.livestockPen.north(PEN_HALF);
    }

    public static boolean inPen(Colony colony, Entity e) {
        BlockPos p = colony.livestockPen;
        return p != null && Math.abs(e.getX() - (p.getX() + 0.5)) < PEN_HALF && Math.abs(e.getZ() - (p.getZ() + 0.5)) < PEN_HALF
                && Math.abs(e.getY() - p.getY()) < 3;
    }

    private static void closeGate(ServerLevel level, Colony colony) {
        if (colony.livestockPen == null) return;
        BlockPos g = gate(colony);
        if (!level.isLoaded(g)) return;
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(g).inflate(0.15, 0.2, 0.15), e -> e.isAlive() && !e.isSpectator()).isEmpty()) return;
        BlockState s = level.getBlockState(g);
        if (s.is(Blocks.OAK_FENCE_GATE) && s.getValue(FenceGateBlock.OPEN)) {
            SafeEdit.place(level, g, s.setValue(FenceGateBlock.OPEN, false), 3);
        }
    }

    private static void openGate(ServerLevel level, Colony colony) {
        if (colony.livestockPen == null) return;
        BlockPos g = gate(colony);
        if (!level.isLoaded(g)) return;
        BlockState s = level.getBlockState(g);
        if (s.is(Blocks.OAK_FENCE_GATE) && !s.getValue(FenceGateBlock.OPEN)) {
            SafeEdit.place(level, g, s.setValue(FenceGateBlock.OPEN, true), 3);
        }
    }

    private static List<Animal> animals(ServerLevel level, Colony colony) {
        int h = Math.min(56, colony.radius());
        AABB box = new AABB(colony.core()).inflate(h, 32, h);
        return level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive() && managed(a));
    }

    /** Penned animals (except one) walk to the pen centre, away from the open gate. */
    private static void herdInside(ServerLevel level, Colony colony, Animal except) {
        BlockPos p = colony.livestockPen;
        if (p == null) return;
        for (Animal a : animals(level, colony)) {
            if (a != except && inPen(colony, a)) a.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.8);
        }
    }

    // ---------------------------------------------------------------- inventory helpers

    private static int carriedProducts(Villager v) {
        return FoodUtil.count(v.getInventory(), RancherTask::isProduct);
    }

    private static int eggs(ServerLevel level, Colony colony, Villager v) {
        return FoodUtil.count(v.getInventory(), s -> s.is(Items.EGG)) + ColonyStorage.count(level, colony, s -> s.is(Items.EGG));
    }

    /** Main-hand lure: keep it, or move the hand stack away and take one lure from inventory or storage. */
    private static boolean equipLure(ServerLevel level, Colony colony, Villager v, Item item) {
        if (v.getMainHandItem().is(item)) return true;
        ItemStack old = v.getMainHandItem();
        if (!old.isEmpty()) {
            v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            FoodUtil.give(level, v, old);
        }
        ItemStack one = ItemStack.EMPTY;
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize() && one.isEmpty(); i++) {
            if (inv.getItem(i).is(item)) {
                one = inv.getItem(i).split(1);
                inv.setChanged();
            }
        }
        if (one.isEmpty()) one = ColonyStorage.withdrawOne(level, colony, s -> s.is(item));
        if (one.isEmpty()) return false;
        v.setItemSlot(EquipmentSlot.MAINHAND, one);
        return true;
    }

    private void putLureAway(ServerLevel level, Villager v) {
        if (lure != null && v.getMainHandItem().is(lure)) {
            ItemStack s = v.getMainHandItem();
            v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            FoodUtil.give(level, v, s);
        }
    }

    private void setState(State s, long now) {
        state = s;
        stateStart = now;
    }

    // ---------------------------------------------------------------- lifecycle (04 §4.5)

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && v.getTradingPlayer() == null && level.getGameTime() >= cooldown
                && colony.roleOf(v.getUUID()) == ColonyRole.RANCHER && colony.hasStorage();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        return !v.isSleeping() && v.getTradingPlayer() == null && colony != null
                && colony.roleOf(v.getUUID()) == ColonyRole.RANCHER && !done;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        state = State.PREPARE;
        target = null;
        lure = null;
        actions = 0;
        stateTicks = 0;
        lastSeen = null;
        done = false;
        nextRun = now;
        stateStart = now;
        lureCooldowns.values().removeIf(t -> t <= now);
        productCooldowns.values().removeIf(t -> t <= now);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        if (target != null) AnimalFollowGoal.release(target);
        putLureAway(level, v);
        Colony colony = colony(level, v);
        if (colony != null) closeGate(level, colony);
        v.getNavigation().stop();
        v.getBrain().eraseMemory(ModRegistries.ANIMAL_TARGET_POS.get());
        v.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
        target = null;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (state != lastSeen) {
            lastSeen = state;
            stateTicks = 0;
        } else {
            stateTicks++;
        }
        if (now < nextRun) return;
        nextRun = now + 5;
        Colony colony = colony(level, v);
        if (colony == null) return;
        if (state == State.PREPARE || state == State.DELIVER) {
            storageVisit(level, v, colony, now);
            return;
        }
        if (state == State.BUILD_PEN) {
            buildPen(level, v, colony, now);
            return;
        }
        if (actions >= ACTION_BUDGET || carriedProducts(v) >= PRODUCT_BUDGET) {
            setState(State.DELIVER, now);
            target = null;
            return;
        }
        penSweep(level, colony, v);
        if (state == State.CHOOSE) {
            choose(level, v, colony, now);
        } else if (target != null && target.isAlive() && managed(target)) {
            v.getBrain().setMemory(ModRegistries.ANIMAL_TARGET_POS.get(), target.blockPosition());
            if (state == State.LEAD) {
                lead(level, v, colony, now);
            } else if (state == State.HARVEST) {
                harvest(level, v, colony, now);
            } else {
                state = State.CHOOSE;
            }
        } else {
            state = State.CHOOSE;
        }
    }

    // ---------------------------------------------------------------- storage visit (04 §4.6)

    private void storageVisit(ServerLevel level, Villager v, Colony colony, long now) {
        if (colony.livestockPen != null && inPen(colony, v)) {
            herdInside(level, colony, null);
            openGate(level, colony);
            Nav.approach(level, v, gate(colony).north(2), 0.7);
            if (now - stateStart > 400) {
                done = true;
                cooldown = now + 100;
            }
            return;
        }
        closeGate(level, colony);
        BlockPos storage = ColonyStorage.nearestFor(level, colony, v, StorageKind.ANIMAL);
        if (storage == null) {
            done = true;
            return;
        }
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), storage);
        if (v.blockPosition().distSqr(storage) > 9) {
            Nav.approach(level, v, storage, 0.85);
            return;
        }
        SimpleContainer inv = v.getInventory();
        // 1-2: egg caps (excess eggs are deleted)
        trimStorageEggs(level, colony);
        int allowed = Math.max(0, EGG_CAP - ColonyStorage.count(level, colony, s -> s.is(Items.EGG)));
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EGG)) continue;
            int keep = Math.min(allowed, s.getCount());
            s.setCount(keep);
            allowed -= keep;
        }
        // 3-4: milk caps
        MilkLedger.trimStorage(level, colony);
        MilkLedger.convert(inv, Math.max(0, MilkLedger.CAP - MilkLedger.storageMilk(level, colony)));
        // 5: products
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isProduct(s)) FoodUtil.deposit(level, colony, v, s);
        }
        // 6: keep at most 2 empty buckets
        int buckets = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.BUCKET)) continue;
            int keep = Math.min(Math.max(0, 2 - buckets), s.getCount());
            buckets += keep;
            if (s.getCount() > keep) {
                ItemStack excess = s.copyWithCount(s.getCount() - keep);
                int moved = FoodUtil.deposit(level, colony, v, excess);
                s.shrink(moved);
            }
        }
        inv.setChanged();
        // 7: food
        if (!FoodUtil.carries(inv, ColonyStorage::isFood)) FoodUtil.takeOne(level, colony, v, ColonyStorage::isFood);
        // 8: shears
        if (!FoodUtil.carries(inv, s -> s.is(Items.SHEARS)) && FoodUtil.hasRoom(inv, new ItemStack(Items.SHEARS))) {
            if (!FoodUtil.takeOne(level, colony, v, s -> s.is(Items.SHEARS))
                    && ColonyStorage.count(level, colony, s -> s.is(Items.IRON_INGOT)) >= 2
                    && ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), 2)) {
                FoodUtil.give(level, v, new ItemStack(Items.SHEARS));
            }
        }
        // 9: buckets for milking
        int want = Math.max(0, MilkLedger.CAP - MilkLedger.colonyMilk(level, colony));
        while (want > 0 && FoodUtil.count(inv, s -> s.is(Items.BUCKET)) < Math.min(2, want)) {
            if (!FoodUtil.hasRoom(inv, new ItemStack(Items.BUCKET))) break;
            if (FoodUtil.takeOne(level, colony, v, s -> s.is(Items.BUCKET))) continue;
            if (!ColonyStorage.consume(level, colony, s -> s.is(Items.IRON_INGOT), 3)) break;
            FoodUtil.give(level, v, new ItemStack(Items.BUCKET));
        }
        // 10: lures
        for (Item item : new Item[]{Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT}) {
            while (FoodUtil.count(inv, s -> s.is(item)) + (v.getMainHandItem().is(item) ? v.getMainHandItem().getCount() : 0) < 8) {
                if (!FoodUtil.hasRoom(inv, new ItemStack(item)) || !FoodUtil.takeOne(level, colony, v, s -> s.is(item))) break;
            }
        }
        if (state == State.DELIVER) {
            done = true;
            cooldown = now + 40;
        } else {
            setState(State.BUILD_PEN, now);
        }
    }

    private static void trimStorageEggs(ServerLevel level, Colony colony) {
        int kept = 0;
        for (ColonyStorage.Slot slot : ColonyStorage.usable(level, colony)) {
            for (int i = 0; i < slot.container().getContainerSize(); i++) {
                ItemStack s = slot.container().getItem(i);
                if (!s.is(Items.EGG)) continue;
                int keep = Math.min(Math.max(0, EGG_CAP - kept), s.getCount());
                if (keep < s.getCount()) {
                    s.setCount(keep);
                    slot.container().setChanged();
                }
                kept += keep;
            }
        }
    }

    // ---------------------------------------------------------------- pen construction (04 §4.7)

    private void buildPen(ServerLevel level, Villager v, Colony colony, long now) {
        if (colony.livestockPen == null) {
            BlockPos site = findPenSite(level, colony);
            if (site == null) {
                done = true;
                cooldown = now + 400;
                return;
            }
            colony.livestockPen = site;
            ColonyManager.markDirty(level);
        }
        BlockPos p = colony.livestockPen;
        BlockPos g = gate(colony);
        List<BlockPos> missing = new ArrayList<>();
        for (int dx = -PEN_HALF; dx <= PEN_HALF; dx++) {
            for (int dz = -PEN_HALF; dz <= PEN_HALF; dz++) {
                if (Math.abs(dx) != PEN_HALF && Math.abs(dz) != PEN_HALF) continue;
                BlockPos c = p.offset(dx, 0, dz);
                Block expected = c.equals(g) ? Blocks.OAK_FENCE_GATE : Blocks.OAK_FENCE;
                if (!level.getBlockState(c).is(expected)) missing.add(c);
            }
        }
        if (!missing.isEmpty()) {
            List<BlockPos> remaining = new ArrayList<>();
            for (BlockPos c : missing) {
                if (level.getBlockState(c).canBeReplaced() && Protection.terrainModifiable(level, c)) remaining.add(c);
            }
            if (remaining.isEmpty()) {
                WorkTracker.status(v, STATUS_PEN_BLOCKED);
                done = true;
                cooldown = now + 600;
                return;
            }
            if (!ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1)) {
                done = true;
                cooldown = now + 200;
                return;
            }
            int placed = 0;
            for (int i = 0; i < Math.min(3, remaining.size()); i++) {
                BlockPos c = remaining.get(i);
                if (!level.getBlockState(c).canBeReplaced()) continue;
                BlockState state = c.equals(g)
                        ? Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, false)
                        : Blocks.OAK_FENCE.defaultBlockState();
                // Fences need their connection state to actually enclose the pen (a bare default post does not).
                state = Block.updateFromNeighbourShapes(state, level, c);
                if (SafeEdit.place(level, c, state, 3)) placed++;
            }
            if (placed == 0) {
                ItemStack refund = new ItemStack(Items.OAK_LOG);
                ColonyStorage.deposit(level, colony, refund);
                FoodUtil.give(level, v, refund);
            } else {
                WorkTracker.credit(v, STAT_PEN, placed);
            }
            v.swing(InteractionHand.MAIN_HAND);
            actions++;
            nextRun = now + 8;
            return;
        }
        List<BlockPos> old = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue;
                BlockPos c = p.offset(dx, 0, dz);
                BlockState s = level.getBlockState(c);
                if (s.is(Blocks.OAK_FENCE) || s.is(Blocks.OAK_FENCE_GATE)) old.add(c);
            }
        }
        if (!old.isEmpty()) {
            for (int i = 0; i < Math.min(3, old.size()); i++) SafeEdit.breakBlock(level, v, old.get(i));
            v.swing(InteractionHand.MAIN_HAND);
            nextRun = now + 8;
            return;
        }
        closeGate(level, colony);
        setState(State.CHOOSE, now);
        actions = 0;
    }

    private static BlockPos findPenSite(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        for (int r = 16; r <= 40; r++) {
            int x = -r, z = -r;
            int[][] legs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            for (int[] leg : legs) {
                for (int k = 0; k < 2 * r; k++) {
                    int cx = core.getX() + x, cz = core.getZ() + z;
                    if (level.hasChunk(cx >> 4, cz >> 4)) {
                        BlockPos c = new BlockPos(cx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz), cz);
                        if (penSiteValid(level, colony, c)) return c;
                    }
                    x += leg[0];
                    z += leg[1];
                }
            }
        }
        return null;
    }

    private static boolean penSiteValid(ServerLevel level, Colony colony, BlockPos c) {
        ColonySavedData data = ColonyManager.data(level);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -PEN_HALF; dx <= PEN_HALF; dx++) {
            for (int dz = -PEN_HALF; dz <= PEN_HALF; dz++) {
                m.set(c.getX() + dx, c.getY() - 1, c.getZ() + dz);
                if (!level.isLoaded(m)) return false;
                BlockState ground = level.getBlockState(m);
                if (!ground.isFaceSturdy(level, m, Direction.UP) || ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND)
                        || ground.is(BlockTags.PLANKS)) return false;
                for (int dy = 0; dy <= 1; dy++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState s = level.getBlockState(m);
                    if (!(s.isAir() || s.canBeReplaced()) || level.getBlockEntity(m) != null) return false;
                    if (data.inLand(m) || Protection.inPlayerOwnedJob(level, m) || nearActiveJob(level, m)) return false;
                }
            }
        }
        BoundingBox area = new BoundingBox(c.getX() - 7, c.getY() - 2, c.getZ() - 7, c.getX() + 7, c.getY() + 3, c.getZ() + 7);
        for (Colony other : data.colonies()) {
            for (ConstructionJob job : other.jobs) {
                if (job.box().intersects(area)) return false;
            }
        }
        return true;
    }

    /** Within 2 blocks (horizontally) of any active construction job of any colony. */
    private static boolean nearActiveJob(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            for (ConstructionJob job : colony.jobs) {
                if (!job.isActive()) continue;
                BoundingBox b = job.box();
                if (pos.getX() >= b.minX() - 2 && pos.getX() <= b.maxX() + 2 && pos.getZ() >= b.minZ() - 2 && pos.getZ() <= b.maxZ() + 2) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- choosing work (04 §4.8)

    /** The colony-wide conditions (shears, egg room, milk room) are computed once per choice by the caller. */
    private boolean productReady(Animal a, long now, boolean shears, boolean eggRoom, boolean milkRoom) {
        if (a.isBaby() || productCooldowns.getOrDefault(a.getUUID(), Long.MIN_VALUE) > now) return false;
        if (a instanceof Sheep sheep) return sheep.readyForShearing() && shears;
        if (a instanceof Chicken) return eggRoom;
        if (milkable(a)) return milkRoom;
        return false;
    }

    private void choose(ServerLevel level, Villager v, Colony colony, long now) {
        List<Animal> list = animals(level, colony);
        Map<Object, Integer> penCount = new HashMap<>();
        int penned = 0;
        for (Animal a : list) {
            if (inPen(colony, a)) {
                penCount.merge(a.getType(), 1, Integer::sum);
                penned++;
            }
        }
        Animal leadPick = null;
        for (Animal a : list) {
            if (inPen(colony, a) || lureCooldowns.getOrDefault(a.getUUID(), Long.MIN_VALUE) > now) continue;
            if (penCount.getOrDefault(a.getType(), 0) >= SPECIES_CAP) continue;
            Item item = lureItem(a);
            if (item == null || !FoodUtil.carries(v.getInventory(), s -> s.is(item))) continue;
            if (leadPick == null) {
                leadPick = a;
                continue;
            }
            boolean sheep = a instanceof Sheep, bestSheep = leadPick instanceof Sheep;
            if ((sheep && !bestSheep) || (sheep == bestSheep && v.distanceToSqr(a) < v.distanceToSqr(leadPick))) leadPick = a;
        }
        if (leadPick != null) {
            putLureAway(level, v);
            lure = lureItem(leadPick);
            if (!equipLure(level, colony, v, lure)) {
                lure = null;
                return;
            }
            target = leadPick;
            setState(State.LEAD, now);
            lastProgress = now;
            leftPen = !inPen(colony, v);
            bestDist = leadPick.blockPosition().distSqr(colony.livestockPen);
            return;
        }
        boolean shears = FoodUtil.carries(v.getInventory(), s -> s.is(Items.SHEARS));
        boolean eggRoom = eggs(level, colony, v) < EGG_CAP;
        boolean milkRoom = FoodUtil.carries(v.getInventory(), s -> s.is(Items.BUCKET)) && MilkLedger.colonyMilk(level, colony) < MilkLedger.CAP;
        Animal harvestPick = null;
        for (Animal a : list) {
            if (!inPen(colony, a) || !productReady(a, now, shears, eggRoom, milkRoom)) continue;
            if (harvestPick == null || v.distanceToSqr(a) < v.distanceToSqr(harvestPick)) harvestPick = a;
        }
        if (harvestPick != null) {
            target = harvestPick;
            setState(State.HARVEST, now);
            return;
        }
        if (penned < PEN_CAP && breed(level, v, colony, list, now)) return;
        setState(State.DELIVER, now);
    }

    private boolean breed(ServerLevel level, Villager v, Colony colony, List<Animal> list, long now) {
        List<Animal> adults = new ArrayList<>();
        for (Animal a : list) {
            if (inPen(colony, a) && !a.isBaby() && a.getAge() == 0) adults.add(a);
        }
        // Species whose breeding item is missing: checked once instead of once per pair.
        Set<Object> noItems = new HashSet<>();
        for (int i = 0; i < adults.size(); i++) {
            Animal a = adults.get(i);
            if (noItems.contains(a.getType())) continue;
            for (int j = i + 1; j < adults.size(); j++) {
                Animal b = adults.get(j);
                if (a.getType() != b.getType()) continue;
                Item item = lureItem(a);
                if (item == null || !consumeBreedingItems(level, colony, v, item)) {
                    noItems.add(a.getType());
                    break;
                }
                a.setInLoveTime(600);
                b.setInLoveTime(600);
                a.spawnChildFromBreeding(level, b);
                BlockPos p = colony.livestockPen;
                level.sendParticles(ParticleTypes.HEART, p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5, 8, 2.0, 0.5, 2.0, 0);
                actions++;
                WorkTracker.credit(v, STAT_BREED, 1);
                nextRun = now + 40;
                return true;
            }
        }
        return false;
    }

    /** Takes 2 breeding items: inventory slots first, then the main hand, then storage. */
    private static boolean consumeBreedingItems(ServerLevel level, Colony colony, Villager v, Item item) {
        SimpleContainer inv = v.getInventory();
        ItemStack hand = v.getMainHandItem();
        int total = FoodUtil.count(inv, s -> s.is(item)) + (hand.is(item) ? hand.getCount() : 0)
                + ColonyStorage.count(level, colony, s -> s.is(item));
        if (total < 2) return false;
        int need = 2;
        for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item)) continue;
            int k = Math.min(need, s.getCount());
            s.shrink(k);
            need -= k;
        }
        inv.setChanged();
        if (need > 0 && hand.is(item)) {
            int k = Math.min(need, hand.getCount());
            hand.shrink(k);
            need -= k;
        }
        return need <= 0 || ColonyStorage.consume(level, colony, s -> s.is(item), need);
    }

    // ---------------------------------------------------------------- leading (04 §4.9)

    private void lead(ServerLevel level, Villager v, Colony colony, long now) {
        Animal a = target;
        BlockPos p = colony.livestockPen;
        if (p == null) {
            finishLead(level, v, colony, false, now);
            return;
        }
        BlockPos g = gate(colony);
        if (inPen(colony, a) && !a.getBoundingBox().intersects(new AABB(g).inflate(0.5))) {
            finishLead(level, v, colony, true, now);
            return;
        }
        if (!equipLure(level, colony, v, lure)) {
            lureCooldowns.put(a.getUUID(), now + 400);
            finishLead(level, v, colony, false, now);
            return;
        }
        AnimalFollowGoal.follow(a, v, lure);
        if (!leftPen && inPen(colony, v)) {
            herdInside(level, colony, a);
            openGate(level, colony);
            Nav.approach(level, v, g.north(2), 0.65);
            return;
        }
        if (!leftPen) {
            leftPen = true;
            closeGate(level, colony);
        }
        double d = a.blockPosition().distSqr(p);
        if (d + 1.0 < bestDist) {
            bestDist = d;
            lastProgress = now;
        }
        if (stateTicks <= 2000 && now - lastProgress <= 600) {
            v.getLookControl().setLookAt(a, 30, 30);
            a.getLookControl().setLookAt(v, 30, 30);
            double va = v.distanceToSqr(a);
            if (va > 36) {
                closeGate(level, colony);
                Nav.approach(level, v, a.blockPosition(), 0.7);
            } else if (va > 16) {
                v.getNavigation().stop();
            } else if (v.blockPosition().distSqr(g) > 9) {
                closeGate(level, colony);
                Nav.approach(level, v, g.north(2), 0.62);
            } else {
                herdInside(level, colony, a);
                openGate(level, colony);
                Nav.approach(level, v, p.south(2), 0.62);
            }
        } else {
            WorkTracker.status(v, STATUS_NO_PATH);
            lureCooldowns.put(a.getUUID(), now + 2400);
            finishLead(level, v, colony, false, now);
        }
    }

    private void finishLead(ServerLevel level, Villager v, Colony colony, boolean success, long now) {
        if (target != null) {
            AnimalFollowGoal.release(target);
            target.getNavigation().stop();
        }
        if (success) herdInside(level, colony, null);
        putLureAway(level, v);
        closeGate(level, colony);
        target = null;
        if (success) {
            actions++;
            WorkTracker.credit(v, STAT_LED, 1);
            state = State.CHOOSE;
        } else {
            setState(State.DELIVER, now);
        }
    }

    // ---------------------------------------------------------------- harvesting (04 §4.5, §4.10)

    private void harvest(ServerLevel level, Villager v, Colony colony, long now) {
        Animal a = target;
        if (colony.livestockPen != null && inPen(colony, a) && !inPen(colony, v)) {
            BlockPos g = gate(colony);
            if (stateTicks > 400) {
                target = null;
                setState(State.DELIVER, now);
            } else if (v.blockPosition().distSqr(g) > 9) {
                closeGate(level, colony);
                Nav.approach(level, v, g.north(2), 0.72);
            } else {
                herdInside(level, colony, a);
                openGate(level, colony);
                Nav.approach(level, v, colony.livestockPen, 0.72);
            }
        } else if (v.distanceToSqr(a) > 9) {
            Nav.approach(level, v, a.blockPosition(), 0.85);
        } else {
            product(level, v, colony, a, now);
            closeGate(level, colony);
            target = null;
            setState(State.CHOOSE, now);
        }
    }

    private void product(ServerLevel level, Villager v, Colony colony, Animal a, long now) {
        SimpleContainer inv = v.getInventory();
        if (a instanceof Sheep sheep) {
            if (!sheep.readyForShearing() || !FoodUtil.carries(inv, s -> s.is(Items.SHEARS))) return;
            sheep.setSheared(true);
            FoodUtil.give(level, v, new ItemStack(wool(sheep), 1 + v.getRandom().nextInt(3)));
            productCooldowns.put(a.getUUID(), now + 1200);
            actions++;
            WorkTracker.credit(v, STAT_SHEAR, 1);
            v.swing(InteractionHand.MAIN_HAND);
        } else if (a instanceof Chicken) {
            FoodUtil.give(level, v, new ItemStack(Items.EGG));
            productCooldowns.put(a.getUUID(), now + 1200);
            actions++;
            WorkTracker.credit(v, STAT_EGGS, 1);
            v.swing(InteractionHand.MAIN_HAND);
        } else if (milkable(a)) {
            if (MilkLedger.colonyMilk(level, colony) >= MilkLedger.CAP || !FoodUtil.removeOne(inv, s -> s.is(Items.BUCKET))) return;
            FoodUtil.give(level, v, new ItemStack(Items.MILK_BUCKET));
            productCooldowns.put(a.getUUID(), now + 2400);
            actions++;
            WorkTracker.credit(v, STAT_MILK, 1);
            v.swing(InteractionHand.MAIN_HAND);
        }
    }

    // ---------------------------------------------------------------- pen sweep (04 §4.11)

    private static void penSweep(ServerLevel level, Colony colony, Villager v) {
        BlockPos p = colony.livestockPen;
        if (p == null) return;
        // Only from inside the pen or within reach: eligible() alone allows collecting across the whole line of sight.
        boolean inside = inPen(colony, v);
        for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(p).inflate(6, 2, 6))) {
            if (!item.isAlive() || (!inside && v.distanceToSqr(item) > 9) || !isProduct(item.getItem())
                    || !ItemPickup.eligible(level, colony, v, item, ColonyRole.RANCHER)) continue;
            int limit = item.getItem().is(Items.EGG) ? Math.max(0, EGG_CAP - eggs(level, colony, v)) : Integer.MAX_VALUE;
            ItemPickup.pickup(level, colony, v, item, limit);
        }
    }
}
