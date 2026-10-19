package net.betterthanvillage.food;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.ToolSupply;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/** Fisher (04 §3): static bobber prop, timed catches, fish cooking and rod crafting in storage. */
public final class FisherTask extends ColonyTask {
    public static final String STAT_FISHING = "рыбалка";
    public static final String STATUS_NO_ROD = "для удочки нужны 5 брёвен или 2 бревна и 2 нити";

    private enum State { PREPARE, SEARCH, FISHING, DELIVER }

    private static final int SPAN = 48;
    private static final int[] OX;
    private static final int[] OZ;
    private static final int[] OD;

    static {
        List<int[]> list = new ArrayList<>();
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

    /** Bobbers spawned by running fisher tasks; any other villager-owned bobber is a stale save leftover. */
    private static final Set<FishingHook> LIVE_BOBBERS = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    private State state;
    private BlockPos water;
    private BlockPos stand;
    private int cursor;
    private int catches;
    private long nextAction;
    private long cooldown;
    private boolean done;
    private FishingHook bobber;
    private long nextCatch;
    private long standSince;

    public FisherTask() {
        super(12000);
    }

    public String debugState() {
        return (state == null ? "ожидание" : state.name().toLowerCase(Locale.ROOT)) + catches;
    }

    public static boolean isLiveBobber(FishingHook hook) {
        return LIVE_BOBBERS.contains(hook);
    }

    private static boolean isFish(ItemStack s) {
        return s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
    }

    // ---------------------------------------------------------------- lifecycle (04 §3.2)

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && v.getTradingPlayer() == null && level.getGameTime() >= cooldown
                && colony.roleOf(v.getUUID()) == ColonyRole.FISHERMAN && colony.hasStorage();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        return !v.isSleeping() && v.getTradingPlayer() == null && colony != null
                && colony.roleOf(v.getUUID()) == ColonyRole.FISHERMAN && !done;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        state = State.PREPARE;
        water = null;
        stand = null;
        cursor = 0;
        catches = 0;
        nextAction = now;
        done = false;
        discardBobber();
        nextCatch = now + 100;
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        discardBobber();
        v.getNavigation().stop();
        v.getBrain().eraseMemory(ModRegistries.FISH_TARGET_POS.get());
        v.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
        water = null;
        stand = null;
    }

    private void discardBobber() {
        if (bobber != null && !bobber.isRemoved()) bobber.discard();
        bobber = null;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (now < nextAction) return;
        Colony colony = colony(level, v);
        if (colony == null) return;
        switch (state) {
            case PREPARE, DELIVER -> storageVisit(level, v, colony, now);
            case SEARCH -> {
                if (!search(level, v, colony, now) && cursor >= OD.length) state = State.DELIVER;
            }
            default -> fish(level, v, now);
        }
    }

    // ---------------------------------------------------------------- fishing

    private void fish(ServerLevel level, Villager v, long now) {
        if (water == null || !level.getBlockState(water).is(Blocks.WATER)) {
            state = State.SEARCH;
            water = null;
            stand = null;
            return;
        }
        v.getBrain().setMemory(ModRegistries.FISH_TARGET_POS.get(), water);
        if (v.blockPosition().distSqr(stand) > 6) {
            if (Nav.isBlacklisted(v, stand, now) || now - standSince > 600) {
                // Unreachable stand: give it up and continue the search past it.
                Nav.blacklist(v, stand, now, 1200);
                discardBobber();
                state = State.SEARCH;
                water = null;
                stand = null;
                return;
            }
            Nav.approach(level, v, stand, 0.75);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(water.getX() + 0.5, water.getY() + 0.2, water.getZ() + 0.5);
        if (bobber == null || bobber.isRemoved()) {
            FishingHook hook = new FishingHook(EntityType.FISHING_BOBBER, level);
            hook.setOwner(v);
            hook.setNoGravity(true);
            hook.moveTo(water.getX() + 0.5, water.getY() + 0.85, water.getZ() + 0.5, 0, 0);
            if (!level.addFreshEntity(hook)) {
                bobber = null;
                return;
            }
            bobber = hook;
            LIVE_BOBBERS.add(hook);
            v.swing(InteractionHand.MAIN_HAND);
            nextCatch = now + 100 + v.getRandom().nextInt(61);
        }
        if (now >= nextCatch) {
            nextCatch = now + 100 + v.getRandom().nextInt(61);
            ItemStack fish = new ItemStack(v.getRandom().nextInt(4) == 0 ? Items.SALMON : Items.COD);
            ItemStack rest = v.getInventory().addItem(fish);
            v.swing(InteractionHand.MAIN_HAND);
            catches++;
            boolean fit = rest.isEmpty();
            if (fit) {
                WorkTracker.credit(v, STAT_FISHING, 1);
            } else {
                FoodUtil.drop(level, v, rest);
            }
            if (!fit || catches >= 12) {
                discardBobber();
                state = State.DELIVER;
                water = null;
                stand = null;
            }
        }
    }

    // ---------------------------------------------------------------- spot search (04 §3.4)

    /** Returns true when a spot was chosen or the path budget ran out. */
    private boolean search(ServerLevel level, Villager v, Colony colony, long now) {
        int r = Math.min(SPAN, colony.radius() - 4);
        long r2 = (long) r * r;
        for (int n = 0; n < 256 && cursor < OD.length; n++) {
            if (OD[cursor] > r2) {
                cursor = OD.length;
                return false;
            }
            int x = colony.core().getX() + OX[cursor];
            int z = colony.core().getZ() + OZ[cursor];
            cursor++;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos w = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
            if (!level.getBlockState(w).is(Blocks.WATER) || !level.getFluidState(w).isSource() || !level.getBlockState(w.above()).isAir()) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int k = 1; k <= 4; k++) {
                    BlockPos s = w.relative(dir, k).above();
                    if (!level.isLoaded(s) || Nav.isBlacklisted(v, s, now) || !level.getBlockState(s).isAir() || !level.getBlockState(s.above()).isAir()) continue;
                    if (!level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) continue;
                    if (!PathBudget.tryAcquire(level)) return true;
                    Path path = v.getNavigation().createPath(s, 1);
                    if (path != null && path.canReach()) {
                        water = w;
                        stand = s;
                        standSince = now;
                        state = State.FISHING;
                        nextAction = now + 40;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- storage visit (04 §3.3)

    private void storageVisit(ServerLevel level, Villager v, Colony colony, long now) {
        BlockPos storage = ColonyStorage.nearestFor(level, colony, v, StorageKind.FOOD);
        if (storage == null) {
            done = true;
            return;
        }
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), storage);
        if (v.blockPosition().distSqr(storage) > 9) {
            Nav.approach(level, v, storage, 0.75);
            return;
        }
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && isFish(s)) FoodUtil.deposit(level, colony, v, s);
        }
        inv.setChanged();
        cookFish(level, colony);
        if (state == State.DELIVER) {
            done = true;
            cooldown = now + 40;
            return;
        }
        if (!v.getMainHandItem().is(Items.FISHING_ROD)) {
            ItemStack rod = ColonyStorage.withdrawOne(level, colony, s -> s.is(Items.FISHING_ROD));
            if (rod.isEmpty()) {
                int logs = ColonyStorage.count(level, colony, s -> s.is(ItemTags.LOGS));
                int string = ColonyStorage.count(level, colony, s -> s.is(Items.STRING));
                boolean crafted = false;
                if (logs >= 2 && string >= 2) {
                    crafted = ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 2)
                            && ColonyStorage.consume(level, colony, s -> s.is(Items.STRING), 2);
                } else if (logs >= 5) {
                    crafted = ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 5);
                }
                if (crafted) rod = new ItemStack(Items.FISHING_ROD);
            }
            if (!rod.isEmpty()) {
                ItemStack old = v.getMainHandItem().copy();
                v.setItemSlot(EquipmentSlot.MAINHAND, rod);
                ToolSupply.stash(level, colony, v, old);
            }
        }
        if (!v.getMainHandItem().is(Items.FISHING_ROD)) {
            WorkTracker.status(v, STATUS_NO_ROD);
            done = true;
            cooldown = now + 200;
            return;
        }
        state = State.SEARCH;
        cursor = 0;
    }

    /** Cooks up to 8 raw fish (cod first) for one fuel item, atomically. */
    private static void cookFish(ServerLevel level, Colony colony) {
        int cod = ColonyStorage.count(level, colony, s -> s.is(Items.COD));
        int salmon = ColonyStorage.count(level, colony, s -> s.is(Items.SALMON));
        int n = Math.min(8, cod + salmon);
        if (n == 0) return;
        int cookedCod = Math.min(cod, n);
        int cookedSalmon = n - cookedCod;
        List<ItemStack> outputs = new ArrayList<>();
        if (cookedCod > 0) outputs.add(new ItemStack(Items.COOKED_COD, cookedCod));
        if (cookedSalmon > 0) outputs.add(new ItemStack(Items.COOKED_SALMON, cookedSalmon));
        ColonyStorage.transaction(level, colony, () ->
                        ColonyStorage.consume(level, colony, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.LOGS), 1)
                                && ColonyStorage.consume(level, colony, s -> s.is(Items.COD), cookedCod)
                                && ColonyStorage.consume(level, colony, s -> s.is(Items.SALMON), cookedSalmon),
                outputs);
    }
}
