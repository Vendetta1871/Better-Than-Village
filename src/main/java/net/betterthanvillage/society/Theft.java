package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.defense.ThreatManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Theft from colony storages (06 §4.4): only noticed thefts count, and the first small one is a warning.
 * Net accounting per open container lives in {@link PlayerBlocks}.
 */
public final class Theft {
    /** A colony villager or golem sees the thief within this range (line of sight required). */
    static final double SEE_RANGE = 16;
    /** ... or hears it within this range regardless of walls. */
    static final double HEAR_RANGE = 7;
    /** Hearing range for a sneaking thief. */
    static final double SNEAK_HEAR_RANGE = 3;
    /** Net items taken at once that skip the warning. */
    static final int LARGE_THEFT = 8;
    /** A second noticed theft within this window mobilizes even when small. */
    static final long REPEAT_WINDOW = 6000;
    private static final String LAST_OFFENSE = "society.theftLastOffense";

    private Theft() {
    }

    /** Colony owning the storage at pos (managed or scanned, not player storage), or null. */
    static Colony storageOwner(ServerLevel level, BlockPos pos) {
        for (Colony colony : ColonyManager.data(level).colonies()) {
            if (colony.managedStorages.containsKey(pos) || colony.storages.contains(pos)) {
                return Protection.isProtectedContainer(level, pos) ? null : colony;
            }
        }
        return null;
    }

    static boolean valuable(ItemStack s) {
        return s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.GOLD_INGOT) || s.is(Items.NETHERITE_INGOT)
                || s.is(Items.NETHERITE_SCRAP) || s.isEnchanted() || s.getRarity() != Rarity.COMMON;
    }

    /** Some awake colony villager (guards included) or colony golem sees or hears the player. */
    static boolean noticed(ServerLevel level, Colony colony, Player player) {
        AABB box = player.getBoundingBox().inflate(SEE_RANGE);
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, LivingEntity::isAlive)) {
            if (!v.isSleeping() && ColonyManager.colonyOf(level, v) == colony && perceives(v, player)) return true;
        }
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, box, LivingEntity::isAlive)) {
            if (ThreatManager.memberOf(level, colony, golem) && perceives(golem, player)) return true;
        }
        return false;
    }

    private static boolean perceives(LivingEntity watcher, Player player) {
        double d2 = watcher.distanceToSqr(player);
        double hear = player.isShiftKeyDown() ? SNEAK_HEAR_RANGE : HEAR_RANGE;
        return d2 <= hear * hear || (d2 <= SEE_RANGE * SEE_RANGE && watcher.hasLineOfSight(player));
    }

    /** The player took a net amount of colony items; unnoticed thefts go unpunished. */
    static void taken(ServerLevel level, Colony colony, Player player, int items, boolean valuable) {
        if (colony == null || items <= 0 || player.isCreative() || player.isSpectator() || !noticed(level, colony, player)) return;
        long now = level.getGameTime();
        Map<UUID, Long> lastOffense = colony.runtime(LAST_OFFENSE, HashMap::new);
        Long last = lastOffense.put(player.getUUID(), now);
        boolean repeat = last != null && now - last >= 0 && now - last <= REPEAT_WINDOW;
        if (!repeat && items < LARGE_THEFT && !valuable) {
            Msg.chat(player, Msg.message("theft.warning"));
            return;
        }
        ThreatManager.reportTheft(level, colony, player, items);
    }
}
