package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;

/**
 * A night agent near a player (docs_v2/07-warfare.md §Night operations): it sneaks to the treasury chest (or the
 * base's chest), to a house to set on fire, or to a sleeper to kill, does the deed and goes home. An agent that is
 * hurt gives up and runs.
 */
public final class NightOpTask extends ColonyTask {
    private static final Logger LOGGER = LogUtils.getLogger();
    private Campaign campaign;
    private BlockPos goal;
    private LivingEntity victim;
    private long nextAt;

    public NightOpTask() {
        super(2400);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (!Campaigns.isSoldier(level, v)) return false;
        Campaign c = Campaigns.ofSoldier(level, v.getUUID());
        if (c.kind != Campaign.Kind.NIGHT || c.phase != Campaign.Phase.SIEGE || c.opDone) return false;
        campaign = c;
        goal = null;
        victim = null;
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        return campaign != null && !campaign.opDone && campaign.phase == Campaign.Phase.SIEGE && Campaigns.isSoldier(level, v)
                && VillagerUtil.currentActivity(v) == ModRegistries.COLONY_TRAVEL.get();
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        LOGGER.info("[night] the agent of {} sneaks in for a {} at {}", Countries.plainName(level, campaign.attacker), campaign.op.key(),
                v.blockPosition().toShortString());
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (v.getLastHurtByMob() != null && v.tickCount - v.getLastHurtByMobTimestamp() <= 20) {
            done(level, "the agent is attacked and runs");
            return;
        }
        if (now < nextAt) return;
        Colony target = campaign.target == null ? null : ColonyManager.data(level).colony(campaign.target);
        switch (campaign.op) {
            case THEFT -> theft(level, v, target, now);
            case ARSON -> arson(level, v, target);
            case KILL -> kill(level, v, target, now);
        }
    }

    private void theft(ServerLevel level, Villager v, Colony target, long now) {
        if (goal == null) {
            if (target != null) {
                List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, target);
                double best = Double.MAX_VALUE;
                for (ColonyStorage.Slot s : treasury) {
                    double d = s.pos().distSqr(v.blockPosition());
                    if (d < best) {
                        best = d;
                        goal = s.pos();
                    }
                }
            } else if (campaign.base != null) {
                for (BlockPos p : BlockPos.betweenClosed(campaign.base.offset(-12, -4, -12), campaign.base.offset(12, 4, 12))) {
                    BlockEntity be = level.getBlockEntity(p);
                    if (be instanceof Container box && !box.isEmpty()) {
                        goal = p.immutable();
                        break;
                    }
                }
            }
            if (goal == null) {
                done(level, "nothing to steal");
                return;
            }
        }
        if (!near(level, v, goal)) return;
        int n;
        if (target != null) {
            n = NightOps.steal(ColonyStorage.treasury(level, target), campaign);
        } else {
            n = level.getBlockEntity(goal) instanceof Container box
                    ? NightOps.steal(List.of(new ColonyStorage.Slot(goal, box)), campaign) : 0;
        }
        v.swing(InteractionHand.MAIN_HAND);
        LOGGER.info("[night] the agent of {} steals {} items at {}", Countries.plainName(level, campaign.attacker), n, goal.toShortString());
        done(level, "theft done");
    }

    private void arson(ServerLevel level, Villager v, Colony target) {
        if (goal == null) {
            if (target != null) {
                for (Household h : target.households.values()) {
                    if (h.bed != null && !h.owner.equals(target.elder)) {
                        goal = h.bed;
                        break;
                    }
                }
                if (goal == null && target.elder != null && target.households.get(target.elder) != null) goal = target.households.get(target.elder).bed;
            } else {
                goal = campaign.base;
            }
            if (goal == null) {
                done(level, "no house to burn");
                return;
            }
        }
        if (!near(level, v, goal)) return;
        boolean lit = ColonyManager.terrainChangesAllowed(level) && PlayerRaid.arson(level, goal);
        v.swing(InteractionHand.MAIN_HAND);
        LOGGER.info("[night] the agent of {} {} at {}", Countries.plainName(level, campaign.attacker), lit ? "sets a fire" : "finds nothing to burn",
                goal.toShortString());
        done(level, "arson done");
    }

    private void kill(ServerLevel level, Villager v, Colony target, long now) {
        if (victim == null || !victim.isAlive() || !victim.isSleeping()) {
            victim = null;
            double best = 64 * 64;
            if (target != null) {
                for (Villager s : ColonyManager.members(level, target)) {
                    if (!s.isSleeping()) continue;
                    boolean warrior = target.roleOf(s.getUUID()) == ColonyRole.WARRIOR;
                    double d = s.distanceToSqr(v) - (warrior ? 100000 : s.getUUID().equals(target.elder) ? 50000 : 0);
                    if (d < best) {
                        best = d;
                        victim = s;
                    }
                }
            } else if (campaign.player != null && level.getPlayerByUUID(campaign.player) instanceof Player p && p.isSleeping()
                    && p.distanceToSqr(v) <= best) {
                victim = p;
            }
            if (victim == null) {
                done(level, "nobody asleep");
                return;
            }
        }
        if (!near(level, v, victim.blockPosition())) return;
        // A blow at a sleeper: it is not defending itself.
        victim.hurt(level.damageSources().mobAttack(v), 40);
        v.swing(InteractionHand.MAIN_HAND);
        nextAt = now + 10;
        if (!victim.isAlive()) {
            LOGGER.info("[night] the agent of {} kills a sleeper at {}", Countries.plainName(level, campaign.attacker), victim.blockPosition().toShortString());
            done(level, "killing done");
        }
    }

    private boolean near(ServerLevel level, Villager v, BlockPos pos) {
        if (v.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 6.25) return true;
        Campaigns.go(level, v, pos, 0.6);
        return false;
    }

    private void done(ServerLevel level, String why) {
        campaign.opDone = true;
        Campaigns.retreat(level, campaign, "night operation: " + why);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        campaign = null;
        goal = null;
        victim = null;
    }
}
