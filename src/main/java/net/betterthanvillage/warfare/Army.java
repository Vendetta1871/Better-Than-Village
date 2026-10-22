package net.betterthanvillage.warfare;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.world.Caravans;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A village's warriors (docs_v2/07-warfare.md §Warriors): their classes, mixed by the situation as far as the
 * treasury's gear and the herder's horses allow; their two shifts; the stables; cavalry mounting in the village.
 */
public final class Army {
    /** Persistent-data key of a village horse: the colony id. */
    public static final String HORSE_TAG = "better_than_village_horse";

    private Army() {
    }

    private static ColonyConfig.Warfare cfg() {
        return ColonyConfig.get().warfare;
    }

    /** Warriors at home (not on campaign, not with a caravan), in a stable order. */
    static List<UUID> warriors(ServerLevel level, Colony colony) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : colony.roles.keySet()) {
            if (colony.roleOf(id) == ColonyRole.WARRIOR && !Campaigns.away(level, id) && !Caravans.away(level, id)) out.add(id);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ---------------------------------------------------------------- census and daily

    /** Census of a live village: gear seen on the warriors, classes and shifts, horses in the stables. */
    public static void census(ServerLevel level, Colony colony, List<Villager> adults) {
        double gear = 0;
        int n = 0;
        for (Villager v : adults) {
            if (colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR) continue;
            gear += Battle.gearOf(v);
            n++;
        }
        if (n > 0) colony.warriorGear = gear / n;
        assign(level, colony);
        stables(level, colony);
    }

    /** Daily step of every village, live or abstract: the herder breeds horses for the cavalry, roles are mixed. */
    public static void daily(ServerLevel level, Colony colony, long day) {
        int herders = 0;
        for (UUID id : colony.roles.keySet()) if (colony.tradeOf(id) == ColonyRole.HERDER) herders++;
        int wanted = cfg().cavalry ? Math.max(1, warriors(level, colony).size() / 3) : 0;
        if (herders > 0 && colony.horses < wanted && Math.floorMod(day, 2L) == 0) {
            var treasury = ColonyStorage.treasury(level, colony);
            if (Chests.count(treasury, s -> s.is(Items.WHEAT) || s.is(Items.HAY_BLOCK)) >= 10) {
                Chests.take(treasury, s -> s.is(Items.WHEAT), 10);
                colony.horses++;
                ColonyManager.markDirty(level);
            }
        }
        if (!ColonyManager.isLive(level, colony)) assign(level, colony);
        Outposts.borderWatch(level, colony);
    }

    /**
     * Classes and shifts. The target mix follows the situation: behind walls under attack more archers, on campaign
     * more infantry, otherwise an even mix; a class needs its gear in the treasury (or on the warrior already), and
     * cavalry a horse. Warriors keep their class while it fits the mix. In war the night watch is reinforced.
     */
    public static void assign(ServerLevel level, Colony colony) {
        List<UUID> warriors = warriors(level, colony);
        colony.warriorClasses.keySet().removeIf(id -> colony.roleOf(id) != ColonyRole.WARRIOR);
        colony.nightWatch.removeIf(id -> colony.roleOf(id) != ColonyRole.WARRIOR);
        if (warriors.isEmpty()) return;
        boolean war = colony.country != null && !Diplomacy.enemies(level, colony.country).isEmpty();
        boolean besieged = Campaigns.against(level, colony.id()) != null;
        boolean attacking = colony.country != null && !Campaigns.of(level, colony.country).isEmpty();
        double ranged, cavalry;
        if (besieged || war && !attacking) {
            ranged = 0.5;
            cavalry = 0;
        } else if (attacking) {
            ranged = 0.25;
            cavalry = 0.25;
        } else {
            ranged = 0.34;
            cavalry = 0.33;
        }
        int n = warriors.size();
        var treasury = ColonyStorage.treasury(level, colony);
        int bows = Chests.count(treasury, s -> s.is(Items.BOW) || s.is(Items.CROSSBOW))
                + Chests.count(treasury, s -> s.is(Items.STRING)) / 3;
        int haveRanged = 0;
        for (UUID id : warriors) if (colony.warriorClasses.get(id) == WarriorClass.RANGED) haveRanged++;
        int wantRanged = Math.min((int) Math.round(n * ranged), haveRanged + bows);
        int wantCavalry = cfg().cavalry ? Math.min((int) Math.round(n * cavalry), colony.horses) : 0;
        Map<WarriorClass, Integer> want = new EnumMap<>(WarriorClass.class);
        want.put(WarriorClass.RANGED, wantRanged);
        want.put(WarriorClass.CAVALRY, wantCavalry);
        want.put(WarriorClass.INFANTRY, Math.max(0, n - wantRanged - wantCavalry));
        Map<WarriorClass, Integer> taken = new EnumMap<>(WarriorClass.class);
        List<UUID> open = new ArrayList<>();
        for (UUID id : warriors) {
            WarriorClass c = colony.warriorClasses.get(id);
            if (c != null && taken.getOrDefault(c, 0) < want.get(c)) taken.merge(c, 1, Integer::sum);
            else open.add(id);
        }
        for (UUID id : open) {
            for (WarriorClass c : new WarriorClass[]{WarriorClass.RANGED, WarriorClass.CAVALRY, WarriorClass.INFANTRY}) {
                if (taken.getOrDefault(c, 0) < want.get(c) || c == WarriorClass.INFANTRY) {
                    colony.warriorClasses.put(id, c);
                    taken.merge(c, 1, Integer::sum);
                    break;
                }
            }
        }
        // Shifts: the night watch is the first share of the warriors by a hash of the day pair, stable over time.
        double share = war ? Math.max(cfg().night_watch_share, 2 / 3.0) : cfg().night_watch_share;
        int night = n <= 1 ? 0 : Math.min(n - 1, (int) Math.round(n * share));
        List<UUID> order = new ArrayList<>(warriors);
        order.sort(Comparator.comparingInt(id -> (int) (id.getLeastSignificantBits() ^ colony.id().getMostSignificantBits())));
        colony.nightWatch.clear();
        colony.nightWatch.addAll(order.subList(0, night));
        ColonyManager.markDirty(level);
    }

    public static WarriorClass classOf(Colony colony, UUID id) {
        return colony.warriorClasses.getOrDefault(id, WarriorClass.INFANTRY);
    }

    // ---------------------------------------------------------------- stables and cavalry

    /** Horses of the stables stand at the pen of a live village: missing ones are brought out, the dead are gone. */
    static void stables(ServerLevel level, Colony colony) {
        if (!cfg().cavalry || colony.livestockPen == null || !level.isLoaded(colony.livestockPen)) return;
        List<AbstractHorse> horses = horses(level, colony);
        if (horses.size() >= colony.horses) return;
        BlockPos pen = colony.livestockPen;
        Horse horse = EntityType.HORSE.create(level, null, pen, MobSpawnType.BREEDING, false, false);
        if (horse == null) return;
        horse.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0, 0);
        horse.setTamed(true);
        horse.equipSaddle(new ItemStack(Items.SADDLE), null);
        horse.setPersistenceRequired();
        horse.getPersistentData().putString(HORSE_TAG, colony.id().toString());
        level.addFreshEntity(horse);
    }

    static List<AbstractHorse> horses(ServerLevel level, Colony colony) {
        String id = colony.id().toString();
        return level.getEntitiesOfClass(AbstractHorse.class, new AABB(colony.core()).inflate(colony.radius() + 16, 32, colony.radius() + 16),
                h -> h.isAlive() && id.equals(h.getPersistentData().getString(HORSE_TAG)));
    }

    /**
     * A cavalry warrior mounts a free village horse when it has to fight in its village and dismounts after;
     * mounted pathfinding stays inside the village (campaigns fight on foot).
     */
    public static void cavalry(ServerLevel level, Colony colony, Villager v, boolean fighting) {
        boolean mounted = v.getVehicle() instanceof AbstractHorse;
        boolean wants = fighting && cfg().cavalry && classOf(colony, v.getUUID()) == WarriorClass.CAVALRY && !v.isSleeping();
        if (mounted && !wants) {
            v.stopRiding();
            return;
        }
        if (mounted || !wants) return;
        AbstractHorse best = null;
        double d = 12 * 12;
        for (AbstractHorse h : horses(level, colony)) {
            if (h.isVehicle()) continue;
            double x = h.distanceToSqr(v);
            if (x < d) {
                d = x;
                best = h;
            }
        }
        if (best == null) return;
        if (d <= 6) v.startRiding(best, true);
        else if (net.betterthanvillage.colony.PathBudget.tryAcquire(level)) v.getNavigation().moveTo(best, 0.8);
    }

    /** A village horse or golem died. */
    public static void onDeath(ServerLevel level, LivingEntity dead) {
        if (dead instanceof IronGolem g) {
            for (Colony c : ColonyManager.data(level).colonies()) {
                if (g.getUUID().equals(c.golem)) {
                    c.golem = null;
                    ColonyManager.markDirty(level);
                }
            }
        }
    }

    /** A village horse died: the stables lose it. */
    public static void horseDied(ServerLevel level, AbstractHorse horse) {
        String id = horse.getPersistentData().getString(HORSE_TAG);
        if (id.isEmpty()) return;
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (c.id().toString().equals(id) && c.horses > 0) {
                c.horses--;
                ColonyManager.markDirty(level);
            }
        }
    }
}
