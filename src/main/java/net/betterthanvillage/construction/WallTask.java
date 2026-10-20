package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.FortBlock;
import net.betterthanvillage.colony.FortificationState;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Builder wall task (05 §4.5). */
public final class WallTask extends ColonyTask {
    static final String STATUS_NEW_APPROACH = "стена: ищет другой подход";

    private final BlockWorkTimer timer = new BlockWorkTimer();
    private FortBlock current;
    private long jobStart;
    private long nextApproach;

    public WallTask() {
        super(12000);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        // repairs come first (isWallBuilder already yields no wall builder while a repair is actionable; kept explicit)
        return !v.isSleeping() && !v.isTrading() && colony.roleOf(v.getUUID()) == ColonyRole.MASON
                && !Damage.hasActionableRepair(level, colony) && Fortifications.isWallBuilder(level, colony, v)
                && net.betterthanvillage.economy.Commissions.canPay(level, colony);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        return colony != null && shouldRun(level, v, colony);
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        current = null;
        timer.reset();
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
        current = null;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        if (colony == null || !ColonyManager.terrainChangesAllowed(level)) return;
        FortificationState f = colony.fortifications;
        if (current == null || f.pending.get(current.pos()) != current) {
            current = Fortifications.selectJob(level, colony, v.blockPosition(), Fortifications.requireNear(level, colony));
            jobStart = now;
            timer.cancel(level);
            if (current == null) return;
        }
        FortBlock job = current;
        BlockPos pos = job.pos();
        BlockState world = level.getBlockState(pos);
        if (!job.remove() && (waterColumn(level, pos) || inPen(colony, pos))) {
            Fortifications.abandonColumn(colony, pos);
            current = null;
            return;
        }
        if (job.remove() && (world.getBlock() != job.state().getBlock() || !f.managed.containsKey(pos))) {
            finish(level, colony, false);
            return;
        }
        if (!job.remove() && nearActivePlot(colony, pos)) {
            // retry once the plot is finished instead of leaving a permanent gap
            Fortifications.postpone(colony, job, now);
            current = null;
            timer.cancel(level);
            return;
        }
        if (Fortifications.blockedPosition(level, pos) || Damage.isProtectedHousePiece(level, pos)) {
            finish(level, colony, false);
            return;
        }
        if (!job.remove() && world.getBlock() == job.state().getBlock()) {
            finish(level, colony, f.managed.containsKey(pos));
            return;
        }
        if (now - jobStart > 600) {
            Fortifications.fail(colony, job, now);
            WorkTracker.status(v, STATUS_NEW_APPROACH);
            current = null;
            timer.cancel(level);
            return;
        }
        boolean natural = world.is(BlockTags.LEAVES) || world.is(BlockTags.LOGS);
        if (!job.remove() && !world.isAir() && !world.canBeReplaced() && !natural && world.isSolid()) {
            finish(level, colony, false);
            return;
        }
        if (v.getBoundingBox().intersects(new AABB(pos)) || HouseConstructionTask.standsOn(v, pos)) {
            for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                int x = v.getBlockX() + d.getStepX() * 2, z = v.getBlockZ() + d.getStepZ() * 2;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (Nav.standable(level, p)) {
                    Nav.approach(level, v, p, 0.8, Nav.R2_DEFAULT);
                    break;
                }
            }
            return;
        }
        double dx = v.getX() - (pos.getX() + 0.5), dz = v.getZ() - (pos.getZ() + 0.5);
        if (dx * dx + dz * dz <= 9 && Math.abs(v.getY() - pos.getY()) <= 5) {
            v.getNavigation().stop();
            v.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            if (!job.remove() && natural) {
                if (timer.tick(level, v, pos, true)) {
                    timer.reset();
                    if (Protection.terrainModifiable(level, pos)) {
                        SafeEditScope.run(() -> level.destroyBlock(pos, true, v));
                    }
                }
            } else if (job.remove()) {
                if (timer.tick(level, v, pos, true)) {
                    timer.reset();
                    removeBlock(level, colony, v, pos, world);
                }
            } else if (timer.tickAction(level, v, pos, ColonyConfig.get().work.placement_ticks)) {
                timer.reset();
                placeBlock(level, colony, v, job);
            }
            return;
        }
        if (now >= nextApproach) {
            nextApproach = now + 20;
            Nav.approach(level, v, pos, 0.8, Nav.R2_DEFAULT);
        }
    }

    private void finish(ServerLevel level, Colony colony, boolean placed) {
        Fortifications.complete(level, colony, current, placed);
        current = null;
        timer.cancel(level);
    }

    private void removeBlock(ServerLevel level, Colony colony, Villager v, BlockPos pos, BlockState state) {
        if (v.blockPosition().equals(pos) || HouseConstructionTask.standsOn(v, pos)) return;
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), v, v.getMainHandItem());
        boolean removed = SafeEditScope.call(() -> level.destroyBlock(pos, false, v));
        if (!removed) return;
        for (ItemStack drop : drops) {
            ColonyStorage.deposit(level, colony, drop);
            if (!drop.isEmpty()) v.spawnAtLocation(drop);
        }
        finish(level, colony, true);
        WorkTracker.credit(v, "перенос старой стены", 1);
        v.swing(InteractionHand.MAIN_HAND);
    }

    private void placeBlock(ServerLevel level, Colony colony, Villager v, FortBlock job) {
        BlockPos pos = job.pos();
        v.swing(InteractionHand.MAIN_HAND);
        if (Fortifications.stock(level, colony, job.state()) <= Fortifications.stockReserve(colony) || Fortifications.occupied(level, pos)) return;
        boolean ok = ColonyStorage.transaction(level, colony, () -> {
            ItemStack one = ColonyStorage.withdrawOne(level, colony, s -> Fortifications.accepts(job.state(), s));
            return !one.isEmpty() && SafeEditScope.call(() -> level.setBlock(pos, job.state(), 3));
        }, List.of());
        if (ok) {
            Fortifications.runtime(colony).stockTime = Long.MIN_VALUE;
            finish(level, colony, true);
            WorkTracker.credit(v, "стена деревни", 1);
        }
    }

    /** Fluid found scanning pos, pos-1 .. pos-4 before the first non-air, non-replaceable block. */
    static boolean waterColumn(ServerLevel level, BlockPos pos) {
        for (int i = 0; i <= 4; i++) {
            BlockPos p = pos.below(i);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) return true;
            if (!s.isAir() && !s.canBeReplaced()) return false;
        }
        return false;
    }

    static boolean inPen(Colony colony, BlockPos pos) {
        BlockPos pen = colony.livestockPen;
        return pen != null && Math.abs(pos.getX() - pen.getX()) <= 6 && Math.abs(pos.getZ() - pen.getZ()) <= 6;
    }

    static boolean nearActivePlot(Colony colony, BlockPos pos) {
        for (ConstructionJob j : colony.jobs) {
            if (!HousePlanner.activePlot(j)) continue;
            BoundingBox b = j.box();
            if (pos.getX() >= b.minX() - 1 && pos.getX() <= b.maxX() + 1 && pos.getZ() >= b.minZ() - 1 && pos.getZ() <= b.maxZ() + 1) return true;
        }
        return false;
    }
}
