package net.betterthanvillage.law;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.registry.ModRegistries;
import net.betterthanvillage.registry.ModTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/**
 * Surrender (docs_v2/05-law.md §Surrender): a player at war who shows a white flag (paper, a white banner or carpet in
 * the main hand) to the country gets a ceasefire while they hold it, and the elder comes to offer the terms. Attacking
 * under the white flag is perfidy: surrender is refused for {@code law.perfidy_minutes}.
 */
public final class Surrender {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The ceasefire holds this long after the flag was last seen (the check runs once a second). */
    static final long CEASEFIRE_HOLD = 60;
    /** The elder comes to a surrendering player this far away. */
    private static final double ELDER_RANGE = 48;

    private Surrender() {
    }

    public static boolean showsWhite(Player p) {
        return p.getMainHandItem().is(ModTags.SURRENDER_FLAGS);
    }

    public static boolean ceasefire(PlayerWar war, long now) {
        return now < war.ceasefireUntil;
    }

    /** Once a second for a player at war standing in a territory of the warring country. */
    static void tick(ServerLevel level, Colony colony, Player player, PlayerWar war, long now) {
        if (!showsWhite(player) || now < war.perfidyUntil) return;
        if (!Witnesses.noticed(level, colony, player, Witnesses.WAR_SEE_RANGE)) return;
        boolean fresh = !ceasefire(war, now);
        war.ceasefireUntil = now + CEASEFIRE_HOLD;
        if (!fresh) return;
        ThreatManager.calm(level, colony, player.getUUID());
        Law.say(player, Law.speaker(level, colony, player, Witnesses.villagers(level, colony, player)), Treaties.terms(level, colony, war, player));
        LOGGER.info("[law] {} surrenders to {}", war.playerName, Law.village(colony).getString());
    }

    /** The player attacked the country: under a white flag or a ceasefire that is perfidy. */
    static void hostileAct(ServerLevel level, Colony colony, Player player, PlayerWar war) {
        long now = level.getGameTime();
        if (!showsWhite(player) && !ceasefire(war, now)) return;
        int minutes = ColonyConfig.get().law.perfidy_minutes;
        war.ceasefireUntil = 0;
        war.perfidyUntil = now + minutes * 1200L;
        ColonyManager.markDirty(level);
        Law.say(player, Law.speaker(level, colony, player, Witnesses.villagers(level, colony, player)),
                Law.message("perfidy", Law.village(colony), minutes));
        LOGGER.info("[law] perfidy by {} at {}", war.playerName, Law.village(colony).getString());
    }

    /** The elder walks up to a surrendering player of a country at war (to offer the treaty); true while it does. */
    public static boolean elderWalk(ServerLevel level, Villager v, Colony colony) {
        if (colony.roleOf(v.getUUID()) != ColonyRole.ELDER || v.isSleeping() || v.getTradingPlayer() != null) return false;
        if (ColonyManager.data(level).wars.isEmpty()) return false;
        long now = level.getGameTime();
        Player target = null;
        double best = ELDER_RANGE * ELDER_RANGE;
        for (Player p : level.players()) {
            PlayerWar w = Wars.war(level, colony.country, p.getUUID());
            if (w == null || !ceasefire(w, now) || !ThreatManager.inColony(colony, p)) continue;
            double d = v.distanceToSqr(p);
            if (d < best) {
                best = d;
                target = p;
            }
        }
        if (target == null) return false;
        VillagerUtil.doExclusively(v, ModRegistries.COLONY_WORK.get());
        if (best > 9) {
            Nav.approach(level, v, target.blockPosition(), 0.8);
        } else {
            Nav.reset(v);
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            v.getLookControl().setLookAt(target);
        }
        return true;
    }
}
