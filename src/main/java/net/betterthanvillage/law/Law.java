package net.betterthanvillage.law;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.debug.TestBot;
import net.betterthanvillage.defense.RaidShelter;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.food.RancherTask;
import net.betterthanvillage.registry.ModTags;
import net.betterthanvillage.society.Deeds;
import net.betterthanvillage.society.LandNotices;
import net.betterthanvillage.society.Prices;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The village's law (docs_v2/05-law.md): offences in a territory, witnesses, warnings and escalation to war.
 * <ul>
 *   <li><b>Continuous offences</b> (weapon in hand, building or breaking blocks): the first sighting warns the player,
 *   who then has the grace period to stop; a sighting after it, with no pause in between, means war.</li>
 *   <li><b>One-shot offences</b> (killing animals, attacking villagers or the golem, theft): a warning; a second one
 *   while the warning is active means war.</li>
 *   <li><b>Active warning</b> is vanilla gossip: warnings add negative gossip to the witnesses and the elder, and a
 *   warning stays active while the village's minor and major negative gossip about the player reaches
 *   {@code law.warning_gossip}. Gossip decays over days, so the warning expires by itself.</li>
 * </ul>
 * Offences need a witness; at war there are no warnings (the war goes on).
 */
public final class Law {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** A continuous offence counts as stopped after this many ticks without a sighting. */
    static final long EPISODE_GAP = 400;
    /** Same-kind offences right after a warning belong to it (several hits, a double click). */
    static final long SPAM_WINDOW = 60;
    /** Gossip each witness (and the elder) gets per warning, the vanilla amount per event. */
    static final int GOSSIP_PER_WARNING = 25;
    /** Net items taken at once that skip the theft warning. */
    public static final int LARGE_THEFT = 8;

    private record Key(UUID colony, UUID player, Offence offence) {
    }

    private static final class Episode {
        final long start;
        long lastSeen;

        Episode(long now) {
            start = lastSeen = now;
        }
    }

    private static final Map<Key, Episode> EPISODES = new HashMap<>();
    private static final Map<Key, Long> LAST_WARNING = new HashMap<>();

    private Law() {
    }

    public static void clearCaches() {
        EPISODES.clear();
        LAST_WARNING.clear();
    }

    /** Players the law applies to: survival players and test bots (not other mods' fake players). */
    public static boolean subject(Player p) {
        return p != null && p.isAlive() && !p.isCreative() && !p.isSpectator() && (!p.isFakePlayer() || p instanceof TestBot);
    }

    static long grace() {
        return ColonyConfig.get().law.grace_seconds * 20L;
    }

    /** The colony fights raiders or monsters: players then carry weapons and build defences freely. */
    private static boolean defending(ServerLevel level, Colony colony, long now) {
        return colony.recentThreat(now, 200) || RaidShelter.isActive(level, colony);
    }

    // ---------------------------------------------------------------- tick

    /** Once a second: weapons in hand, surrender gestures, war bookkeeping. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 20 != 0) return;
        Wars.tick(level, now);
        for (Player p : new ArrayList<>(level.players())) {
            if (!subject(p)) continue;
            Colony colony = ColonyManager.colonyAt(level, p.blockPosition());
            if (colony == null) continue;
            PlayerWar war = Wars.war(level, colony.country, p.getUUID());
            if (war != null) {
                Surrender.tick(level, colony, p, war, now);
                continue;
            }
            if (holdsWeapon(p) && !defending(level, colony, now)) {
                List<Villager> witnesses = Witnesses.villagers(level, colony, p);
                if (!witnesses.isEmpty() || Witnesses.golemSees(level, colony, p, Witnesses.SEE_RANGE)) {
                    continuous(level, colony, p, Offence.WEAPON, witnesses, p.blockPosition());
                }
            }
        }
        EPISODES.values().removeIf(e -> now - e.lastSeen > EPISODE_GAP * 2 || now < e.lastSeen);
        LAST_WARNING.values().removeIf(t -> now - t > SPAM_WINDOW * 20 || now < t);
    }

    static boolean holdsWeapon(Player p) {
        return p.getMainHandItem().is(ModTags.WEAPONS) || p.getOffhandItem().is(ModTags.WEAPONS);
    }

    // ---------------------------------------------------------------- offences

    /** A player placed (placed = true) or broke a block. */
    public static void blockChanged(ServerLevel level, Player player, BlockPos pos, boolean placed) {
        if (!subject(player) || Deeds.ownsPosition(level, player.getUUID(), pos)) return;
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null || Wars.war(level, colony.country, player.getUUID()) != null) return;
        long now = level.getGameTime();
        if (defending(level, colony, now)) return;
        List<Villager> witnesses = Witnesses.villagers(level, colony, player);
        if (witnesses.isEmpty() && !Witnesses.golemSees(level, colony, player, Witnesses.SEE_RANGE)) return;
        continuous(level, colony, player, Offence.BUILDING, witnesses, pos);
    }

    /** A placed block that counts as building: structural blocks, not utility ones (a torch, a crafting table). */
    public static boolean buildingBlock(BlockState state) {
        return LandNotices.structural(state) && !LandNotices.UTILITY.contains(state.getBlock());
    }

    /** A broken block that counts: anything that takes time to break, except the player's own blocks. */
    public static boolean breakCounts(ServerLevel level, Player player, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getDestroySpeed(level, pos) <= 0 || LandNotices.UTILITY.contains(state.getBlock())) return false;
        return !player.getUUID().equals(ColonyManager.data(level).blockOwner(pos));
    }

    /** A player hurt a villager or golem of the colony. */
    public static void attacked(ServerLevel level, Colony colony, Player player, LivingEntity victim) {
        if (!subject(player) || (victim instanceof IronGolem g && g.isPlayerCreated())) return;
        PlayerWar war = Wars.war(level, colony.country, player.getUUID());
        if (war != null) {
            Surrender.hostileAct(level, colony, player, war);
            ThreatManager.engage(level, colony, player);
            return;
        }
        List<Villager> witnesses = new ArrayList<>(Witnesses.villagers(level, colony, player));
        if (victim instanceof Villager v && !witnesses.contains(v)) witnesses.add(0, v);
        oneShot(level, colony, player, Offence.ATTACK, witnesses, false);
    }

    /** A player killed an animal. */
    public static void animalKilled(ServerLevel level, Player player, Animal animal) {
        if (!subject(player) || !animal.getType().is(ModTags.PROTECTED_ANIMALS)) return;
        BlockPos pos = animal.blockPosition();
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null || Deeds.ownsPosition(level, player.getUUID(), pos) || RancherTask.playerKept(level, animal)) return;
        List<Villager> witnesses = Witnesses.villagers(level, colony, player);
        if (witnesses.isEmpty() && !Witnesses.golemSees(level, colony, player, Witnesses.SEE_RANGE)) return;
        oneShot(level, colony, player, Offence.ANIMAL, witnesses, false);
    }

    /** A player took a net amount of the colony's goods (a storage, the treasury or a home chest). */
    public static void theft(ServerLevel level, Colony colony, Player player, int items, boolean valuable) {
        if (colony == null || items <= 0 || !subject(player)) return;
        List<Villager> witnesses = Witnesses.villagers(level, colony, player);
        if (witnesses.isEmpty() && !Witnesses.golemSees(level, colony, player, Witnesses.SEE_RANGE)) return;
        colony.stolenItems += items;
        if (Wars.war(level, colony.country, player.getUUID()) != null) {
            ThreatManager.engage(level, colony, player);
            return;
        }
        oneShot(level, colony, player, Offence.THEFT, witnesses, items >= LARGE_THEFT || valuable);
    }

    // ---------------------------------------------------------------- escalation

    private static void continuous(ServerLevel level, Colony colony, Player player, Offence o, List<Villager> witnesses, BlockPos where) {
        long now = level.getGameTime();
        Key key = new Key(colony.id(), player.getUUID(), o);
        Episode e = EPISODES.get(key);
        if (e == null || now - e.lastSeen > EPISODE_GAP || now < e.lastSeen) {
            EPISODES.put(key, new Episode(now));
            warn(level, colony, player, o, witnesses, where);
            return;
        }
        e.lastSeen = now;
        if (now - e.start >= grace()) {
            EPISODES.remove(key);
            Wars.declare(level, colony, player, o);
        }
    }

    /** Returns true when the offence led to war. */
    private static boolean oneShot(ServerLevel level, Colony colony, Player player, Offence o, List<Villager> witnesses, boolean grave) {
        long now = level.getGameTime();
        Key key = new Key(colony.id(), player.getUUID(), o);
        Long last = LAST_WARNING.get(key);
        if (last != null && now - last >= 0 && now - last < SPAM_WINDOW) return false;
        if (grave || warningActive(level, colony, player.getUUID())) {
            Wars.declare(level, colony, player, o);
            return true;
        }
        warn(level, colony, player, o, witnesses, player.blockPosition());
        return false;
    }

    private static void warn(ServerLevel level, Colony colony, Player player, Offence o, List<Villager> witnesses, BlockPos where) {
        long now = level.getGameTime();
        GossipType type = o == Offence.ATTACK ? GossipType.MAJOR_NEGATIVE : GossipType.MINOR_NEGATIVE;
        Set<Villager> told = new LinkedHashSet<>(witnesses);
        Villager elder = elder(level, colony);
        // The witnesses tell the elder: the whole village knows.
        if (elder != null) told.add(elder);
        for (Villager v : told) v.getGossips().add(player.getUUID(), type, GOSSIP_PER_WARNING);
        LAST_WARNING.put(new Key(colony.id(), player.getUUID(), o), now);
        long seconds = grace() / 20;
        Component text = switch (o) {
            case WEAPON -> message("warning.weapon", seconds);
            case BUILDING -> message("warning.building", seconds, Prices.landPrice(colony));
            default -> message("warning." + o.key());
        };
        say(player, speaker(level, colony, player, witnesses), text);
        if (o == Offence.BUILDING) LandNotices.warned(level, colony, player.getUUID(), where);
        LOGGER.info("[law] {} warned {} for {} ({} witnesses)", village(colony).getString(), player.getGameProfile().getName(), o.key(), witnesses.size());
    }

    /** The village's negative gossip about the player keeps a warning active. */
    public static boolean warningActive(ServerLevel level, Colony colony, UUID player) {
        return gossip(level, colony, player) >= ColonyConfig.get().law.warning_gossip;
    }

    /** Minor plus major negative gossip about the player, summed over the loaded members. */
    public static int gossip(ServerLevel level, Colony colony, UUID player) {
        int total = 0;
        for (Villager v : ColonyManager.members(level, colony)) {
            var entry = v.getGossips().getGossipEntries().get(player);
            if (entry != null) total += entry.getInt(GossipType.MINOR_NEGATIVE) + entry.getInt(GossipType.MAJOR_NEGATIVE);
        }
        return total;
    }

    /** Forgets running offences of the player (war declared or peace signed). */
    static void forget(UUID player) {
        EPISODES.keySet().removeIf(k -> k.player().equals(player));
        LAST_WARNING.keySet().removeIf(k -> k.player().equals(player));
    }

    // ---------------------------------------------------------------- speech

    static Villager elder(ServerLevel level, Colony colony) {
        return colony.elder != null && level.getEntity(colony.elder) instanceof Villager v && v.isAlive() ? v : null;
    }

    /** Who speaks to the player: the elder when near, else the nearest witness, else nobody in particular. */
    static Villager speaker(ServerLevel level, Colony colony, Player player, List<Villager> witnesses) {
        Villager elder = elder(level, colony);
        if (elder != null && elder.distanceToSqr(player) <= 16 * 16) return elder;
        return witnesses.isEmpty() ? elder : witnesses.get(0);
    }

    static void say(Player player, Villager speaker, Component text) {
        if (player == null) return;
        Component who = speaker == null ? Component.translatable("message.better_than_village.law.someone")
                : Component.translatable("entity.better_than_village.role." + roleOf(speaker).serializedName());
        player.displayClientMessage(Component.translatable("message.better_than_village.law.says", who, text), false);
    }

    private static ColonyRole roleOf(Villager v) {
        return v.level() instanceof ServerLevel level ? ColonyManager.roleOf(level, v) : ColonyRole.CITIZEN;
    }

    static Component message(String key, Object... args) {
        return Component.translatable("message.better_than_village.law." + key, args);
    }

    /** "the village at x, z". */
    public static Component village(Colony colony) {
        return message("village", colony.core().getX(), colony.core().getZ());
    }
}
