package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.law.Law;
import net.betterthanvillage.law.Offence;
import net.betterthanvillage.law.PlayerWar;
import net.betterthanvillage.law.Wars;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Relation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * War against the player (docs_v2/07-warfare.md §War against the player): the player's strength score from vanilla
 * statistics and our counters, the base found from the player's home blocks and presence, and the offensive: a
 * country at war with a player it can match sends an army against the base, and friendly countries that think ill
 * of the player join it in a coalition.
 */
public final class PlayerBases {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks between presence samples. */
    private static final int SAMPLE = 2400;
    /** Presence cells considered for the base. */
    private static final int CELLS = 12;

    private PlayerBases() {
    }

    public static PlayerIntel intel(ServerLevel level, UUID player) {
        return ColonyManager.data(level).intel.computeIfAbsent(player, PlayerIntel::new);
    }

    // ---------------------------------------------------------------- sampling

    /** Every two minutes: where each player is (nights and sleep count more), the score and the base. */
    public static void tick(ServerLevel level, long now) {
        if (now % SAMPLE != 0) return;
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean night = t >= 13000 && t < 23000;
        for (ServerPlayer p : level.players()) {
            if (!Law.subject(p)) continue;
            PlayerIntel in = intel(level, p.getUUID());
            in.name = p.getGameProfile().getName();
            in.presence.merge(PlayerIntel.cell(p.blockPosition()), p.isSleeping() ? 6 : night ? 3 : 1, Integer::sum);
            in.lastSample = now;
            in.strength = strength(p, in);
            decay(in);
            in.base = findBase(level, p, in);
        }
        ColonyManager.markDirty(level);
    }

    /** The player logged out here: a strong sign of home. */
    public static void loggedOut(ServerLevel level, Player p) {
        if (!Law.subject(p)) return;
        PlayerIntel in = intel(level, p.getUUID());
        in.presence.merge(PlayerIntel.cell(p.blockPosition()), 8, Integer::sum);
        ColonyManager.markDirty(level);
    }

    /** A player built an iron golem (our counter for the strength score). */
    public static void golemBuilt(ServerLevel level, IronGolem golem) {
        if (!golem.isPlayerCreated()) return;
        Player p = level.getNearestPlayer(golem, 8);
        if (p == null || !Law.subject(p)) return;
        intel(level, p.getUUID()).golemsBuilt++;
        ColonyManager.markDirty(level);
    }

    private static void decay(PlayerIntel in) {
        int total = 0;
        for (int v : in.presence.values()) total += v;
        if (total < 2000 && in.presence.size() < 512) return;
        in.presence.replaceAll((k, v) -> v / 2);
        in.presence.values().removeIf(v -> v <= 0);
    }

    // ---------------------------------------------------------------- strength

    /**
     * The player's strength score: diamonds mined, mobs killed, villagers and golems killed, golems built and the
     * current gear (armor and weapon).
     */
    static int strength(ServerPlayer p, PlayerIntel in) {
        var stats = p.getStats();
        int diamonds = stats.getValue(Stats.BLOCK_MINED.get(Blocks.DIAMOND_ORE)) + stats.getValue(Stats.BLOCK_MINED.get(Blocks.DEEPSLATE_DIAMOND_ORE));
        int mobs = stats.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
        int villagers = stats.getValue(Stats.ENTITY_KILLED.get(EntityType.VILLAGER)) + stats.getValue(Stats.ENTITY_KILLED.get(EntityType.IRON_GOLEM));
        double attack = p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        return 2 + p.getArmorValue() + (int) Math.round(attack * 2) + diamonds / 4 + mobs / 50 + villagers * 2 + in.golemsBuilt * 4;
    }

    // ---------------------------------------------------------------- the base

    /**
     * The base: of the cells where the player spends most time (and the respawn point), the one with the most
     * home blocks the player placed (beds, the respawn bed most; chests, barrels, shulker boxes; furnaces, crafting
     * tables, doors) plus presence. Null when the player has no home blocks and no respawn point.
     */
    static BlockPos findBase(ServerLevel level, ServerPlayer p, PlayerIntel in) {
        List<Long> cells = new ArrayList<>(in.presence.keySet());
        cells.sort((a, b) -> Integer.compare(in.presence.get(b), in.presence.get(a)));
        if (cells.size() > CELLS) cells = new ArrayList<>(cells.subList(0, CELLS));
        BlockPos respawn = p.getRespawnDimension() == level.dimension() ? p.getRespawnPosition() : null;
        if (respawn != null && !cells.contains(PlayerIntel.cell(respawn))) cells.add(PlayerIntel.cell(respawn));
        ColonySavedData data = ColonyManager.data(level);
        BlockPos best = null;
        double bestScore = 0;
        for (long cell : cells) {
            int cx = PlayerIntel.cellX(cell) << 5, cz = PlayerIntel.cellZ(cell) << 5;
            double score = 2 * in.presence.getOrDefault(cell, 0);
            BlockPos anchor = null;
            double anchorWeight = 0;
            for (int chx = cx >> 4; chx <= (cx + 31) >> 4; chx++) {
                for (int chz = cz >> 4; chz <= (cz + 31) >> 4; chz++) {
                    LongSet set = data.playerBlocksInChunk(chx, chz);
                    if (set == null) continue;
                    for (long key : set) {
                        BlockPos pos = BlockPos.of(key);
                        if (!p.getUUID().equals(data.blockOwner(pos))) continue;
                        double w = level.isLoaded(pos) ? weight(level.getBlockState(pos), pos.equals(respawn)) : 0.5;
                        score += w;
                        if (w > anchorWeight) {
                            anchorWeight = w;
                            anchor = pos;
                        }
                    }
                }
            }
            if (respawn != null && PlayerIntel.cell(respawn) == cell) {
                score += 10;
                if (anchor == null) anchor = respawn;
            }
            if (anchor == null) continue;
            if (score > bestScore) {
                bestScore = score;
                best = anchor;
            }
        }
        if (best == null && respawn != null) best = respawn;
        return best;
    }

    private static double weight(BlockState s, boolean respawnBed) {
        if (s.is(BlockTags.BEDS)) return respawnBed ? 10 : 5;
        if (s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock || s.getBlock() instanceof ShulkerBoxBlock) return 2;
        if (s.is(Blocks.FURNACE) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER)) return 1;
        if (s.is(BlockTags.DOORS)) return 1;
        return 0.2;
    }

    // ---------------------------------------------------------------- the offensive

    /**
     * Daily: a country at war with a player sends an army against the player's base when its strength matches the
     * player's score; friendly countries whose villages think ill of the player (or allies) join the war.
     */
    public static void daily(ServerLevel level) {
        ColonySavedData data = ColonyManager.data(level);
        var cfg = ColonyConfig.get().warfare;
        for (PlayerWar w : new ArrayList<>(data.wars)) {
            if (w.weary || Countries.get(level, w.country) == null) continue;
            PlayerIntel in = data.intel.get(w.player);
            if (in == null || in.base == null) continue;
            int mine = Countries.strength(level, w.country);
            int score = Math.max(1, in.strength + 4 * w.losses);
            if (mine * cfg.player_offensive_ratio >= score) {
                Campaign c = Campaigns.launchAgainstPlayer(level, w.country, w.player, in.base);
                if (c != null) coalition(level, w, in, score);
            }
        }
    }

    /** Other countries join a war on a strong player: allies of the attacker, or friends whose people dislike the player. */
    private static void coalition(ServerLevel level, PlayerWar war, PlayerIntel in, int score) {
        ColonySavedData data = ColonyManager.data(level);
        for (UUID other : new ArrayList<>(data.countries.keySet())) {
            if (other.equals(war.country) || Wars.war(level, other, war.player) != null || Wars.truce(level, other, war.player) != null) continue;
            Relation.State state = Diplomacy.state(level, war.country, other);
            double rel = Diplomacy.relation(level, war.country, other).score;
            Colony capital = Countries.capital(level, other);
            if (capital == null) continue;
            int gossip = ColonyManager.isLive(level, capital) ? Law.gossip(level, capital, war.player) : 0;
            // Allies and close friends of the attacker, and countries whose people already think ill of the player.
            boolean joins = state == Relation.State.ALLIANCE || rel >= 50 || (rel >= 20 && gossip > 0)
                    || gossip >= ColonyConfig.get().law.warning_gossip;
            // A coalition forms only against a strong player: one the attacker could not easily face alone.
            if (!joins || score * 2 < Countries.strength(level, war.country)) {
                LOGGER.info("[war] {} stays out of the war on {} (relations {}, gossip {}, player {} vs strength {})", Countries.plainName(level, other),
                        war.playerName, Math.round(rel), gossip, score, Countries.strength(level, war.country));
                continue;
            }
            Wars.declare(level, capital, war.player, war.playerName, Offence.COALITION);
            LOGGER.info("[war] {} joins the war of {} on {}", Countries.plainName(level, other), Countries.plainName(level, war.country), war.playerName);
        }
    }

    public static List<String> status(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, PlayerIntel> e : ColonyManager.data(level).intel.entrySet()) {
            PlayerIntel in = e.getValue();
            out.add("player " + (in.name.isEmpty() ? e.getKey().toString().substring(0, 8) : in.name) + ": strength " + in.strength + ", base "
                    + (in.base == null ? "-" : in.base.toShortString()) + ", cells " + in.presence.size() + ", golems built " + in.golemsBuilt);
        }
        return out;
    }
}
