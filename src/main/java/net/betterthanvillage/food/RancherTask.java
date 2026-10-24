package net.betterthanvillage.food;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
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
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.AgeableMob;
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
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

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
    /** Max penned animals of one species before breeding it stops. */
    private static final int SPECIES_BREED_CAP = 16;
    /** Ticks between two breedings of the same species in a colony. */
    private static final int BREED_COOLDOWN = 1200;
    /** Ticks the herder feeds a pair before the young one comes. */
    private static final int FEED_TICKS = 80;
    /** Lures of each kind the herder carries. */
    private static final int LURE_STOCK = 8;
    /** Consecutive blocked-perimeter failures before the pen is relocated. */
    private static final int PEN_BLOCKED_RELOCATE = 3;

    public static final String STAT_PEN = "загон";
    public static final String STAT_LED = "животное приведено в загон";
    public static final String STAT_SHEAR = "стрижка";
    public static final String STAT_EGGS = "сбор яиц";
    public static final String STAT_MILK = "дойка";
    public static final String STAT_BREED = "разведение животных";
    public static final String STATUS_PEN_BLOCKED = "периметр загона заблокирован";
    public static final String STATUS_NO_STORAGE = "склад недоступен";
    public static final String STATUS_NO_PEN_SITE = "нет места для загона";
    public static final String STATUS_NO_LOGS = "нет брёвен для загона";
    public static final String STATUS_NO_PATH = "животному недоступен путь в загон: выбран другой маршрут/животное";

    private enum State { PREPARE, BUILD_PEN, CHOOSE, LEAD, HARVEST, BREED, DELIVER }

    private State state;
    private Animal target;
    /** The second animal of the pair the herder walks up to feed (BREED). */
    private Animal mate;
    /** When the herder started feeding the pair (-1: still walking up to it). */
    private long feedingSince = -1;
    private Item lure;
    private long nextRun;
    private long cooldown;
    private long stateStart;
    private long lastProgress;
    private double bestDist;
    /** Closest the herder has come to the animal it leads (squared). */
    private double bestReach;
    private int actions;
    private int stateTicks;
    private State lastSeen;
    private boolean leftPen;
    private boolean done;
    private final Map<UUID, Long> productCooldowns = new HashMap<>();
    private final Map<UUID, Long> lureCooldowns = new HashMap<>();
    private int penBlocked;

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
        if (s.is(BlockTags.FENCE_GATES) && s.hasProperty(FenceGateBlock.OPEN) && s.getValue(FenceGateBlock.OPEN)) {
            SafeEdit.place(level, g, s.setValue(FenceGateBlock.OPEN, false), 3);
        }
    }

    private static void openGate(ServerLevel level, Colony colony) {
        if (colony.livestockPen == null) return;
        BlockPos g = gate(colony);
        if (!level.isLoaded(g)) return;
        BlockState s = level.getBlockState(g);
        if (s.is(BlockTags.FENCE_GATES) && s.hasProperty(FenceGateBlock.OPEN) && !s.getValue(FenceGateBlock.OPEN)) {
            SafeEdit.place(level, g, s.setValue(FenceGateBlock.OPEN, true), 3);
        }
    }

    private static List<Animal> animals(ServerLevel level, Colony colony) {
        // The whole territory (at most 96 blocks out): a village's animals graze beyond its houses.
        int h = Math.min(96, colony.radius());
        AABB box = new AABB(colony.core()).inflate(h, 32, h);
        return level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive() && managed(a));
    }

    /** Largest group of one managed species the colony keeps or can lure (herder fishing fallback, 02 §Herder). */
    public static int largestHerd(ServerLevel level, Colony colony) {
        Map<Object, Integer> counts = new HashMap<>();
        int best = 0;
        for (Animal a : animals(level, colony)) {
            if (!inPen(colony, a) && foreignAnimal(level, colony, a)) continue;
            best = Math.max(best, counts.merge(a.getType(), 1, Integer::sum));
        }
        return best;
    }

    /** Penned animals (except one) near the gate walk to the pen centre, away from the open gate. */
    private static void herdInside(ServerLevel level, Colony colony, Animal except) {
        BlockPos p = colony.livestockPen;
        if (p == null) return;
        AABB nearGate = new AABB(gate(colony)).inflate(3, 2, 3);
        for (Animal a : level.getEntitiesOfClass(Animal.class, nearGate, a -> a.isAlive() && managed(a))) {
            if (a != except && inPen(colony, a)) a.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.8);
        }
    }

    /** Animals the rancher must not lure: player-owned, tied, ridden, protected or in another pen. */
    private static boolean foreignAnimal(ServerLevel level, Colony colony, Animal a) {
        if (a.hasCustomName() || a.isLeashed() || a.isVehicle() || a.isPassenger()) return true;
        if (a instanceof OwnableEntity owned && owned.getOwnerUUID() != null) return true;
        BlockPos pos = a.blockPosition();
        ColonySavedData data = ColonyManager.data(level);
        if (data.inLand(pos) || Protection.inPlayerOwnedJob(level, pos)) return true;
        for (Colony other : data.colonies()) {
            if (other != colony && inPen(other, a)) return true;
        }
        return inPlayerEnclosure(level, data, pos);
    }

    /** Animals a player keeps (named, tied, tamed, on their land or in a player-built enclosure): killing them is no offence. */
    public static boolean playerKept(ServerLevel level, Animal a) {
        if (a.hasCustomName() || a.isLeashed()) return true;
        if (a instanceof OwnableEntity owned && owned.getOwnerUUID() != null) return true;
        BlockPos pos = a.blockPosition();
        ColonySavedData data = ColonyManager.data(level);
        return data.inLand(pos) || Protection.inPlayerOwnedJob(level, pos) || inPlayerEnclosure(level, data, pos);
    }

    /** Player-placed fences, walls or gates on all four sides within 12 blocks. */
    private static boolean inPlayerEnclosure(ServerLevel level, ColonySavedData data, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int k = 1; k <= 12 && !hit; k++) {
                BlockPos q = pos.relative(d, k);
                if (!data.isPlayerBlock(q) || !level.isLoaded(q)) continue;
                BlockState s = level.getBlockState(q);
                hit = s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES);
            }
            if (!hit) return false;
        }
        return true;
    }

    private static boolean perimeterBlock(BlockState s, boolean gateCell) {
        return gateCell ? s.is(BlockTags.FENCE_GATES) : s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES);
    }

    /** No free slot in the villager inventory and none in colony storage. */
    private static boolean noRoomAnywhere(ServerLevel level, Colony colony, Villager v) {
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return ColonyStorage.emptySlots(level, colony) == 0;
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
                && colony.roleOf(v.getUUID()) == ColonyRole.HERDER && colony.hasStorage() && !FisherTask.fishing(level, colony)
                && !VillagerUtil.isRunning(v, FisherTask.class);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = ColonyManager.colonyOf(level, v);
        return !v.isSleeping() && v.getTradingPlayer() == null && colony != null
                && colony.roleOf(v.getUUID()) == ColonyRole.HERDER && !done;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        // With a pen and lures at hand the herder goes on with its animals (the pen is checked first); the storage
        // visit comes when it runs out or has products to bring.
        Colony colony = ColonyManager.colonyOf(level, v);
        boolean lures = FoodUtil.carries(v.getInventory(), st -> st.is(Items.WHEAT) || st.is(Items.WHEAT_SEEDS) || st.is(Items.CARROT));
        state = colony != null && colony.livestockPen != null && lures && carriedProducts(v) == 0 ? State.BUILD_PEN : State.PREPARE;
        target = null;
        mate = null;
        feedingSince = -1;
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
        lure = null;
        mate = null;
        Colony colony = ColonyManager.colonyOf(level, v);
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
        Colony colony = ColonyManager.colonyOf(level, v);
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
            } else if (state == State.BREED) {
                feedPair(level, v, colony, now);
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
            WorkTracker.status(v, STATUS_NO_STORAGE);
            done = true;
            return;
        }
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), storage);
        if (v.blockPosition().distSqr(storage) > 9) {
            Nav.approach(level, v, storage, 0.85);
            return;
        }
        SimpleContainer inv = v.getInventory();
        // Egg and milk caps only stop collecting; products are never deleted. Products and picked-up
        // items the role does not keep go to storage.
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && (isProduct(s) || !ItemPickup.keepWhenDumping(s, ColonyRole.HERDER))) FoodUtil.deposit(level, colony, v, s);
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
            while (FoodUtil.count(inv, s -> s.is(item)) + (v.getMainHandItem().is(item) ? v.getMainHandItem().getCount() : 0) < LURE_STOCK) {
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

    // ---------------------------------------------------------------- pen construction (04 §4.7)

    private void buildPen(ServerLevel level, Villager v, Colony colony, long now) {
        if (colony.livestockPen == null) {
            BlockPos site = findPenSite(level, colony);
            if (site == null) {
                WorkTracker.status(v, STATUS_NO_PEN_SITE);
                done = true;
                cooldown = now + 400;
                return;
            }
            colony.livestockPen = site;
            ColonyManager.markDirty(level);
        }
        BlockPos p = colony.livestockPen;
        BlockPos g = gate(colony);
        if (ColonyManager.terrainChangesAllowed(level)) {
            List<BlockPos> cuts = outsideCuts(level, p);
            if (!cuts.isEmpty()) {
                cuts.sort(java.util.Comparator.comparingDouble(c -> v.distanceToSqr(Vec3.atCenterOf(c))));
                BlockPos c = cuts.get(0);
                if (v.distanceToSqr(Vec3.atCenterOf(c)) > 16) {
                    Nav.approach(level, v, c, 0.7);
                    return;
                }
                int cut = 0;
                for (BlockPos b : cuts) {
                    if (cut < 3 && v.distanceToSqr(Vec3.atCenterOf(b)) <= 16 && SafeEdit.breakBlock(level, v, b)) cut++;
                }
                v.swing(InteractionHand.MAIN_HAND);
                WorkTracker.credit(v, STAT_PEN, 1);
                nextRun = now + 8;
                return;
            }
        }
        List<BlockPos> missing = new ArrayList<>();
        for (int dx = -PEN_HALF; dx <= PEN_HALF; dx++) {
            for (int dz = -PEN_HALF; dz <= PEN_HALF; dz++) {
                if (Math.abs(dx) != PEN_HALF && Math.abs(dz) != PEN_HALF) continue;
                BlockPos c = p.offset(dx, 0, dz);
                if (!perimeterBlock(level.getBlockState(c), c.equals(g))) missing.add(c);
            }
        }
        if (!missing.isEmpty()) {
            List<BlockPos> remaining = new ArrayList<>();
            for (BlockPos c : missing) {
                if (level.getBlockState(c).canBeReplaced() && Protection.terrainModifiable(level, c)) remaining.add(c);
            }
            if (remaining.isEmpty()) {
                WorkTracker.status(v, STATUS_PEN_BLOCKED);
                if (++penBlocked >= PEN_BLOCKED_RELOCATE) {
                    // Permanently blocked perimeter: give the site up, the next visit picks a new one.
                    penBlocked = 0;
                    colony.livestockPen = null;
                    ColonyManager.markDirty(level);
                }
                done = true;
                cooldown = now + 600;
                return;
            }
            // The pen is the village's: its fence wood comes from the treasury, not the herder's household.
            if (!net.betterthanvillage.economy.StockScope.treasuryTest(() -> ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1))) {
                WorkTracker.status(v, STATUS_NO_LOGS);
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
                net.betterthanvillage.economy.StockScope.treasury(() -> ColonyStorage.deposit(level, colony, refund));
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
        penBlocked = 0;
        closeGate(level, colony);
        setState(State.CHOOSE, now);
        actions = 0;
    }

    private static BlockPos findPenSite(ServerLevel level, Colony colony) {
        BlockPos core = colony.core();
        // Out to the edge of the territory (at most 64): a hillside village has its flat ground farther out.
        int max = Math.max(16, Math.min(64, colony.radius() - PEN_HALF - 2));
        for (int r = 16; r <= max; r++) {
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
        // The ground just outside the fence is not higher than the pen's (from a block at fence level an animal steps
        // onto the fence and over it, and villagers path along its top), or one natural block higher: the herder cuts
        // it away (outsideCuts).
        boolean cuts = ColonyManager.terrainChangesAllowed(level);
        int out = PEN_HALF + 1;
        for (int dx = -out; dx <= out; dx++) {
            for (int dz = -out; dz <= out; dz++) {
                if (Math.abs(dx) != out && Math.abs(dz) != out) continue;
                m.set(c.getX() + dx, c.getY(), c.getZ() + dz);
                if (!level.isLoaded(m)) return false;
                if (level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
                if (!cuts || !cuttable(level, m) || data.inLand(m)) return false;
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

    /** A natural block at fence level right outside the pen, with room above it: the herder takes it away. */
    private static boolean cuttable(ServerLevel level, BlockPos pos) {
        BlockPos up = pos.above();
        return net.betterthanvillage.construction.Terrain.natural(level.getBlockState(pos)) && level.getBlockEntity(pos) == null
                && level.getBlockState(up).getCollisionShape(level, up).isEmpty() && Protection.terrainModifiable(level, pos);
    }

    /** Natural blocks at fence level around the pen, left to cut away. */
    private static List<BlockPos> outsideCuts(ServerLevel level, BlockPos p) {
        List<BlockPos> out = new ArrayList<>();
        int r = PEN_HALF + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                BlockPos c = p.offset(dx, 0, dz);
                if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() && cuttable(level, c)) out.add(c);
            }
        }
        return out;
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
        // Animals already penned come first (shearing, then breeding a pair), more are led in after.
        boolean shears = FoodUtil.carries(v.getInventory(), s -> s.is(Items.SHEARS));
        boolean eggRoom = eggs(level, colony, v) < EGG_CAP;
        boolean milkRoom = FoodUtil.carries(v.getInventory(), s -> s.is(Items.BUCKET)) && MilkLedger.colonyMilk(level, colony) < MilkLedger.CAP;
        Animal harvestPick = null;
        boolean noRoom = noRoomAnywhere(level, colony, v);
        for (Animal a : list) {
            if (noRoom) break;
            if (!inPen(colony, a) || !productReady(a, now, shears, eggRoom, milkRoom)) continue;
            if (harvestPick == null || v.distanceToSqr(a) < v.distanceToSqr(harvestPick)) harvestPick = a;
        }
        if (harvestPick != null) {
            target = harvestPick;
            setState(State.HARVEST, now);
            return;
        }
        if (penned < PEN_CAP && breed(level, v, colony, list, penCount, now)) return;
        Animal leadPick = null;
        for (Animal a : list) {
            if (inPen(colony, a) || lureCooldowns.getOrDefault(a.getUUID(), Long.MIN_VALUE) > now) continue;
            if (penCount.getOrDefault(a.getType(), 0) >= SPECIES_CAP) continue;
            Item item = lureItem(a);
            if (item == null || !FoodUtil.carries(v.getInventory(), s -> s.is(item))) continue;
            if (foreignAnimal(level, colony, a)) {
                lureCooldowns.put(a.getUUID(), now + 1200);
                continue;
            }
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
            bestReach = Double.MAX_VALUE;
            return;
        }
        setState(State.DELIVER, now);
    }

    /**
     * Stock of a breeding item that breeding must not touch: the treasury keeps wheat for bread; the herder's own
     * household bought it for the animals and keeps two lures (one to lead the next animal in, one spare), the next
     * storage visit tops the lures up again.
     */
    private static int breedingReserve(Item item) {
        if (net.betterthanvillage.economy.StockScope.inHousehold()) return 2;
        return item == Items.WHEAT ? 32 : 16;
    }

    private boolean breed(ServerLevel level, Villager v, Colony colony, List<Animal> list, Map<Object, Integer> penCount, long now) {
        Map<Object, Long> speciesCooldowns = colony.runtime("better_than_village:breed_cooldowns", HashMap::new);
        List<Animal> adults = new ArrayList<>();
        for (Animal a : list) {
            if (inPen(colony, a) && !a.isBaby() && a.getAge() == 0) adults.add(a);
        }
        // Species that may not breed now (cap, cooldown, reserve, no items): checked once instead of once per pair.
        Set<Object> skip = new HashSet<>();
        for (int i = 0; i < adults.size(); i++) {
            Animal a = adults.get(i);
            Object type = a.getType();
            if (skip.contains(type)) continue;
            Item item = lureItem(a);
            if (item == null || penCount.getOrDefault(type, 0) >= SPECIES_BREED_CAP
                    || speciesCooldowns.getOrDefault(type, Long.MIN_VALUE) > now
                    || breedingStock(level, colony, v, item) - 2 < breedingReserve(item)) {
                skip.add(type);
                continue;
            }
            for (int j = i + 1; j < adults.size(); j++) {
                Animal b = adults.get(j);
                if (a.getType() != b.getType()) continue;
                // The feed goes in the hand: the herder walks up to the pair with it.
                putLureAway(level, v);
                lure = item;
                if (!equipLure(level, colony, v, item)) {
                    lure = null;
                    skip.add(type);
                    break;
                }
                target = v.distanceToSqr(a) <= v.distanceToSqr(b) ? a : b;
                mate = target == a ? b : a;
                setState(State.BREED, now);
                return true;
            }
        }
        return false;
    }

    /** Feeds the pair chosen by breed() once the herder stands by it: hearts over both, then the young one. */
    private void feedPair(ServerLevel level, Villager v, Colony colony, long now) {
        Animal a = target, b = mate;
        if (b == null || !b.isAlive() || !inPen(colony, b) || !inPen(colony, a) || stateTicks > 1200) {
            endFeeding(level, v, colony, now);
            return;
        }
        if (!reach(level, v, colony, a)) return;
        v.getLookControl().setLookAt(a);
        if (v.distanceToSqr(b) > 9 && stateTicks < 200) {
            // The mate comes over for the feed in the herder's hand.
            b.getNavigation().moveTo(v, 1.1);
            return;
        }
        if (feedingSince < 0) {
            if (!consumeBreedingItems(level, colony, v, lure)) {
                endFeeding(level, v, colony, now);
                return;
            }
            feedingSince = now;
        }
        // A few seconds of feeding: the herder hands the feed, hearts rise over the pair, then the young one comes.
        b.getNavigation().moveTo(a, 1.0);
        if ((now - feedingSince) % 10 < 5) {
            v.swing(InteractionHand.MAIN_HAND);
            level.broadcastEntityEvent(a, (byte) 18);
            level.broadcastEntityEvent(b, (byte) 18);
        }
        if (now - feedingSince < FEED_TICKS) return;
        Map<Object, Long> speciesCooldowns = colony.runtime("better_than_village:breed_cooldowns", HashMap::new);
        spawnChild(level, a, b);
        speciesCooldowns.put(a.getType(), now + BREED_COOLDOWN);
        actions++;
        WorkTracker.credit(v, STAT_BREED, 1);
        nextRun = now + 40;
        endFeeding(level, v, colony, now);
    }

    private void endFeeding(ServerLevel level, Villager v, Colony colony, long now) {
        feedingSince = -1;
        putLureAway(level, v);
        lure = null;
        closeGate(level, colony);
        target = null;
        mate = null;
        setState(State.CHOOSE, now);
    }

    private static int breedingStock(ServerLevel level, Colony colony, Villager v, Item item) {
        ItemStack hand = v.getMainHandItem();
        return FoodUtil.count(v.getInventory(), s -> s.is(item)) + (hand.is(item) ? hand.getCount() : 0)
                + ColonyStorage.count(level, colony, s -> s.is(item));
    }

    /**
     * Animal.spawnChildFromBreeding without the experience orb of finalizeSpawnChildFromBreeding:
     * villager breeding must not be an XP farm.
     */
    private static void spawnChild(ServerLevel level, Animal a, Animal b) {
        AgeableMob child = a.getBreedOffspring(level, b);
        BabyEntitySpawnEvent event = new BabyEntitySpawnEvent(a, b, child);
        boolean cancelled = NeoForge.EVENT_BUS.post(event).isCanceled();
        child = event.getChild();
        a.setAge(6000);
        b.setAge(6000);
        a.resetLove();
        b.resetLove();
        if (cancelled || child == null) return;
        child.setBaby(true);
        child.moveTo(a.getX(), a.getY(), a.getZ(), 0.0F, 0.0F);
        level.broadcastEntityEvent(a, (byte) 18);
        level.addFreshEntityWithPassengers(child);
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
            // A long lead from the far side of the territory is work too (the 60 s watchdog would stop it).
            WorkTracker.progress(v);
        }
        double reach = v.distanceToSqr(a);
        if (reach + 4.0 < bestReach) {
            // Walking out to a far animal counts as progress of the lead.
            bestReach = reach;
            lastProgress = now;
            WorkTracker.progress(v);
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
        if (stateTicks > 400 && !inPen(colony, v)) {
            target = null;
            setState(State.DELIVER, now);
        } else if (reach(level, v, colony, a)) {
            product(level, v, colony, a, now);
            closeGate(level, colony);
            target = null;
            setState(State.CHOOSE, now);
        }
    }

    /** Walks up to a penned animal, through the gate when the herder is outside; true once within reach. */
    private static boolean reach(ServerLevel level, Villager v, Colony colony, Animal a) {
        if (colony.livestockPen != null && inPen(colony, a) && !inPen(colony, v)) {
            BlockPos g = gate(colony);
            if (v.blockPosition().distSqr(g) > 9) {
                closeGate(level, colony);
                Nav.approach(level, v, g.north(2), 0.72);
            } else {
                herdInside(level, colony, a);
                openGate(level, colony);
                Nav.approach(level, v, colony.livestockPen, 0.72);
            }
            return false;
        }
        if (v.distanceToSqr(a) > 9) {
            Nav.approach(level, v, a.blockPosition(), 0.85);
            return false;
        }
        return true;
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
                    || !ItemPickup.eligible(level, colony, v, item, ColonyRole.HERDER)) continue;
            int limit = item.getItem().is(Items.EGG) ? Math.max(0, EGG_CAP - eggs(level, colony, v)) : Integer.MAX_VALUE;
            ItemPickup.pickup(level, colony, v, item, limit);
        }
    }
}
