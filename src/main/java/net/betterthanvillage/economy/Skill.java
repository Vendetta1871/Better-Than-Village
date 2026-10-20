package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;

import java.util.UUID;

/**
 * Hereditary craft (docs_v2/02-professions.md §Inheritance): a villager who took up a trade its parents did not
 * practise is the first of its line. It works slower, knows only the basic workshop recipes, finds less ore and
 * its goods fetch less. Its children in the same trade are masters.
 */
public final class Skill {
    /** Work of a first-generation craftsman takes this much longer. */
    public static final double NOVICE_SLOWDOWN = 1.5;
    /** Goods of a first-generation craftsman sell for this share of the price. */
    public static final double NOVICE_QUALITY = 0.85;

    private Skill() {
    }

    /** True for a master of its trade (born into it, or a villager of the generated village). */
    public static boolean master(Colony colony, UUID villager) {
        if (colony == null || colony.tradeOf(villager) == ColonyRole.ELDER) return true;
        Household h = colony.households.get(villager);
        return h == null || h.lineage >= 1;
    }

    /** Time factor for a villager's work (1 for masters). */
    public static double workFactor(LivingEntity entity) {
        if (!(entity instanceof Villager v) || !(v.level() instanceof ServerLevel level)) return 1.0;
        return master(ColonyManager.colonyOf(level, v), v.getUUID()) ? 1.0 : NOVICE_SLOWDOWN;
    }

    /** Price factor of a household's goods. */
    public static double quality(Colony colony, UUID villager) {
        return master(colony, villager) ? 1.0 : NOVICE_QUALITY;
    }
}
