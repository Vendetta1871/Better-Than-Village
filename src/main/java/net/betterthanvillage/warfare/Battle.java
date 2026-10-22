package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.world.Caravans;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Abstract battles (docs_v2/07-warfare.md §Abstract battles): the strength of a side is the sum of its warriors'
 * power (class, gear tier, health), the defenders' multiplied for every intact wall ring, plus golems. Every in-game
 * hour of a siege is a round of random attrition proportional to the enemy's strength; the attackers first wear the
 * outermost intact ring down and breach it. A side breaks when its warriors are gone (conquest) or, for the attackers,
 * when they have lost most of their power.
 */
public final class Battle {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Share of a side's power dealt as damage per round. */
    private static final double INTENSITY = 0.3;
    /** The attackers break off below this share of their initial power. */
    private static final double MORALE = 0.3;

    private Battle() {
    }

    private static ColonyConfig.Warfare cfg() {
        return ColonyConfig.get().warfare;
    }

    // ---------------------------------------------------------------- power

    /** Gear tier of a live warrior: the weapon (0..3) plus the armor (0..1). */
    public static double gearOf(Villager v) {
        double weapon = 0;
        for (ItemStack s : new ItemStack[]{v.getMainHandItem(), v.getOffhandItem()}) {
            weapon = Math.max(weapon, weaponTier(s));
        }
        for (int i = 0; i < v.getInventory().getContainerSize(); i++) weapon = Math.max(weapon, weaponTier(v.getInventory().getItem(i)));
        double armor = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (v.getItemBySlot(slot).getItem() instanceof ArmorItem a) armor += a.getDefense();
        }
        if (v.getOffhandItem().is(Items.SHIELD)) armor += 2;
        return Math.min(4, weapon + armor / 20.0);
    }

    private static double weaponTier(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.is(Items.NETHERITE_SWORD) || s.is(Items.DIAMOND_SWORD)) return 3;
        if (s.is(Items.IRON_SWORD)) return 2;
        if (s.is(Items.BOW) || s.is(Items.CROSSBOW)) return 1.5;
        if (s.is(Items.STONE_SWORD)) return 1;
        if (s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)) return 0.5;
        return 0;
    }

    /** Power of one soldier in a siege (infantry storms best, cavalry least). */
    static double soldierPower(Campaign.Soldier s) {
        if (s.golem) return cfg().golem_power * s.hp;
        double bonus = switch (s.cls) {
            case INFANTRY -> 0.5;
            case RANGED -> 0.25;
            case CAVALRY -> 0;
        };
        return (cfg().warrior_power + s.gear + bonus) * s.hp;
    }

    /** Power of one soldier in the field (a sally against the outpost): cavalry is strongest. */
    static double fieldPower(Campaign.Soldier s) {
        if (s.golem) return cfg().golem_power * s.hp;
        double bonus = s.cls == WarriorClass.CAVALRY ? 1.0 : 0;
        return (cfg().warrior_power + s.gear + bonus) * s.hp;
    }

    public static double armyPower(Campaign c, long now) {
        double p = 0;
        for (Campaign.Soldier s : c.present(now)) p += soldierPower(s);
        return p;
    }

    /** A defending warrior: archers on the walls count more. */
    static double defenderPower(Colony colony, UUID id, double hp) {
        double bonus = colony.warriorClasses.getOrDefault(id, WarriorClass.INFANTRY) == WarriorClass.RANGED ? 0.5 : 0;
        return (cfg().warrior_power + colony.warriorGear + bonus) * hp;
    }

    /** The village's warriors at home (not on campaign or with a caravan), never the fallen. */
    public static List<UUID> defenders(ServerLevel level, Colony colony) {
        List<UUID> out = new ArrayList<>();
        var fallen = ColonyManager.data(level).fallen;
        for (UUID id : colony.roles.keySet()) {
            if (colony.roleOf(id) != ColonyRole.WARRIOR || fallen.contains(id)) continue;
            if (Campaigns.away(level, id) || Caravans.away(level, id)) continue;
            out.add(id);
        }
        return out;
    }

    /** Intact rings of the village still to breach in this campaign. */
    static int intactRings(Colony colony, Campaign c) {
        return Math.max(0, Fortifications.builtRings(colony).size() - c.ringsBroken);
    }

    /** Strength of the defenders behind their walls (debug and the ruler's estimates). */
    public static double defencePower(ServerLevel level, Colony colony, Campaign c) {
        double d = 0;
        for (UUID id : defenders(level, colony)) d += defenderPower(colony, id, c == null ? 1 : c.defenderHp.getOrDefault(id, 1.0));
        if (golemStands(level, colony)) d += cfg().golem_power * (c == null ? 1 : c.defenderHp.getOrDefault(colony.golem, 1.0));
        int rings = c == null ? Fortifications.builtRings(colony).size() : intactRings(colony, c);
        return d * (1 + cfg().ring_bonus * rings) * watchBonus(colony);
    }

    /** A border watch outpost adds a tenth to the defence. */
    static double watchBonus(Colony colony) {
        return colony.watchPost != null ? 1.1 : 1.0;
    }

    static boolean golemStands(ServerLevel level, Colony colony) {
        return colony.golem != null && !ColonyManager.data(level).fallen.contains(colony.golem) && !Campaigns.away(level, colony.golem);
    }

    // ---------------------------------------------------------------- a round

    /** One hour of an abstract siege. */
    static void round(ServerLevel level, Campaign c, Colony target, long now) {
        Random rnd = new Random(c.id.getMostSignificantBits() ^ now);
        List<Campaign.Soldier> army = c.present(now);
        double attack = 0;
        for (Campaign.Soldier s : army) attack += soldierPower(s);
        if (attack <= 0) {
            Campaigns.retreat(level, c, "no army left");
            return;
        }
        List<UUID> defenders = defenders(level, target);
        boolean golem = golemStands(level, target);
        int rings = intactRings(target, c);
        double wall = 1 + cfg().ring_bonus * rings;
        double defence = 0;
        for (UUID id : defenders) defence += defenderPower(target, id, c.defenderHp.getOrDefault(id, 1.0));
        if (golem) defence += cfg().golem_power * c.defenderHp.getOrDefault(target.golem, 1.0);
        // Healers tend the wounded, with a potion from the treasury when there is one.
        heal(level, c, target, defenders);
        // The walls first: the outermost intact ring is worn down, then breached.
        if (rings > 0) {
            c.ring -= 0.12 * (0.5 + rnd.nextDouble()) * attack / (attack + defence);
            if (c.ring <= 0) {
                Siege.breach(level, target, c);
                c.ringsBroken++;
                c.ring = 1;
                wall = 1 + cfg().ring_bonus * intactRings(target, c);
            }
        }
        double toDefenders = INTENSITY * attack * (0.6 + 0.8 * rnd.nextDouble()) / wall;
        double toAttackers = INTENSITY * defence * wall * watchBonus(target) * (0.6 + 0.8 * rnd.nextDouble());
        int killedDefenders = 0, killedAttackers = 0;
        // Damage to the defenders, spread over them in hits of one point.
        List<UUID> units = new ArrayList<>(defenders);
        if (golem) units.add(target.golem);
        for (double left = toDefenders; left > 0 && !units.isEmpty(); left -= 1) {
            UUID id = units.get(rnd.nextInt(units.size()));
            boolean isGolem = id.equals(target.golem);
            double durability = isGolem ? cfg().golem_power * 2 : 1.5 * (cfg().warrior_power + target.warriorGear);
            double hp = c.defenderHp.getOrDefault(id, 1.0) - Math.min(1, left) / durability;
            if (hp <= 0) {
                units.remove(id);
                Casualties.killDefender(level, target, id, isGolem);
                Campaigns.defenderLost(level, c, id);
                killedDefenders++;
            } else {
                c.defenderHp.put(id, hp);
            }
        }
        List<Campaign.Soldier> soldiers = new ArrayList<>(army);
        for (double left = toAttackers; left > 0 && !soldiers.isEmpty(); left -= 1) {
            Campaign.Soldier s = soldiers.get(rnd.nextInt(soldiers.size()));
            double durability = s.golem ? cfg().golem_power * 2 : 1.5 * (cfg().warrior_power + s.gear);
            s.hp -= Math.min(1, left) / durability;
            if (s.hp <= 0) {
                soldiers.remove(s);
                Casualties.killSoldier(level, c, s);
                killedAttackers++;
            }
        }
        ColonyManager.markDirty(level);
        LOGGER.info("[war] siege of {} by {}: attack {} vs defence {} (rings {}, ring {}%), killed {} defenders and {} attackers",
                net.betterthanvillage.politics.Countries.villageName(target), Campaigns.shortId(c), round1(attack), round1(defence * wall),
                intactRings(target, c), Math.round(c.ring * 100), killedDefenders, killedAttackers);
        if (defenders(level, target).isEmpty() && !golemStands(level, target)) {
            Conquest.conquer(level, c, target);
        } else if (armyPower(c, now) < MORALE * c.initialPower) {
            Campaigns.retreat(level, c, "losses too heavy");
        }
    }

    /**
     * A sally of the defenders against the outpost, fought in the field without walls. Returns true if the
     * defenders won and the outpost is lost.
     */
    static boolean sally(ServerLevel level, Campaign c, Colony target, long now) {
        Random rnd = new Random(c.id.getLeastSignificantBits() ^ now);
        List<UUID> defenders = defenders(level, target);
        double defence = 0;
        for (UUID id : defenders) defence += defenderPower(target, id, c.defenderHp.getOrDefault(id, 1.0));
        double attack = 0;
        for (Campaign.Soldier s : c.present(now)) attack += fieldPower(s);
        if (defenders.isEmpty() || defence < 0.5 * attack) return false;
        if (rnd.nextDouble() > 0.35 * defence / (attack + defence)) return false;
        // A short fight: a few hits each way.
        List<Campaign.Soldier> soldiers = new ArrayList<>(c.present(now));
        for (int i = 0; i < Math.max(1, (int) (defence * 0.2)) && !soldiers.isEmpty(); i++) {
            Campaign.Soldier s = soldiers.get(rnd.nextInt(soldiers.size()));
            s.hp -= 1 / (1.5 * (cfg().warrior_power + s.gear));
            if (s.hp <= 0) {
                soldiers.remove(s);
                Casualties.killSoldier(level, c, s);
            }
        }
        List<UUID> units = new ArrayList<>(defenders);
        for (int i = 0; i < Math.max(1, (int) (attack * 0.15)) && !units.isEmpty(); i++) {
            UUID id = units.get(rnd.nextInt(units.size()));
            double hp = c.defenderHp.getOrDefault(id, 1.0) - 1 / (1.5 * (cfg().warrior_power + target.warriorGear));
            if (hp <= 0) {
                units.remove(id);
                Casualties.killDefender(level, target, id, false);
                Campaigns.defenderLost(level, c, id);
            } else {
                c.defenderHp.put(id, hp);
            }
        }
        return true;
    }

    private static void heal(ServerLevel level, Campaign c, Colony target, List<UUID> defenders) {
        int healers = 0;
        for (UUID id : target.roles.keySet()) if (target.roleOf(id) == ColonyRole.HEALER) healers++;
        if (healers == 0) return;
        var treasury = ColonyStorage.treasury(level, target);
        for (int i = 0; i < healers; i++) {
            UUID worst = null;
            double low = 1;
            for (UUID id : defenders) {
                double hp = c.defenderHp.getOrDefault(id, 1.0);
                if (hp < low) {
                    low = hp;
                    worst = id;
                }
            }
            if (worst == null) return;
            boolean potion = !Chests.take(treasury, s -> s.is(Items.POTION) || s.is(Items.SPLASH_POTION), 1).isEmpty();
            c.defenderHp.put(worst, Math.min(1, low + (potion ? 0.3 : 0.1)));
        }
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
