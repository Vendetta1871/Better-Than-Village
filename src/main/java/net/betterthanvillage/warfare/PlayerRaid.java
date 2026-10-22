package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.SafeEditScope;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The storm of a player's base (docs_v2/07-warfare.md §War against the player, Behaviour): the soldiers fight the
 * player if present, empty the base's chests into the army's loot and set the base on fire; the loot goes to the
 * staging village's treasury when the army is home.
 */
public final class PlayerRaid {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RADIUS = 12;
    /** Campaign -> containers already emptied. */
    private static final Map<UUID, Set<BlockPos>> EMPTIED = new HashMap<>();
    /** Campaigns that set their fire. */
    private static final Set<UUID> BURNT = new HashSet<>();

    private PlayerRaid() {
    }

    public static void clearCaches() {
        EMPTIED.clear();
        BURNT.clear();
    }

    /** Player deaths this many times end the raid: the army has made its point. */
    private static final int BEATEN = 3;

    /** A soldier of a raid killed its player. */
    public static void playerKilled(ServerLevel level, net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.LivingEntity killer) {
        Campaign c = killer == null ? null : Campaigns.ofSoldier(level, killer.getUUID());
        if (c == null || !player.getUUID().equals(c.player)) return;
        c.lossesDefender++;
        ColonyManager.markDirty(level);
        LOGGER.info("[war] {} killed the player {} ({} times)", Campaigns.shortId(c), player.getGameProfile().getName(), c.lossesDefender);
    }

    /** A third of the soldiers carry the base off instead of fighting, unless they are attacked. */
    static boolean looter(Campaign c, Villager v) {
        int i = c.soldiers.indexOf(c.soldier(v.getUUID()));
        boolean hurt = v.getLastHurtByMob() != null && v.tickCount - v.getLastHurtByMobTimestamp() <= 100;
        return i >= 0 && i % 3 == 2 && !hurt;
    }

    /** Once a second: the raid ends when the loot is full, nothing is left to take, or the player was beaten. */
    static void tickLive(ServerLevel level, Campaign c, long now) {
        if (c.base == null || !level.isLoaded(c.base)) return;
        boolean beaten = c.lossesDefender >= BEATEN;
        boolean full = true;
        for (int i = 0; i < c.loot.getContainerSize(); i++) {
            if (c.loot.getItem(i).isEmpty()) full = false;
        }
        boolean done = container(level, c) == null && (BURNT.contains(c.id) || !ColonyManager.terrainChangesAllowed(level));
        if (full || done || beaten) {
            EMPTIED.remove(c.id);
            BURNT.remove(c.id);
            Campaigns.retreat(level, c, "the raid on the base is done");
        }
    }

    static void soldierTick(ServerLevel level, Campaign c, Villager v, long now) {
        BlockPos chest = container(level, c);
        BlockPos goal = chest != null ? chest : c.base;
        if (v.distanceToSqr(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5) > 6) {
            Campaigns.go(level, v, goal, 0.85);
            return;
        }
        if ((now + v.getId()) % 40 != 0) return;
        if (chest != null && level.getBlockEntity(chest) instanceof Container box) {
            int taken = 0;
            for (int i = 0; i < box.getContainerSize() && taken < 3; i++) {
                ItemStack s = box.getItem(i);
                if (s.isEmpty()) continue;
                ItemStack rest = c.loot.addItem(s.copy());
                int moved = s.getCount() - rest.getCount();
                if (moved <= 0) break;
                s.shrink(moved);
                taken++;
            }
            box.setChanged();
            v.swing(InteractionHand.MAIN_HAND);
            if (taken == 0) EMPTIED.computeIfAbsent(c.id, k -> new HashSet<>()).add(chest);
            else LOGGER.info("[war] {} loots {} stacks from the chest at {}", Campaigns.shortId(c), taken, chest.toShortString());
            return;
        }
        if (!BURNT.contains(c.id) && ColonyManager.terrainChangesAllowed(level) && arson(level, c.base)) {
            BURNT.add(c.id);
            v.swing(InteractionHand.MAIN_HAND);
            LOGGER.info("[war] {} sets the base at {} on fire", Campaigns.shortId(c), c.base.toShortString());
        }
    }

    /** A container of the base not yet emptied (player-placed ones first), or null. */
    private static BlockPos container(ServerLevel level, Campaign c) {
        Set<BlockPos> done = EMPTIED.getOrDefault(c.id, Set.of());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.base.offset(-RADIUS, -4, -RADIUS), c.base.offset(RADIUS, 4, RADIUS))) {
            if (done.contains(p) || !(level.getBlockEntity(p) instanceof Container box) || box.isEmpty()) continue;
            double d = p.distSqr(c.base) - (ColonyManager.data(level).isPlayerBlock(p) ? 10000 : 0);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Sets fire on a flammable block of the base; true if a fire was lit. */
    static boolean arson(ServerLevel level, BlockPos around) {
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-8, -2, -8), around.offset(8, 4, 8))) {
            BlockState s = level.getBlockState(p);
            BlockPos above = p.above();
            if (!s.isFlammable(level, p, net.minecraft.core.Direction.UP) || !level.getBlockState(above).isAir()) continue;
            BlockState fire = BaseFireBlock.getState(level, above);
            if (!fire.canSurvive(level, above)) continue;
            SafeEditScope.run(() -> level.setBlock(above, fire, 11));
            return true;
        }
        return false;
    }

    static boolean lit(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.FIRE);
    }
}
