package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.SafeEdit;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Predicate;

/** Builder repair task: restores recorded colony blocks (05 §6.3). */
public final class HouseRepairTask extends ColonyTask {
    static final String STATUS_SPOT_TAKEN = "ремонт: место занято";
    static final String STATUS_NEED_MATERIALS = "ремонт: нужны материалы";
    static final String STATUS_SEARCH_APPROACH = "ремонт: поиск безопасного подхода";

    private BlockPos target;
    private BlockPos stand;
    private long startTime;

    public HouseRepairTask() {
        super(600);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && !v.isTrading() && colony.roleOf(v.getUUID()) == ColonyRole.BUILDER && HousePlanner.established(level, colony)
                && ColonyManager.terrainChangesAllowed(level) && Damage.hasActionableRepair(level, colony);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        return colony != null && shouldRun(level, v, colony);
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        target = null;
        stand = null;
        startTime = now;
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        if (colony != null && target != null && colony.repairs.containsKey(target) && now - startTime >= 500) {
            Damage.cooldowns(colony).put(target, now + Damage.REPAIR_COOLDOWN);
        }
        target = null;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (now % 10 != 0) return;
        Colony colony = colony(level, v);
        if (colony == null) return;
        target = null;
        double best = Double.MAX_VALUE;
        Damage.Supply[] supply = new Damage.Supply[1];
        for (Map.Entry<BlockPos, BlockState> e : new ArrayList<>(colony.repairs.entrySet())) {
            double d = e.getKey().distSqr(v.blockPosition());
            if (d < best && Damage.actionable(level, colony, e.getKey(), e.getValue(), supply)) {
                best = d;
                target = e.getKey();
            }
        }
        if (target == null) return;
        BlockPos pos = target;
        BlockState intended = colony.repairs.get(pos);
        BlockState world = level.getBlockState(pos);
        if (world.getBlock() == intended.getBlock()) {
            colony.repairs.remove(pos);
            ColonyManager.markDirty(level);
            return;
        }
        if (!(world.isAir() || world.canBeReplaced())) {
            WorkTracker.status(v, STATUS_SPOT_TAKEN);
            return;
        }
        BlockPos partner = partnerPos(pos, intended);
        BlockState partnerState = null;
        if (partner != null) {
            partnerState = colony.repairs.getOrDefault(partner, partnerState(intended));
            BlockState pw = level.getBlockState(partner);
            if (pw.getBlock() == partnerState.getBlock()) {
                colony.repairs.remove(partner);
                partner = null;
            } else if (!(pw.isAir() || pw.canBeReplaced()) || !Protection.terrainModifiable(level, partner)) {
                return;
            }
        }
        Predicate<ItemStack> accepted = s -> BuildPlan.accepts(intended, s);
        ItemStack stack = accepted.test(v.getMainHandItem()) ? v.getMainHandItem() : ItemStack.EMPTY;
        if (stack.isEmpty()) {
            SimpleContainer inv = v.getInventory();
            for (int i = 0; i < inv.getContainerSize() && stack.isEmpty(); i++) {
                if (accepted.test(inv.getItem(i))) stack = inv.getItem(i);
            }
        }
        if (stack.isEmpty()) {
            BlockPos storage = ColonyStorage.nearestContaining(level, colony, v.blockPosition(), accepted);
            if (storage == null) {
                WorkTracker.status(v, STATUS_NEED_MATERIALS);
                return;
            }
            if (v.blockPosition().distSqr(storage) > 9) {
                Nav.approach(level, v, storage, 0.8, Nav.R2_DEFAULT);
                return;
            }
            Item item = BuildPlan.materialItem(intended);
            int same = 0;
            for (BlockState s : colony.repairs.values()) {
                if (BuildPlan.materialItem(s) == item) same++;
            }
            ItemStack taken = ColonyStorage.takeFrom(level, colony, storage, accepted, Math.max(1, Math.min(16, same)));
            ItemStack rest = v.getInventory().addItem(taken);
            if (!rest.isEmpty()) ColonyStorage.deposit(level, colony, rest);
            if (!rest.isEmpty()) v.spawnAtLocation(rest);
            return;
        }
        if (!HouseConstructionTask.withinReach(v, pos)) {
            approach(level, v, pos, now);
            return;
        }
        if (!intended.getCollisionShape(level, pos).isEmpty() && Fortifications.occupied(level, pos)) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e.isAlive() && !e.isSpectator())) {
                if (e instanceof Mob mob && PathBudget.tryAcquire(level)) {
                    Paths.send(level, mob, aside(level, colony, pos), 0.8, Nav.R2_DEFAULT);
                }
            }
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        BlockState before = world;
        // flag 3: neighbours re-shape to the restored block (panes, fences, the other half of a double chest)
        if (!SafeEdit.place(level, pos, intended, 3)) return;
        if (partner != null && !SafeEdit.place(level, partner, partnerState, 3)) {
            SafeEditScope.run(() -> level.setBlock(pos, before, 3));
            return;
        }
        stack.shrink(1);
        if (v.getMainHandItem().isEmpty()) v.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, "восстановление дома", 1);
        colony.repairs.remove(pos);
        if (partner != null) colony.repairs.remove(partner);
        ColonyManager.markDirty(level);
        target = null;
    }

    /** Door: other half; bed: the other part along facing. */
    static BlockPos partnerPos(BlockPos pos, BlockState s) {
        if (s.getBlock() instanceof DoorBlock) {
            return s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (s.getBlock() instanceof BedBlock) {
            Direction facing = s.getValue(BedBlock.FACING);
            return s.getValue(BedBlock.PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
        }
        return null;
    }

    static BlockState partnerState(BlockState s) {
        if (s.getBlock() instanceof DoorBlock) {
            return s.setValue(DoorBlock.HALF, s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        }
        return s.setValue(BedBlock.PART, s.getValue(BedBlock.PART) == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
    }

    /** Closest standable position within 4 horizontally / 2 vertically, at least 2 blocks away (fallback: core). */
    static BlockPos aside(ServerLevel level, Colony colony, BlockPos pos) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, -2, -4), pos.offset(4, 2, 4))) {
            double d = p.distSqr(pos);
            if (d < 4 || d >= bestD || !Nav.standable(level, p)) continue;
            best = p.immutable();
            bestD = d;
        }
        return best != null ? best : colony.core();
    }

    private void approach(ServerLevel level, Villager v, BlockPos pos, long now) {
        if (stand != null && Nav.standable(level, stand) && HouseConstructionTask.horizontal(stand, pos) <= 16
                && Math.abs(stand.getY() - pos.getY()) <= 13 && !Nav.isBlacklisted(v, stand, now)) {
            Nav.approach(level, v, stand, 0.85, Nav.R2_DEFAULT);
            return;
        }
        stand = null;
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                for (int y = pos.getY() - 13; y <= pos.getY() + 3; y++) {
                    BlockPos c = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                    if (c.equals(pos) || c.equals(pos.above()) || !Nav.standable(level, c) || Nav.isBlacklisted(v, c, now)) continue;
                    candidates.add(c);
                }
            }
        }
        if (candidates.isEmpty() || !PathBudget.tryAcquire(level)) return;
        Path path = Paths.find(v, candidates);
        if (path != null && path.canReach() && path.getTarget() != null) {
            stand = path.getTarget();
            Nav.approach(level, v, stand, 0.85, Nav.R2_DEFAULT);
        } else {
            WorkTracker.status(v, STATUS_SEARCH_APPROACH);
        }
    }
}
