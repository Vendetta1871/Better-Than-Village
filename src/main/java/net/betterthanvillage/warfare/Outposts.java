package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Outposts (docs_v2/07-warfare.md §Outposts): a small fortified camp the army builds near its target before every
 * attack, to a code-generated plan of vanilla blocks: a palisade with an opening toward the target, a watchtower,
 * a chest and beds. No attack starts before it stands; losing it stalls the campaign until it is rebuilt, and an army
 * that loses it too often breaks off.
 */
public final class Outposts {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Half size of the palisade square. */
    private static final int HALF = 4;

    private Outposts() {
    }

    /** One block of the plan. */
    record Part(BlockPos pos, BlockState state) {
    }

    /** The plan around the centre (the ground block positions are found column by column). */
    static List<Part> plan(ServerLevel level, Campaign c, BlockPos center) {
        return planAround(level, center, aim(level, c));
    }

    static List<Part> planAround(ServerLevel level, BlockPos center, BlockPos aim) {
        List<Part> out = new ArrayList<>();
        Direction open = Direction.getNearest(aim.getX() - center.getX(), 0, aim.getZ() - center.getZ());
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                boolean edge = Math.abs(dx) == HALF || Math.abs(dz) == HALF;
                if (!edge) continue;
                // The opening: the middle of the side facing the target.
                int along = open.getAxis() == Direction.Axis.X ? dz : dx;
                int across = open.getAxis() == Direction.Axis.X ? dx : dz;
                if (Math.abs(along) <= 1 && across == HALF * open.getAxisDirection().getStep()) continue;
                BlockPos g = ground(level, center.getX() + dx, center.getZ() + dz);
                boolean corner = Math.abs(dx) == HALF && Math.abs(dz) == HALF;
                if (corner && dx < 0 && dz < 0) {
                    // The watchtower.
                    for (int y = 0; y < 4; y++) out.add(new Part(g.above(y), Blocks.SPRUCE_LOG.defaultBlockState()));
                    out.add(new Part(g.above(4), Blocks.TORCH.defaultBlockState()));
                } else {
                    out.add(new Part(g, Blocks.OAK_FENCE.defaultBlockState()));
                    out.add(new Part(g.above(), Blocks.OAK_FENCE.defaultBlockState()));
                }
            }
        }
        BlockPos chest = ground(level, center.getX(), center.getZ());
        out.add(new Part(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, open)));
        BlockPos foot = ground(level, center.getX() - 2, center.getZ() + 1);
        out.add(new Part(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT)));
        out.add(new Part(foot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD)));
        return out;
    }

    private static BlockPos aim(ServerLevel level, Campaign c) {
        if (c.kind == Campaign.Kind.PLAYER) return c.base;
        Colony target = ColonyManager.data(level).colony(c.target);
        return target == null ? c.outpost : target.core();
    }


    /** Live: the outpost's blocks go up as the labour progresses (all of them once it is built). */
    static void tickLive(ServerLevel level, Campaign c, BlockPos center) {
        if (c.outpostPlaced || !level.isPositionEntityTicking(center)) return;
        int work = Campaigns.cfg().outpost_work;
        double share = c.outpostBuilt ? 1 : Math.min(1, c.outpostWork / (double) work);
        List<Part> parts = plan(level, c, center);
        int upTo = (int) Math.floor(parts.size() * share);
        int placed = 0;
        for (int i = 0; i < upTo; i++) {
            Part p = parts.get(i);
            BlockState here = level.getBlockState(p.pos());
            if (here.getBlock() == p.state().getBlock()) {
                placed++;
                continue;
            }
            boolean chest = p.state().getBlock() instanceof ChestBlock;
            // The chest is the outpost's heart: it goes in even where a natural block stands.
            boolean force = chest && !here.hasBlockEntity() && here.getDestroySpeed(level, p.pos()) >= 0
                    && !ColonyManager.data(level).isPlayerBlock(p.pos());
            if ((!here.canBeReplaced() && !force) || !net.betterthanvillage.colony.Protection.terrainModifiable(level, p.pos())) {
                placed++;
                continue;
            }
            SafeEditScope.run(() -> level.setBlock(p.pos(), p.state(), 3));
            placed++;
            break;
        }
        if (c.outpostBuilt && placed >= parts.size() - 1
                && level.getBlockState(ground(level, center.getX(), center.getZ())).getBlock() instanceof ChestBlock) {
            c.outpostPlaced = true;
            ColonyManager.markDirty(level);
            LOGGER.info("[war] the outpost of {} stands at {}", Campaigns.shortId(c), center.toShortString());
        }
    }

    /** The outpost's chest is gone: the defenders (or a player) destroyed it. */
    static boolean destroyed(ServerLevel level, Campaign c, BlockPos center) {
        if (!level.isLoaded(center)) return false;
        return !(level.getBlockState(ground(level, center.getX(), center.getZ())).getBlock() instanceof ChestBlock);
    }

    /** The outpost is lost: the campaign stalls until it is rebuilt, or breaks off after too many losses. */
    static void lost(ServerLevel level, Campaign c) {
        c.outpostLosses++;
        c.outpostWork = 0;
        c.outpostBuilt = false;
        c.outpostPlaced = false;
        LOGGER.info("[war] {} lost its outpost ({} times)", Campaigns.shortId(c), c.outpostLosses);
        if (c.defender != null) Diplomacy.relation(level, c.attacker, c.defender).side(c.attacker).spent += 4;
        if (c.outpostLosses >= Campaigns.cfg().outpost_max_losses) {
            Campaigns.retreat(level, c, "the outpost was lost " + c.outpostLosses + " times");
            return;
        }
        c.phase = Campaign.Phase.OUTPOST;
        c.phaseSince = level.getGameTime();
        ColonyManager.markDirty(level);
    }

    /** An hour of outpost work; abstractly the defenders may sally against it. */
    static void step(ServerLevel level, Campaign c, long now) {
        int soldiers = 0;
        for (Campaign.Soldier s : c.present(now)) if (!s.golem) soldiers++;
        c.outpostWork += 10 * soldiers;
        if (!c.live && c.kind == Campaign.Kind.WAR) {
            Colony target = ColonyManager.data(level).colony(c.target);
            if (target != null && Battle.sally(level, c, target, now)) {
                LOGGER.info("[war] {} sallies out of {} against the outpost of {}", Countries.plainName(level, target.country),
                        Countries.villageName(target), Campaigns.shortId(c));
                lost(level, c);
                return;
            }
        }
        if (c.outpostWork >= Campaigns.cfg().outpost_work && !c.outpostBuilt) {
            c.outpostBuilt = true;
            // Military construction is paid by the treasury of the staging village.
            Colony origin = Campaigns.origin(level, c);
            if (origin != null) {
                var treasury = ColonyStorage.treasury(level, origin);
                int logs = Chests.total(Chests.take(treasury, s -> s.is(ItemTags.LOGS), 8));
                int planks = Chests.total(Chests.take(treasury, s -> s.is(ItemTags.PLANKS), 16));
                if (c.defender != null) Diplomacy.relation(level, c.attacker, c.defender).side(c.attacker).spent += (logs + planks / 4 + 3) / 4;
            }
            LOGGER.info("[war] {} finished its outpost at {}", Campaigns.shortId(c), c.outpost.toShortString());
            ColonyManager.markDirty(level);
        }
    }

    // ---------------------------------------------------------------- border watch

    /**
     * Daily: a village facing a hostile or warring neighbour (or the base of a player at war with its country) keeps
     * a watch outpost in its own domain on that side; it is dropped in peace. Its blocks go up when the village is
     * live; it strengthens the defence and the night watch.
     */
    public static void borderWatch(ServerLevel level, Colony v) {
        if (v.country == null) return;
        BlockPos threat = null;
        double best = 800 * 800;
        for (Colony o : ColonyManager.data(level).colonies()) {
            if (o.country == null || o.country.equals(v.country)) continue;
            var state = Diplomacy.state(level, v.country, o.country);
            if (state != net.betterthanvillage.politics.Relation.State.WAR && state != net.betterthanvillage.politics.Relation.State.HOSTILITY) continue;
            double d = o.core().distSqr(v.core());
            if (d < best) {
                best = d;
                threat = o.core();
            }
        }
        for (net.betterthanvillage.law.PlayerWar w : ColonyManager.data(level).wars) {
            if (!w.country.equals(v.country)) continue;
            PlayerIntel in = ColonyManager.data(level).intel.get(w.player);
            if (in != null && in.base != null && in.base.distSqr(v.core()) < best) {
                best = in.base.distSqr(v.core());
                threat = in.base;
            }
        }
        if (threat == null) {
            if (v.watchPost != null) {
                v.watchPost = null;
                v.watchPostPlaced = false;
                ColonyManager.markDirty(level);
            }
            return;
        }
        if (v.watchPost == null) {
            double dx = threat.getX() - v.core().getX(), dz = threat.getZ() - v.core().getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            int d = Math.min((int) len / 2, v.radius() + 12);
            v.watchPost = new BlockPos((int) Math.round(v.core().getX() + dx / len * d), 0, (int) Math.round(v.core().getZ() + dz / len * d));
            v.watchPostPlaced = false;
            ColonyManager.markDirty(level);
            LOGGER.info("[war] {} keeps a border watch at {}", Countries.villageName(v), v.watchPost.toShortString());
        }
    }

    /** A live village puts up its watch outpost, a block a second. */
    public static void tickWatch(ServerLevel level, Colony v) {
        if (v.watchPost == null || v.watchPostPlaced || !level.isLoaded(v.watchPost) || !level.isPositionEntityTicking(v.watchPost)) return;
        if (!ColonyManager.terrainChangesAllowed(level)) return;
        List<Part> parts = planAround(level, v.watchPost, v.core());
        for (Part p : parts) {
            BlockState here = level.getBlockState(p.pos());
            if (here.getBlock() == p.state().getBlock() || !here.canBeReplaced()
                    || !net.betterthanvillage.colony.Protection.terrainModifiable(level, p.pos())) continue;
            SafeEditScope.run(() -> level.setBlock(p.pos(), p.state(), 3));
            return;
        }
        v.watchPostPlaced = true;
        ColonyManager.markDirty(level);
    }

    /** The ground under the outpost's blocks: the surface, below anything the plan itself placed. */
    static BlockPos ground(ServerLevel level, int x, int z) {
        BlockPos p = Campaigns.surface(level, x, z);
        for (int i = 0; i < 8; i++) {
            BlockState below = level.getBlockState(p.below());
            if (!(below.is(Blocks.OAK_FENCE) || below.is(Blocks.SPRUCE_LOG) || below.is(Blocks.TORCH) || below.is(Blocks.CHEST)
                    || below.is(Blocks.RED_BED) || below.canBeReplaced())) break;
            p = p.below();
        }
        return p;
    }
}
