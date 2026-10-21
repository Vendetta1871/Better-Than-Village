package net.betterthanvillage.law;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.defense.ThreatManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Who notices an offence (docs_v2/05-law.md §Offences): an awake villager of the colony, or its golem, that sees the
 * player (line of sight) or hears them close by. Unwitnessed offences have no effect.
 */
public final class Witnesses {
    /** Seeing range (line of sight required). */
    public static final double SEE_RANGE = 16;
    /** Warriors and golems spot an enemy of their country this far. */
    public static final double WAR_SEE_RANGE = 24;
    /** Hearing range regardless of walls. */
    static final double HEAR_RANGE = 7;
    /** Hearing range for a sneaking player. */
    static final double SNEAK_HEAR_RANGE = 3;

    private Witnesses() {
    }

    /** Awake villagers of the colony that perceive the player, nearest first. */
    public static List<Villager> villagers(ServerLevel level, Colony colony, Player player, double see) {
        List<Villager> out = new ArrayList<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(see), LivingEntity::isAlive)) {
            if (!v.isSleeping() && ColonyManager.colonyOf(level, v) == colony && perceives(v, player, see)) out.add(v);
        }
        out.sort(Comparator.comparingDouble(v -> v.distanceToSqr(player)));
        return out;
    }

    public static List<Villager> villagers(ServerLevel level, Colony colony, Player player) {
        return villagers(level, colony, player, SEE_RANGE);
    }

    /** Some villager or golem of the colony perceives the player. */
    public static boolean noticed(ServerLevel level, Colony colony, Player player, double see) {
        return !villagers(level, colony, player, see).isEmpty() || golemSees(level, colony, player, see);
    }

    public static boolean noticed(ServerLevel level, Colony colony, Player player) {
        return noticed(level, colony, player, SEE_RANGE);
    }

    static boolean golemSees(ServerLevel level, Colony colony, Player player, double see) {
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, player.getBoundingBox().inflate(see), LivingEntity::isAlive)) {
            if (ThreatManager.memberOf(level, colony, golem) && perceives(golem, player, see)) return true;
        }
        return false;
    }

    static boolean perceives(LivingEntity watcher, Entity target, double see) {
        double d2 = watcher.distanceToSqr(target);
        double hear = target.isShiftKeyDown() ? SNEAK_HEAR_RANGE : HEAR_RANGE;
        return d2 <= hear * hear || (d2 <= see * see && watcher.hasLineOfSight(target));
    }
}
