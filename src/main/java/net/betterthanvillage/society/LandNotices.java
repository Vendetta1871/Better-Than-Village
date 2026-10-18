package net.betterthanvillage.society;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.LandWarning;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Unauthorized building notices (06 §3): record per player, elder delivery, punishment after the grace period. */
public final class LandNotices {
    public static final long GRACE = 600;
    public static final long NOTICE_LIFETIME = 24000;

    private LandNotices() {
    }

    // ---------------------------------------------------------------- placements (06 §3.1, §3.2)

    /** "Structural" placed states: full cube, door, bed, chest, barrel, stairs or slab. */
    static boolean structural(BlockState state) {
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || state.getBlock() instanceof DoorBlock
                || state.is(BlockTags.BEDS) || state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock;
    }

    /** Violation check for a successful player placement. */
    public static void onPlacement(ServerLevel level, Player player, BlockPos pos, BlockState state) {
        if (player.isCreative() || player.isSpectator() || !structural(state)) return;
        if (Deeds.ownsPosition(level, player.getUUID(), pos)) return;
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null) return;
        double max = Math.min(96, colony.radius());
        if (colony.core().distSqr(pos) > max * max) return;
        long now = level.getGameTime();
        ColonySavedData data = ColonyManager.data(level);
        LandWarning record = find(data, player);
        if (record != null && record.colony.equals(colony.id()) && (record.delivered < 0 || now - record.delivered <= NOTICE_LIFETIME)) {
            record.pos = pos.immutable();
            if (record.delivered >= 0 && now - record.delivered >= GRACE) {
                record.continued = Math.min(3, record.continued + 1);
                if (record.continued >= 3) {
                    record.punished = true;
                    ThreatManager.addPlayerHostility(level, colony, player, 4);
                }
            }
        } else {
            if (record != null) data.landWarnings.remove(record);
            LandWarning fresh = new LandWarning();
            fresh.player = player.getUUID();
            fresh.colony = colony.id();
            fresh.pos = pos.immutable();
            data.landWarnings.add(fresh);
        }
        data.setDirty();
    }

    private static LandWarning find(ColonySavedData data, Player player) {
        for (LandWarning w : data.landWarnings) {
            if (player.getUUID().equals(w.player)) return w;
        }
        return null;
    }

    // ---------------------------------------------------------------- delivery (06 §3.3)

    private static boolean daytime(ServerLevel level) {
        return Math.floorMod(level.getDayTime(), 24000L) < 12000;
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 20 != 0 || !daytime(level)) return;
        ColonySavedData data = ColonyManager.data(level);
        for (LandWarning w : data.landWarnings) {
            if (w.delivered >= 0) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(w.player);
            if (player == null || player.isSpectator() || player.level() != level) continue;
            Colony colony = data.colony(w.colony);
            if (colony == null) continue;
            Villager elder = findElder(level, colony);
            if (elder == null) continue;
            if (elder.distanceToSqr(player) > 16) {
                elder.getNavigation().moveTo(player, 0.9);
                continue;
            }
            ItemStack notice = Deeds.landNotice(Prices.landPrice(colony));
            if (!player.getInventory().add(notice)) {
                player.drop(notice, false);
            }
            Msg.chat(player, Msg.item("land_notice.warning"));
            w.delivered = now;
            Nav.reset(elder);
            data.setDirty();
        }
    }

    private static Villager findElder(ServerLevel level, Colony colony) {
        for (Villager v : ColonyManager.adultMembers(level, colony)) {
            if (colony.roleOf(v.getUUID()) == ColonyRole.ELDER && !v.isSleeping() && v.getTradingPlayer() == null) return v;
        }
        return null;
    }

    /** Elder walks to an undelivered notice recipient; returns true when it took over the villager this tick. */
    public static boolean elderWalk(ServerLevel level, Villager v, Colony colony) {
        if (colony.roleOf(v.getUUID()) != ColonyRole.ELDER || v.isSleeping() || v.getTradingPlayer() != null || !daytime(level)) return false;
        long now = level.getGameTime();
        if (colony.lastThreatTick != Colony.NEVER && now - colony.lastThreatTick < 200) return false;
        ColonySavedData data = ColonyManager.data(level);
        if (data.landWarnings.isEmpty()) return false;
        long r2 = (long) colony.radius() * colony.radius();
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (LandWarning w : data.landWarnings) {
            if (w.delivered >= 0 || !colony.id().equals(w.colony)) continue;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(w.player);
            if (p == null || p.isSpectator() || p.level() != level || p.blockPosition().distSqr(colony.core()) > r2) continue;
            double d = v.distanceToSqr(p);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null) return false;
        VillagerUtil.doExclusively(v, ModRegistries.COLONY_WORK.get());
        if (best > 16) {
            Nav.approach(level, v, nearest.blockPosition(), 0.9);
        } else {
            Nav.reset(v);
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            v.getLookControl().setLookAt(nearest);
        }
        return true;
    }
}
