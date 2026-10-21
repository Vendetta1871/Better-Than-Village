package net.betterthanvillage.law;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.economy.Credit;
import net.betterthanvillage.economy.Debt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * War of a country on a player, defensive version (docs_v2/05-law.md §War against the player): every warrior and golem
 * of the country attacks the player on sight in its territories, villagers refuse to trade and civilians take shelter.
 * It ends with a peace treaty bought from an elder ({@link Treaties}) or, for a debt war, when the debt is paid.
 */
public final class Wars {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Colony id -> {tick, 1 if a hostile player is inside}. */
    private static final Map<UUID, long[]> ENEMY_INSIDE = new HashMap<>();

    private Wars() {
    }

    public static void clearCaches() {
        ENEMY_INSIDE.clear();
    }

    public static PlayerWar war(ServerLevel level, UUID country, UUID player) {
        if (country == null || player == null) return null;
        for (PlayerWar w : ColonyManager.data(level).wars) {
            if (w.country.equals(country) && w.player.equals(player)) return w;
        }
        return null;
    }

    /** The colony's warriors attack this player on sight: at war with its country and no ceasefire. */
    public static boolean hostile(ServerLevel level, Colony colony, Player player) {
        PlayerWar w = war(level, colony.country, player.getUUID());
        return w != null && !Surrender.ceasefire(w, level.getGameTime()) && Law.subject(player);
    }

    /** Villages of a country. */
    public static List<Colony> colonies(ServerLevel level, UUID country) {
        List<Colony> out = new ArrayList<>();
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (country.equals(c.country)) out.add(c);
        }
        return out;
    }

    // ---------------------------------------------------------------- declaration and peace

    public static PlayerWar declare(ServerLevel level, Colony colony, Player player, Offence cause) {
        return declare(level, colony, player.getUUID(), player.getGameProfile().getName(), cause);
    }

    /** The colony's elder declares war in the name of its country (the player may be offline: a debt). */
    public static PlayerWar declare(ServerLevel level, Colony colony, UUID player, String name, Offence cause) {
        PlayerWar w = war(level, colony.country, player);
        if (w != null) return w;
        long now = level.getGameTime();
        w = new PlayerWar();
        w.country = colony.country;
        w.player = player;
        w.playerName = name == null ? "" : name;
        w.colony = colony.id();
        w.cause = cause;
        w.declared = now;
        ColonySavedData data = ColonyManager.data(level);
        data.wars.add(w);
        data.setDirty();
        Law.forget(player);
        LOGGER.info("[law] {} (country {}) declares war on {} for {}", Law.village(colony).getString(), shortId(colony.country), w.playerName, cause.key());
        Player online = level.getPlayerByUUID(player);
        if (online != null) {
            Law.say(online, Law.elder(level, colony), Law.message("war", Law.village(colony), Law.message("reason." + cause.key())));
            if (ThreatManager.inColony(colony, online)) ThreatManager.engage(level, colony, online);
        }
        return w;
    }

    /**
     * Ends the war. A treaty (price != null: emeralds the player paid, negative when the country paid) starts the
     * truce; the country forgives: its villagers drop their negative gossip about the player.
     */
    public static void peace(ServerLevel level, PlayerWar war, Integer price) {
        ColonySavedData data = ColonyManager.data(level);
        if (!data.wars.remove(war)) return;
        long now = level.getGameTime();
        if (price != null) {
            data.treaties.removeIf(t -> t.country.equals(war.country) && t.player.equals(war.player));
            PlayerWar.Treaty t = new PlayerWar.Treaty();
            t.country = war.country;
            t.player = war.player;
            t.signed = now;
            t.truceUntil = now + ColonyConfig.get().law.truce_days * 24000L;
            t.price = price;
            data.treaties.add(t);
        }
        for (Colony c : colonies(level, war.country)) {
            for (Villager v : ColonyManager.members(level, c)) {
                v.getGossips().remove(war.player, GossipType.MINOR_NEGATIVE);
                v.getGossips().remove(war.player, GossipType.MAJOR_NEGATIVE);
            }
            ThreatManager.calm(level, c, war.player);
        }
        Law.forget(war.player);
        data.setDirty();
        LOGGER.info("[law] peace between country {} and {} (price {})", shortId(war.country), war.playerName, price);
    }

    /** The active truce of a country with the player, or null. */
    public static PlayerWar.Treaty truce(ServerLevel level, UUID country, UUID player) {
        long now = level.getGameTime();
        for (PlayerWar.Treaty t : ColonyManager.data(level).treaties) {
            if (t.country.equals(country) && t.player.equals(player) && now < t.truceUntil) return t;
        }
        return null;
    }

    /** A paid-off debt ends a war fought over debt once the player owes the country nothing overdue. */
    public static void debtPaid(ServerLevel level, Debt paid) {
        if (paid.debtor.kind() != Debt.Kind.PLAYER) return;
        Colony creditor = ColonyManager.data(level).colony(paid.creditor.colony());
        if (creditor == null) return;
        PlayerWar w = war(level, creditor.country, paid.debtor.id());
        if (w == null || w.cause != Offence.DEBT) return;
        long today = Credit.day(level);
        for (Debt d : ColonyManager.data(level).debts.values()) {
            if (d == paid || !d.debtor.equals(paid.debtor) || !d.overdue(today)) continue;
            Colony c = ColonyManager.data(level).colony(d.creditor.colony());
            if (c != null && w.country.equals(c.country)) return;
        }
        peace(level, w, null);
        Player online = level.getPlayerByUUID(w.player);
        if (online != null) Law.say(online, Law.elder(level, creditor), Law.message("peace.debt", Law.village(creditor)));
    }

    // ---------------------------------------------------------------- tick

    static void tick(ServerLevel level, long now) {
        if (now % 100 != 0) return;
        ColonySavedData data = ColonyManager.data(level);
        var cfg = ColonyConfig.get().law;
        data.treaties.removeIf(t -> now >= t.truceUntil || now < t.signed);
        for (PlayerWar w : new ArrayList<>(data.wars)) {
            List<Colony> colonies = colonies(level, w.country);
            if (colonies.isEmpty()) {
                // The country is gone.
                data.wars.remove(w);
                data.setDirty();
                continue;
            }
            if (w.weary) continue;
            long days = (now - w.declared) / 24000L;
            if (w.losses >= cfg.weariness_losses || (days >= cfg.weariness_days && w.losses > 0)) {
                w.weary = true;
                data.setDirty();
                Colony colony = data.colony(w.colony);
                if (colony == null) colony = colonies.get(0);
                LOGGER.info("[law] country {} is weary of the war on {} (losses {})", shortId(w.country), w.playerName, w.losses);
                Player online = level.getPlayerByUUID(w.player);
                if (online != null) {
                    Law.say(online, Law.elder(level, colony), Law.message("weary", Law.village(colony), Treaties.reparation(level, colony, w)));
                }
            }
        }
    }

    /** Drops the cached enemy check of the colony (a ceasefire or peace just began). */
    public static void refresh(Colony colony) {
        ENEMY_INSIDE.remove(colony.id());
    }

    /** A hostile player stands inside the colony (civilians take shelter); cached for a second. */
    public static boolean enemyInside(ServerLevel level, Colony colony) {
        if (ColonyManager.data(level).wars.isEmpty()) return false;
        long now = level.getGameTime();
        long[] cached = ENEMY_INSIDE.get(colony.id());
        if (cached != null && now >= cached[0] && now - cached[0] < 20) return cached[1] != 0;
        boolean inside = false;
        for (Player p : level.players()) {
            if (ThreatManager.inColony(colony, p) && hostile(level, colony, p)) {
                inside = true;
                break;
            }
        }
        ENEMY_INSIDE.put(colony.id(), new long[]{now, inside ? 1 : 0});
        return inside;
    }

    // ---------------------------------------------------------------- losses

    /** A villager or golem died: a loss of its country if the player at war killed it. */
    public static void memberKilled(ServerLevel level, LivingEntity victim, Player killer) {
        Colony colony = victim instanceof Villager v ? ColonyManager.colonyOf(level, v) : ColonyManager.colonyAt(level, victim.blockPosition());
        if (colony == null || (victim instanceof IronGolem g && g.isPlayerCreated())) return;
        PlayerWar w = war(level, colony.country, killer.getUUID());
        if (w == null) return;
        w.losses++;
        ColonyManager.markDirty(level);
    }

    /** A warrior or golem of a country at war with the player killed them. */
    public static void playerKilled(ServerLevel level, Player player, LivingEntity killer) {
        Colony colony = killer instanceof Villager v ? ColonyManager.colonyOf(level, v) : ColonyManager.colonyAt(level, killer.blockPosition());
        if (colony == null) return;
        PlayerWar w = war(level, colony.country, player.getUUID());
        if (w == null) return;
        w.playerDeaths++;
        w.lastPlayerDeath = level.getGameTime();
        ColonyManager.markDirty(level);
        LOGGER.info("[law] {} killed {} (deaths {}, losses {})", Law.village(colony).getString(), w.playerName, w.playerDeaths, w.losses);
    }

    // ---------------------------------------------------------------- strength

    /** Military strength of a country (the same measure its ruler uses against other countries). */
    public static int countryStrength(ServerLevel level, UUID country) {
        return net.betterthanvillage.politics.Countries.strength(level, country);
    }

    /** Strength of the player in this war: armour, weapon and the losses already inflicted. */
    public static int playerStrength(Player player, PlayerWar war) {
        double attack = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        return 2 + player.getArmorValue() + (int) Math.round(attack * 2) + 4 * war.losses;
    }

    /** Debug line: the country's wars and truces. */
    public static String lawStatus(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        java.util.StringJoiner out = new java.util.StringJoiner("; ");
        ColonySavedData data = ColonyManager.data(level);
        for (PlayerWar w : data.wars) {
            if (!w.country.equals(colony.country)) continue;
            out.add("war on " + w.playerName + " (" + w.cause.key() + ", losses " + w.losses + ", deaths " + w.playerDeaths
                    + (w.weary ? ", weary" : "") + (Surrender.ceasefire(w, now) ? ", ceasefire" : "") + (now < w.perfidyUntil ? ", perfidy" : "") + ")");
        }
        for (PlayerWar.Treaty t : data.treaties) {
            if (t.country.equals(colony.country)) out.add("truce with " + shortId(t.player) + " for " + Math.max(0, t.truceUntil - now) / 20 + "s");
        }
        return out.length() == 0 ? "peace" : out.toString();
    }

    static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
