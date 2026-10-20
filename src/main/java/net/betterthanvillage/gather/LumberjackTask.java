package net.betterthanvillage.gather;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
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
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.colony.StoragePlacer;
import net.betterthanvillage.construction.Damage;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lumberjack (03 §2): tree search, felling, wood delivery, wood-chest creation, sticks and replanting. */
public final class LumberjackTask extends ColonyTask {
    /** Every (dx, dz) with dx² + dz² <= 96², sorted by distance from the origin. */
    private static final int[][] SPIRAL = buildSpiral(96);

    /** Ticks to wait after a full fruitless sweep of the spiral, and after a partial one. */
    private static final long SEARCH_COOLDOWN = 100;
    private static final long SEARCH_STEP = 5;
    /** How long a tree that could not be reached or cut is skipped. */
    private static final long BAD_TREE_TICKS = 6000;

    private BlockPos anchor;
    private int cursor;
    private long nextSearch;
    /** Tree bases that turned out unreachable or uncuttable, with expiry (game time). */
    private final Map<BlockPos, Long> badTrees = new HashMap<>();

    private boolean delivering;
    private BlockPos base;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private int cut;
    private Block sapling = Blocks.OAK_SAPLING;
    private long nextAction;
    private boolean needsAxe;
    private BlockPos chestSite;
    /** After a delivery that could not happen, keep felling (logs stay carried) until this game time. */
    private long noDeliveryUntil;
    private final BlockWorkTimer timer = new BlockWorkTimer();

    public LumberjackTask() {
        super(6000);
    }

    // ---------------------------------------------------------------- activation

    private static boolean assigned(Colony colony, Villager v) {
        ColonyRole role = colony.roleOf(v.getUUID());
        return role == ColonyRole.CARPENTER
                || (role == ColonyRole.MASON && !colony.needsStarterWarehouse() && colony.storages.isEmpty());
    }

    /** A mason only fells trees to found the village stock (no storage yet), which is public work. */
    @Override
    public boolean publicWork(Villager v) {
        return v.getBrain().getMemory(ModRegistries.COLONY_ROLE.get()).orElse(ColonyRole.CITIZEN) == ColonyRole.MASON;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || v.getTradingPlayer() != null || !ColonyManager.terrainChangesAllowed(level) || !assigned(colony, v)
                || VillagerUtil.isRunning(v, net.betterthanvillage.economy.WorkshopTask.class)) {
            return false;
        }
        queue.clear();
        base = null;
        cut = 0;
        chestSite = null;
        long now = level.getGameTime();
        if (countLogs(v.getInventory()) >= 4 && now >= noDeliveryUntil) {
            delivering = true;
            return true;
        }
        delivering = false;
        if (now < nextSearch) return false;
        BlockPos found = findTree(level, colony, v);
        if (found == null) {
            nextSearch = now + (cursor >= SPIRAL.length ? SEARCH_COOLDOWN : SEARCH_STEP);
            return false;
        }
        base = found;
        List<BlockPos> logs = connectedLogs(level, found, false);
        // Top-down, so an interrupted felling leaves a stump on soil (findable again) instead of a floating trunk.
        logs.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()).thenComparingDouble(p -> p.distSqr(found)));
        queue.addAll(logs);
        sapling = saplingFor(level.getBlockState(found));
        v.getBrain().setMemory(ModRegistries.TREE_TARGET_POS.get(), found);
        return true;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long time) {
        nextAction = time;
        Colony colony = colony(level, v);
        needsAxe = !v.getMainHandItem().is(ItemTags.AXES) && colony != null && colony.hasStorage();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        return !v.isSleeping() && v.getTradingPlayer() == null && colony != null && assigned(colony, v)
                && (delivering || !queue.isEmpty());
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long time) {
        timer.cancel(level);
        v.getNavigation().stop();
        queue.clear();
        base = null;
        delivering = false;
        chestSite = null;
        v.getBrain().eraseMemory(ModRegistries.TREE_TARGET_POS.get());
    }

    // ---------------------------------------------------------------- felling

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        if (colony == null || now < nextAction || !ColonyManager.terrainChangesAllowed(level)) return;
        if (needsAxe) {
            BlockPos storage = ColonyStorage.nearestFor(level, colony, v, StorageKind.WOOD);
            if (storage != null && v.position().distanceToSqr(Vec3.atCenterOf(storage)) > 9) {
                Nav.approach(level, v, storage, 0.8);
                nextAction = now + 10;
                return;
            }
            ToolSupply.ensureTool(level, colony, v, ToolSupply.Kind.AXE);
            needsAxe = false;
        }
        if (delivering) {
            deliver(level, colony, v, now);
            return;
        }
        while (!queue.isEmpty() && !level.getBlockState(queue.peekFirst()).is(BlockTags.LOGS)) {
            queue.pollFirst();
        }
        if (queue.isEmpty()) {
            finishTree(level, colony, v);
            delivering = true;
            return;
        }
        BlockPos log = queue.peekFirst();
        if (!Protection.terrainModifiable(level, log) || Damage.isProtectedHousePiece(level, log) || GatherUtil.inStructure(level, log, 0, 0, 0)) {
            queue.pollFirst();
            nextAction = now + 2;
            return;
        }
        // Work from beside the log's column (reach 3); if that spot is unreachable, fall back to the base stance (reach 5).
        BlockPos stand = new BlockPos(log.getX(), base.getY(), log.getZ());
        double reach2 = 9;
        if (!stand.equals(base) && Nav.isBlacklisted(v, stand, now)) {
            stand = base;
            reach2 = 25;
        }
        double dx = v.getX() - (stand.getX() + 0.5);
        double dz = v.getZ() - (stand.getZ() + 0.5);
        if (dx * dx + dz * dz > reach2) {
            Nav.approach(level, v, stand, 0.6);
            if (stand.equals(base) && Nav.isBlacklisted(v, base, now)) {
                // Unreachable: give the tree up for a while instead of retrying until the watchdog fires.
                badTrees.put(base, now + BAD_TREE_TICKS);
                queue.clear();
            }
            return;
        }
        BlockState state = level.getBlockState(log);
        ItemStack drop = new ItemStack(state.getBlock().asItem());
        if (now >= noDeliveryUntil && !v.getInventory().canAddItem(drop) && carriesWood(v.getInventory())) {
            // No room for the next log: deliver first, then come back to this tree.
            timer.cancel(level);
            delivering = true;
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(log.getX() + 0.5, log.getY() + 0.5, log.getZ() + 0.5);
        if (timer.tick(level, v, log, true)) {
            if (SafeEdit.breakBlock(level, v, log)) {
                give(level, v, drop);
                cut++;
                v.swing(InteractionHand.MAIN_HAND);
                if (GatherUtil.wearTool(v, ItemTags.AXES, 1)) {
                    // Axe broke: fetch a new one from storage before the next log.
                    needsAxe = colony.hasStorage();
                }
            }
            // A refused break (entity on top, falling block above) skips the log instead of retrying every tick.
            queue.pollFirst();
            timer.reset();
            if (queue.isEmpty()) {
                finishTree(level, colony, v);
                delivering = true;
            }
        }
        nextAction = now + 1;
    }

    private static void give(ServerLevel level, Villager v, ItemStack stack) {
        ItemStack rest = v.getInventory().addItem(stack);
        if (!rest.isEmpty()) {
            level.addFreshEntity(new ItemEntity(level, v.getX(), v.getY() + 0.5, v.getZ(), rest));
        }
    }

    // ---------------------------------------------------------------- delivery

    private void deliver(ServerLevel level, Colony colony, Villager v, long now) {
        // A new WOOD chest only when the usable storages are really full, never because the nearest one is blacklisted.
        boolean full = !colony.hasStorage()
                || (!ColonyStorage.usable(level, colony).isEmpty() && ColonyStorage.emptySlots(level, colony) <= 4);
        BlockPos target;
        if (full) {
            if (countLogs(v.getInventory()) < 4) {
                giveUpDelivery(now);
                return;
            }
            target = buildWoodStorage(level, colony, v, now);
            if (target == null) return;
        } else {
            target = ColonyStorage.nearestFor(level, colony, v, StorageKind.WOOD);
            if (target == null) {
                // Storages exist but are blacklisted or unloaded right now: wait instead of building another chest.
                if (countLogs(v.getInventory()) < 4) giveUpDelivery(now);
                nextAction = now + 40;
                return;
            }
        }
        if (v.position().distanceToSqr(Vec3.atCenterOf(target)) > 9) {
            Nav.approach(level, v, target, 0.6);
            return;
        }
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && isWoodLoot(s)) {
                WorkTracker.deliver(level, colony, v, s);
            }
        }
        inv.setChanged();
        v.swing(InteractionHand.MAIN_HAND);
        delivering = false;
        nextAction = now + 20;
    }

    /** Ends the delivery attempt; carried wood stays in the inventory and felling goes on for a while. */
    private void giveUpDelivery(long now) {
        delivering = false;
        noDeliveryUntil = now + 600;
    }

    private static boolean carriesWood(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isWoodLoot(inv.getItem(i))) return true;
        }
        return false;
    }

    /** Walks to a cached wood-chest site and places a WOOD double chest there; returns it once placed. */
    private BlockPos buildWoodStorage(ServerLevel level, Colony colony, Villager v, long now) {
        if (chestSite == null) {
            chestSite = StoragePlacer.findSite(level, colony);
            if (chestSite == null) {
                giveUpDelivery(now);
                return null;
            }
        }
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), chestSite);
        // Walk to a cell beside the site, never onto it (the chest would be placed into the villager).
        BlockPos side = Nav.standable(level, chestSite.north()) ? chestSite.north() : chestSite.south();
        List<LivingEntity> occupants = level.getEntitiesOfClass(LivingEntity.class, new AABB(chestSite).expandTowards(1, 0, 0));
        if (occupants.stream().anyMatch(e -> e != v)) {
            chestSite = null;
            return null;
        }
        if (!occupants.isEmpty() || v.position().distanceToSqr(Vec3.atCenterOf(chestSite)) > 9) {
            Nav.approach(level, v, side, 0.6, Nav.R2_EXACT);
            return null;
        }
        if (!level.getBlockState(chestSite).isAir() || !level.getBlockState(chestSite.east()).isAir() || countLogs(v.getInventory()) < 4) {
            chestSite = null;
            return null;
        }
        BlockPos site = chestSite;
        chestSite = null;
        if (!StoragePlacer.placeDoubleChest(level, colony, site, StorageKind.WOOD)) {
            return null;
        }
        removeLogs(v.getInventory(), 4);
        ColonyManager.markDirty(level);
        v.swing(InteractionHand.MAIN_HAND);
        return site;
    }

    private static boolean isWoodLoot(ItemStack s) {
        return s.is(ItemTags.LOGS) || s.is(Items.STICK);
    }

    private static int countLogs(SimpleContainer inv) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ItemTags.LOGS)) n += s.getCount();
        }
        return n;
    }

    private static void removeLogs(SimpleContainer inv, int n) {
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(ItemTags.LOGS)) continue;
            int k = Math.min(n, s.getCount());
            s.shrink(k);
            n -= k;
        }
        inv.setChanged();
    }

    // ---------------------------------------------------------------- tree search

    private BlockPos findTree(ServerLevel level, Colony colony, Villager v) {
        BlockPos here = v.blockPosition();
        if (anchor == null || here.distSqr(anchor) > 256 || cursor >= SPIRAL.length) {
            anchor = here;
            cursor = 0;
        }
        int r = Math.min(colony.radius() - 4, 96);
        long r2 = (long) r * r;
        long now = level.getGameTime();
        badTrees.values().removeIf(exp -> exp <= now);
        Set<BlockPos> rejected = new HashSet<>();
        // Every log of an already rejected tree: skips repeating the BFS for each of its logs.
        Set<BlockPos> rejectedLogs = new HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int n = 0; n < 1024 && cursor < SPIRAL.length; n++, cursor++) {
            int x = anchor.getX() + SPIRAL[cursor][0];
            int z = anchor.getZ() + SPIRAL[cursor][1];
            long cdx = x - colony.core().getX();
            long cdz = z - colony.core().getZ();
            if (cdx * cdx + cdz * cdz > r2) continue;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            for (int y = top; y >= top - 40; y--) {
                if (!level.getBlockState(m.set(x, y, z)).is(BlockTags.LOGS)) continue;
                BlockPos found = m.immutable();
                if (rejectedLogs.contains(found)) continue;
                List<BlockPos> tree = connectedLogs(level, found, true);
                BlockPos b = findBase(level, tree, found);
                if (b != null && !rejected.contains(b) && !badTrees.containsKey(b) && !Nav.isBlacklisted(v, b, now)
                        && !ColonyManager.data(level).isPlayerBlock(b) && Protection.terrainModifiable(level, b)
                        && !Damage.isProtectedHousePiece(level, b) && isNaturalTree(level, b)) {
                    return b;
                }
                if (b != null) rejected.add(b);
                rejectedLogs.addAll(tree);
            }
        }
        return null;
    }

    /** Lowest log of the given connected set standing on tree soil (ties: nearest to the found log). */
    private static BlockPos findBase(ServerLevel level, List<BlockPos> tree, BlockPos found) {
        BlockPos best = null;
        for (BlockPos p : tree) {
            if (!GatherUtil.isTreeSoil(level.getBlockState(p.below()))) continue;
            if (best == null || p.getY() < best.getY() || (p.getY() == best.getY() && p.distSqr(found) < best.distSqr(found))) {
                best = p;
            }
        }
        return best;
    }

    /** BFS over the 26-neighbourhood: up to 256 logs, |dx|,|dz| <= 10, dy <= 40 (>= -40 if allowBelow, else >= 0). */
    public static List<BlockPos> connectedLogs(ServerLevel level, BlockPos start, boolean allowBelow) {
        List<BlockPos> out = new ArrayList<>();
        if (!level.getBlockState(start).is(BlockTags.LOGS)) return out;
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        seen.add(start);
        open.add(start);
        while (!open.isEmpty() && out.size() < 256) {
            BlockPos p = open.poll();
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.offset(dx, dy, dz);
                        if (Math.abs(n.getX() - start.getX()) > 10 || Math.abs(n.getZ() - start.getZ()) > 10) continue;
                        int ry = n.getY() - start.getY();
                        if (ry > 40 || ry < (allowBelow ? -40 : 0)) continue;
                        if (!seen.add(n) || !level.isLoaded(n)) continue;
                        if (level.getBlockState(n).is(BlockTags.LOGS)) open.add(n);
                    }
                }
            }
        }
        return out;
    }

    /** Natural tree vs. player build (03 §2.3); reused by other topics. */
    public static boolean isNaturalTree(ServerLevel level, BlockPos base) {
        if (!GatherUtil.isTreeSoil(level.getBlockState(base.below()))) return false;
        String path = BuiltInRegistries.BLOCK.getKey(level.getBlockState(base).getBlock()).getPath();
        if (!path.endsWith("_log") || path.startsWith("stripped_")) return false;
        List<BlockPos> logs = connectedLogs(level, base, false);
        if (logs.size() < 3) return false;
        int maxY = base.getY();
        for (BlockPos p : logs) maxY = Math.max(maxY, p.getY());
        int height = maxY - base.getY() + 1;
        if (height < 3 || height > 40) return false;
        if (GatherUtil.inStructure(level, base, 5, 0, 5)) return false;
        BlockPos from = base.offset(-4, 0, -4);
        BlockPos to = base.offset(4, height + 3, 4);
        for (int cx = from.getX() >> 4; cx <= to.getX() >> 4; cx++) {
            for (int cz = from.getZ() >> 4; cz <= to.getZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        int leaves = 0;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.LEAVES) && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) leaves++;
        }
        for (BlockPos log : logs) {
            if (log.getY() > base.getY() + 3) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockState s = level.getBlockState(log.offset(dx, dy, dz));
                        // A natural bee nest does not make the tree a build (only logs are cut, the nest stays).
                        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.BEDS) || s.is(BlockTags.DOORS) || s.is(Blocks.COBBLESTONE)
                                || s.is(Blocks.STONE_BRICKS) || (s.hasBlockEntity() && !s.is(Blocks.BEE_NEST))) {
                            return false;
                        }
                    }
                }
            }
        }
        return leaves >= 8;
    }

    // ---------------------------------------------------------------- sticks and replanting

    /** Sapling for the base log's wood type (pale oak does not exist in 1.21.1). */
    private static Block saplingFor(BlockState log) {
        String path = BuiltInRegistries.BLOCK.getKey(log.getBlock()).getPath();
        if (path.contains("dark_oak")) return Blocks.DARK_OAK_SAPLING;
        if (path.contains("spruce")) return Blocks.SPRUCE_SAPLING;
        if (path.contains("birch")) return Blocks.BIRCH_SAPLING;
        if (path.contains("acacia")) return Blocks.ACACIA_SAPLING;
        if (path.contains("jungle")) return Blocks.JUNGLE_SAPLING;
        if (path.contains("mangrove")) return Blocks.MANGROVE_PROPAGULE;
        if (path.contains("cherry")) return Blocks.CHERRY_SAPLING;
        return Blocks.OAK_SAPLING;
    }

    private void finishTree(ServerLevel level, Colony colony, Villager v) {
        if (base == null) return;
        if (cut <= 0) {
            // Nothing could be cut (protected logs): do not pick this tree again right away.
            badTrees.put(base, level.getGameTime() + BAD_TREE_TICKS);
            return;
        }
        v.getInventory().addItem(new ItemStack(Items.STICK, Math.max(1, cut / 2)));
        replant(level, colony, v);
    }

    private void replant(ServerLevel level, Colony colony, Villager v) {
        boolean big = sapling == Blocks.DARK_OAK_SAPLING;
        double d2 = base.distSqr(colony.core());
        int count;
        if (big) {
            count = d2 < 3136 && saplingsNearCore(level, colony, 7) > 6 ? 0 : 4;
        } else {
            int nearby = nearbyTrees(level, base);
            count = d2 < 1024 || nearby >= 3 ? 1 : nearby >= 1 ? 2 : 3;
            if (d2 < 3136) {
                int existing = saplingsNearCore(level, colony, 10);
                count = existing >= 10 ? 0 : Math.min(count, 10 - existing);
            }
        }
        if (count <= 0) return;
        BlockState state = sapling.defaultBlockState();
        List<BlockPos> spots;
        if (big) {
            spots = List.of(base, base.east(), base.south(), base.south().east());
            for (BlockPos p : spots) {
                if (!validSaplingSpot(level, colony, p) || !state.canSurvive(level, p)) return;
            }
        } else {
            spots = List.of(base, base.east(4), base.west(4), base.south(4), base.north(4),
                    base.offset(3, 0, 3), base.offset(-3, 0, 3), base.offset(3, 0, -3), base.offset(-3, 0, -3));
        }
        int planted = 0;
        for (BlockPos p : spots) {
            if (planted >= count) break;
            if (validSaplingSpot(level, colony, p) && state.canSurvive(level, p) && SafeEdit.place(level, p, state, 3)) {
                planted++;
            }
        }
        if (planted > 0) v.swing(InteractionHand.MAIN_HAND);
    }

    /** Columns in a radius-20 disc holding a sapling or a log on tree soil within the top 24 blocks; stops at 12. */
    private static int nearbyTrees(ServerLevel level, BlockPos center) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                if (dx * dx + dz * dz > 400) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                for (int y = top; y > top - 24; y--) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (s.is(BlockTags.SAPLINGS) || (s.is(BlockTags.LOGS) && GatherUtil.isTreeSoil(level.getBlockState(m.set(x, y - 1, z))))) {
                        if (++n >= 12) return n;
                        break;
                    }
                }
            }
        }
        return n;
    }

    /** Sapling blocks in a radius-48 disc around the core (top 32 blocks of each column), early exit at limit. */
    private static int saplingsNearCore(ServerLevel level, Colony colony, int limit) {
        int n = 0;
        BlockPos core = colony.core();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -48; dx <= 48; dx++) {
            for (int dz = -48; dz <= 48; dz++) {
                if (dx * dx + dz * dz > 48 * 48) continue;
                int x = core.getX() + dx, z = core.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = top; y > top - 32; y--) {
                    if (level.getBlockState(m.set(x, y, z)).is(BlockTags.SAPLINGS) && ++n >= limit) return n;
                }
            }
        }
        return n;
    }

    private static boolean validSaplingSpot(ServerLevel level, Colony colony, BlockPos pos) {
        BlockPos soil = pos.below();
        if (!GatherUtil.freeNaturalGround(level, colony, soil)) return false;
        if (!Protection.terrainModifiable(level, pos) || ColonyManager.data(level).isPlayerBlock(soil)) return false;
        BlockState s = level.getBlockState(pos);
        if (!s.isAir() && !s.canBeReplaced()) return false;
        if (!level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()) return false;
        BlockState soilState = level.getBlockState(soil);
        if (!soilState.is(BlockTags.DIRT) || soilState.is(Blocks.DIRT_PATH) || soilState.is(Blocks.FARMLAND)) return false;
        return !GatherUtil.inStructure(level, pos, 3, 0, 3);
    }

    private static int[][] buildSpiral(int r) {
        List<int[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r) list.add(new int[]{dx, dz});
            }
        }
        list.sort(Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        return list.toArray(new int[0][]);
    }
}
