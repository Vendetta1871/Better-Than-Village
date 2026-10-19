package net.betterthanvillage.ai.nav;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.construction.Fortifications;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.betterthanvillage.mixin.gather.TrapDoorBlockAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Doors, gates and trapdoors for colony villagers (03 §5.4); replaces vanilla door interaction. */
public final class DoorHandler {
    private static final Map<ServerLevel, Map<BlockPos, Long>> RECORDED = new WeakHashMap<>();

    private DoorHandler() {
    }

    private enum Kind { GATE, DOOR, TRAPDOOR }

    /** Called every tick for every villager. */
    public static void tick(ServerLevel level, Villager v) {
        Colony colony = ColonyManager.foundingPartyColony(level, v.getUUID());
        if (colony == null) colony = ColonyManager.colonyAt(level, v.blockPosition());
        if (colony == null) return;
        if (v.getNavigation() instanceof GroundPathNavigation ground) {
            ground.setCanOpenDoors(!v.isSleeping());
        }
        // Sleeping villagers neither open nor close anything.
        if (v.isSleeping()) return;
        long now = level.getGameTime();
        if ((now + v.getId()) % 3 != 0) return;
        BlockPos center = v.blockPosition();
        BlockPos penGate = colony.livestockPen != null ? colony.livestockPen.north(5) : null;
        boolean night = Math.floorMod(level.getDayTime(), 24000L) >= 12000;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 2, 4))) {
            BlockState s = level.getBlockState(p);
            Kind kind = kindOf(s);
            if (kind == null) continue;
            if (kind == Kind.GATE && (Fortifications.isWallBlock(level, p) || p.equals(penGate))) continue;
            if (kind == Kind.DOOR && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) continue;
            if (s.hasProperty(BlockStateProperties.POWERED) && s.getValue(BlockStateProperties.POWERED)) continue;
            Vec3 c = Vec3.atCenterOf(p);
            double d2 = v.position().distanceToSqr(c);
            if (d2 > 16.25) continue;
            BlockPos pos = p.immutable();
            boolean open = s.getValue(BlockStateProperties.OPEN);
            if (needed(level, v, pos, kind, d2, now)) {
                if (!open) {
                    setOpen(level, v, pos, s, kind, true);
                    RECORDED.computeIfAbsent(level, l -> new HashMap<>()).put(pos, now);
                } else {
                    // Still on the path: keep a door we opened from closing in front of the villager.
                    Map<BlockPos, Long> rec = RECORDED.get(level);
                    if (rec != null) rec.computeIfPresent(pos, (k, t) -> now);
                }
            } else if (night && open) {
                RECORDED.computeIfAbsent(level, l -> new HashMap<>()).putIfAbsent(pos, now - 12);
            }
        }
    }

    private static Kind kindOf(BlockState s) {
        if (s.getBlock() instanceof FenceGateBlock) return Kind.GATE;
        if (s.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()) return Kind.DOOR;
        if (s.getBlock() instanceof TrapDoorBlock trap && ((TrapDoorBlockAccessor) trap).btv$type().canOpenByHand()) return Kind.TRAPDOOR;
        return null;
    }

    private static boolean needed(ServerLevel level, Villager v, BlockPos pos, Kind kind, double d2, long now) {
        if (v.isSleeping() || v.getTradingPlayer() != null) return false;
        if (kind == Kind.TRAPDOOR && pos.equals(v.blockPosition().below())) return false;
        // A closed trapdoor lying on the ground is walkable; opening it flips it upright and walls
        // the villager in (the vanilla self-locking bug), so leave it shut.
        if (kind == Kind.TRAPDOOR && level.getBlockState(pos).getValue(TrapDoorBlock.HALF) == Half.BOTTOM
                && !level.getBlockState(pos).getValue(TrapDoorBlock.OPEN)) return false;
        Path path = v.getNavigation().getPath();
        if (path != null && !path.isDone() && d2 <= 12) {
            int idx = path.getNextNodeIndex();
            for (int i = Math.max(0, idx - 1); i < Math.min(path.getNodeCount(), idx + 3); i++) {
                Node n = path.getNode(i);
                if (Math.abs(n.x - pos.getX()) <= 1 && Math.abs(n.y - pos.getY()) <= 1 && Math.abs(n.z - pos.getZ()) <= 1) return true;
            }
        }
        if (kind == Kind.TRAPDOOR && d2 <= 9) {
            BlockPos target = Nav.currentTarget(v, now);
            if (target == null) {
                Optional<WalkTarget> walk = v.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
                if (walk.isPresent()) target = walk.get().getTarget().currentBlockPosition();
            }
            if (target != null) {
                Vec3 a = v.position().add(0, 0.5, 0);
                Vec3 b = Vec3.atCenterOf(target).add(0, 0.5, 0);
                return segmentDist2(a, b, Vec3.atCenterOf(pos)) <= 1.5;
            }
        }
        return false;
    }

    private static double segmentDist2(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1.0E-9 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return a.add(ab.scale(t)).distanceToSqr(p);
    }

    private static void setOpen(ServerLevel level, LivingEntity actor, BlockPos pos, BlockState s, Kind kind, boolean open) {
        switch (kind) {
            case GATE -> level.setBlock(pos, s.setValue(FenceGateBlock.OPEN, open), 3);
            case DOOR -> ((DoorBlock) s.getBlock()).setOpen(actor, level, s, pos, open);
            case TRAPDOOR -> {
                level.setBlock(pos, s.setValue(TrapDoorBlock.OPEN, open), 3);
                BlockSetType type = ((TrapDoorBlockAccessor) s.getBlock()).btv$type();
                level.playSound(null, pos, open ? type.trapdoorOpen() : type.trapdoorClose(), SoundSource.BLOCKS, 1.0f, 1.0f);
                level.gameEvent(actor, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
            }
        }
    }

    /** Called every world tick; closes recorded blocks every 5 ticks. */
    public static void processRecorded(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 5 != 0) return;
        Map<BlockPos, Long> map = RECORDED.get(level);
        if (map == null) return;
        Iterator<Map.Entry<BlockPos, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            BlockPos pos = e.getKey();
            if (!level.isLoaded(pos)) {
                it.remove();
                continue;
            }
            BlockState s = level.getBlockState(pos);
            Kind kind = kindOf(s);
            if (kind == null || Fortifications.isWallBlock(level, pos)
                    || (s.hasProperty(BlockStateProperties.POWERED) && s.getValue(BlockStateProperties.POWERED))) {
                it.remove();
                continue;
            }
            if (now - e.getValue() < 12) continue;
            AABB box = new AABB(pos).inflate(0.15, 0.2, 0.15);
            boolean occupied = !level.getEntitiesOfClass(LivingEntity.class, box,
                    ent -> ent instanceof Villager || ent instanceof IronGolem || ent instanceof Player).isEmpty();
            if (occupied) {
                e.setValue(now);
                continue;
            }
            if (s.getValue(BlockStateProperties.OPEN)) {
                setOpen(level, null, pos, s, kind, false);
            }
            it.remove();
        }
        if (map.isEmpty()) RECORDED.remove(level);
    }

    public static void clearCaches() {
        RECORDED.clear();
    }
}
