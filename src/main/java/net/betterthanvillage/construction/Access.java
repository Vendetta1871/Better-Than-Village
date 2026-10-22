package net.betterthanvillage.construction;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.politics.Countries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Paths to unreachable houses (docs_v2/04-land.md §Terraforming and demolition): now and then the village checks
 * that a villager by the bell can walk to each household's bed. For a bed it cannot reach, the miner fixes the
 * entrance of its house: blocks at head height in front of a door (a roof eave above the entrance steps of a
 * generated house, a block dropped there) are taken down, and a missing step below the doorway is filled. The
 * plan goes to {@link LandWork}'s access list; {@link LandWorkTask} does the work.
 */
public final class Access {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks between two bed checks of a village (one bed per check). */
    static final long CHECK_INTERVAL = 600;
    /** A bed found reachable (or fixed) is checked again after this long. */
    private static final long RECHECK = 24000;
    private static final String CHECKED = "btv_access_checked";
    private static final String CURSOR = "btv_access_cursor";
    /** Searches chained for one walk (each from where the last ended). */
    private static final int LEGS = 4;

    private Access() {
    }

    /** One planning step: the next household bed is checked and, if unreachable, its doors are planned for. */
    static void plan(ServerLevel level, Colony colony, LandWork.State s, long now) {
        List<Household> homes = new ArrayList<>();
        for (Household h : colony.households.values()) if (h.bed != null && level.isLoaded(h.bed)) homes.add(h);
        if (homes.isEmpty()) return;
        homes.sort(Comparator.comparing(h -> h.owner));
        int cursor = Math.floorMod((Integer) colony.runtime.getOrDefault(CURSOR, 0), homes.size());
        colony.runtime.put(CURSOR, cursor + 1);
        BlockPos bed = homes.get(cursor).bed;
        Map<BlockPos, Long> checked = colony.runtime(CHECKED, java.util.HashMap::new);
        Long last = checked.get(bed);
        if (last != null && now - last < RECHECK) return;
        Walk walk = walk(level, colony, bed);
        if (walk == null) return;
        checked.put(bed, now);
        if (walk.reaches()) return;
        // A way from where the walk ends to a door of the house (or to the bed), digging and filling where needed.
        List<BlockPos> goals = new ArrayList<>();
        for (BlockPos door : doors(level, bed)) {
            Direction facing = level.getBlockState(door).getValue(DoorBlock.FACING);
            for (Direction out : new Direction[]{facing, facing.getOpposite()}) {
                BlockPos a = door.relative(out);
                if (a.distSqr(bed) > door.distSqr(bed)) goals.add(a);
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) goals.add(bed.relative(d));
        Map<BlockPos, Boolean> edits = Trail.plan(level, walk.walker(), walk.end(), goals);
        if (edits == null) {
            LOGGER.info("[village] {}: the bed at {} cannot be reached and no way to it was found from {}", Countries.villageName(colony),
                    bed.toShortString(), walk.end().toShortString());
            return;
        }
        // A way with nothing to change: the villagers' own search fails there for other reasons; nothing to do.
        if (edits.isEmpty()) return;
        edits.forEach((p, place) -> s.access.putIfAbsent(p, place));
        StringBuilder list = new StringBuilder();
        edits.forEach((p, place) -> list.append(place ? " fill " : " dig ").append(p.toShortString()).append(' ')
                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath()).append(';'));
        LOGGER.info("[village] {}: the bed at {} cannot be reached from {}; the miners change {} blocks on the way:{}", Countries.villageName(colony),
                bed.toShortString(), walk.end().toShortString(), edits.size(), list);
    }

    /** Where a walk from the bell toward the bed ends: next to the bed when it is reachable. */
    record Walk(Villager walker, BlockPos end, boolean reaches) {
    }

    /** A villager by the bell walks toward the bed; null when no check could run now. */
    static Walk walk(ServerLevel level, Colony colony, BlockPos bed) {
        BlockPos c = colony.core();
        List<Villager> near = level.getEntitiesOfClass(Villager.class, new AABB(c).inflate(16, 8, 16),
                v -> v.isAlive() && !v.isBaby() && v.onGround() && ColonyManager.colonyOf(level, v) == colony);
        if (near.isEmpty() || !PathBudget.tryAcquire(level)) return null;
        Villager v = near.stream().min(Comparator.comparingDouble(b -> b.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5))).get();
        // As a villager walks to its bed: to the bed itself; standing beside it (diagonals too) is enough. One search
        // is cut short by the follow range on a long way, and a villager walking there plans again as it goes: so
        // does the check, from where each leg ends, while the legs get closer.
        Villager walker = v;
        BlockPos end = v.blockPosition();
        for (int leg = 0; leg < LEGS; leg++) {
            Path path = walker.getNavigation().createPath(bed, 1);
            BlockPos next = path == null || path.getEndNode() == null ? walker.blockPosition() : path.getEndNode().asBlockPos();
            boolean closer = next.distSqr(bed) < end.distSqr(bed);
            if (closer || leg == 0) end = next;
            if (reaches(level, end, bed) || !closer || leg == LEGS - 1) break;
            // A villager standing where the leg ended (not added to the world) walks on.
            Villager probe = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            if (probe == null) break;
            probe.moveTo(end.getX() + 0.5, end.getY(), end.getZ() + 0.5, 0, 0);
            probe.setOnGround(true);
            walker = probe;
        }
        return new Walk(v, end, reaches(level, end, bed));
    }

    /**
     * The walk stops by the bed's side or foot, inside its room: within three blocks, and the bed in sight from
     * there (a spot behind the house wall is as near, but outside).
     */
    private static boolean reaches(ServerLevel level, BlockPos end, BlockPos bed) {
        if (Math.abs(end.getX() - bed.getX()) > 3 || Math.abs(end.getZ() - bed.getZ()) > 3 || Math.abs(end.getY() - bed.getY()) > 1) return false;
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(end.getX() + 0.5, end.getY() + 1.5, end.getZ() + 0.5);
        net.minecraft.world.phys.Vec3 top = new net.minecraft.world.phys.Vec3(bed.getX() + 0.5, bed.getY() + 0.4, bed.getZ() + 0.5);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, top, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                || level.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    /** Lower halves of the doors near the bed (its house's doors), nearest first. */
    static List<BlockPos> doors(ServerLevel level, BlockPos bed) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-8, -3, -8), bed.offset(8, 3, 8))) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(bed)));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

}
