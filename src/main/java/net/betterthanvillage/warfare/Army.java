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
import net.minecraft.nbt.CompoundTag;
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
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
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
        // Professional warriors first: a conscript rides or shoots only when no warrior by trade is left for it.
        List<UUID> ranked = new ArrayList<>(warriors);
        ranked.sort(Comparator.comparingInt(id -> professional(colony, id) ? 0 : 1));
        boolean conscriptsWait = false;
        for (UUID id : ranked) {
            WarriorClass c = colony.warriorClasses.get(id);
            boolean keeps = c != null && taken.getOrDefault(c, 0) < want.get(c)
                    && (c == WarriorClass.INFANTRY || professional(colony, id) || !conscriptsWait);
            if (keeps) taken.merge(c, 1, Integer::sum);
            else open.add(id);
            if (!keeps && professional(colony, id)) conscriptsWait = true;
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
        // Under a siege everybody stands to by day: the army storms when it is ready, not at night.
        double share = besieged ? 0 : war ? Math.max(cfg().night_watch_share, 2 / 3.0) : cfg().night_watch_share;
        int night = n <= 1 ? 0 : Math.min(n - 1, (int) Math.round(n * share));
        List<UUID> order = new ArrayList<>(warriors);
        order.sort(Comparator.comparingInt(id -> (int) (id.getLeastSignificantBits() ^ colony.id().getMostSignificantBits())));
        colony.nightWatch.clear();
        colony.nightWatch.addAll(order.subList(0, night));
        ColonyManager.markDirty(level);
    }

    /**
     * The marching warriors take the campaign mix: a quarter of them cavalry while the stables have horses (riders
     * first), the rest keep their class. At home in war the mix is defensive, so it is set here when the army forms.
     */
    static void campaignClasses(Colony colony, List<UUID> marching) {
        if (!cfg().cavalry || marching.isEmpty()) return;
        int want = Math.min((int) Math.round(marching.size() * 0.25), colony.horses);
        List<UUID> order = new ArrayList<>(marching);
        order.sort(Comparator.<UUID>comparingInt(id -> professional(colony, id) ? 0 : 1)
                .thenComparingInt(id -> colony.warriorClasses.get(id) == WarriorClass.CAVALRY ? 0
                        : colony.warriorClasses.get(id) == WarriorClass.INFANTRY || !colony.warriorClasses.containsKey(id) ? 1 : 2));
        for (int i = 0; i < order.size(); i++) {
            UUID id = order.get(i);
            if (i < want) colony.warriorClasses.put(id, WarriorClass.CAVALRY);
            else if (colony.warriorClasses.get(id) == WarriorClass.CAVALRY) colony.warriorClasses.put(id, WarriorClass.INFANTRY);
        }
    }

    /** A warrior by trade, not a conscript. */
    static boolean professional(Colony colony, UUID id) {
        return colony.tradeOf(id) == ColonyRole.WARRIOR;
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
     * A cavalry warrior mounts a free village horse when it has to fight in its village and dismounts after. On
     * campaign the soldier keeps the horse its stables lent it (see lendHorse).
     */
    public static void cavalry(ServerLevel level, Colony colony, Villager v, boolean fighting) {
        if (Campaigns.isSoldier(level, v)) return;
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

    // ---------------------------------------------------------------- cavalry on campaign

    /** Persistent-data key of a horse on campaign: the rider's id. */
    public static final String CAMPAIGN_HORSE_TAG = "better_than_village_campaign_horse";

    /**
     * The stables lend a horse to a cavalry soldier: the stables count one less while it is away. The horse the
     * soldier rides now (a fight in the village) goes with it; otherwise a horse at the pen is led away.
     */
    static boolean lendHorse(ServerLevel level, Colony colony, Villager rider, Campaign.Soldier s) {
        if (!cfg().cavalry || colony.horses <= 0) return false;
        colony.horses--;
        if (rider != null && rider.getVehicle() instanceof AbstractHorse ridden) {
            ridden.getPersistentData().remove(HORSE_TAG);
            ridden.getPersistentData().putString(CAMPAIGN_HORSE_TAG, s.id.toString());
        } else {
            if (ColonyManager.isLive(level, colony)) {
                for (AbstractHorse h : horses(level, colony)) {
                    if (h.isVehicle()) continue;
                    CompoundTag data = net.betterthanvillage.world.Materializer.stash(h);
                    if (data != null) s.horse = data;
                    break;
                }
            }
            // A rider in the world gets on now; one out of it when it appears with the army.
            if (rider != null) mount(level, rider, s, rider.blockPosition());
        }
        ColonyManager.markDirty(level);
        return true;
    }

    /** A cavalry soldier appearing in the world gets on its horse: the one it left with, or a fresh one of the stables. */
    static void mount(ServerLevel level, Villager rider, Campaign.Soldier s, BlockPos pos) {
        if (rider.getVehicle() instanceof AbstractHorse) return;
        AbstractHorse horse = ownHorse(level, rider, 32);
        if (horse == null && s.horse != null && net.betterthanvillage.world.Materializer.spawn(level, s.horse, pos) instanceof AbstractHorse h) horse = h;
        if (horse == null) {
            Horse fresh = EntityType.HORSE.create(level, null, pos, MobSpawnType.BREEDING, false, false);
            if (fresh == null) return;
            fresh.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
            fresh.setTamed(true);
            fresh.equipSaddle(new ItemStack(Items.SADDLE), null);
            CompoundTag tag = new CompoundTag();
            if (!fresh.saveAsPassenger(tag)) return;
            if (!(net.betterthanvillage.world.Materializer.spawn(level, tag, pos) instanceof AbstractHorse spawned)) return;
            horse = spawned;
        }
        s.horse = null;
        horse.setPersistenceRequired();
        // The horse plans the rider's way (Campaigns.go): as far as a villager sees.
        var range = horse.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        if (range != null && range.getBaseValue() < 48) range.setBaseValue(48);
        horse.getPersistentData().remove(HORSE_TAG);
        horse.getPersistentData().putString(CAMPAIGN_HORSE_TAG, s.id.toString());
        boolean rides = rider.startRiding(horse, true);
        if (!rides) LOGGER.info("[war] soldier {} cannot mount its horse", rider.getUUID().toString().substring(0, 8));
    }

    /** The soldier is home: its horse goes back to the stables (the pen brings out a horse for it). */
    static void returnHorse(ServerLevel level, Colony home, net.minecraft.world.entity.Entity rider, Campaign.Soldier s) {
        if (!s.mounted) return;
        if (rider != null && rider.getVehicle() instanceof AbstractHorse horse) net.betterthanvillage.world.Materializer.discard(horse);
        s.mounted = false;
        s.horse = null;
        if (home != null) {
            home.horses++;
            ColonyManager.markDirty(level);
        }
    }

    /**
     * A villager rides by the horse's legs: the horse follows the rider's path (the rider only steers). Called after
     * every navigation order of a mounted soldier.
     */
    public static void steer(ServerLevel level, Villager v) {
        if (!(v.getVehicle() instanceof AbstractHorse)) remount(level, v);
        if (!(v.getVehicle() instanceof AbstractHorse horse)) return;
        // A horse on its own way (travel) is not turned back to the rider's stale path.
        if (net.betterthanvillage.ai.nav.WaypointNavigator.active(horse, level.getGameTime())) return;
        net.minecraft.world.level.pathfinder.Path path = v.getNavigation().getPath();
        BlockPos goal = path != null && !path.isDone() ? path.getTarget() : v.getNavigation().getTargetPos();
        if (goal == null) return;
        BlockPos current = horse.getNavigation().getTargetPos();
        if (current != null && current.distSqr(goal) <= 4 && !horse.getNavigation().isDone()) return;
        if (net.betterthanvillage.colony.PathBudget.tryAcquire(level)) {
            horse.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.3);
        }
    }

    /**
     * A soldier walks to the point; a rider's horse finds the way itself (it is wider than the villager, and the
     * villager's own path does not move it).
     */
    static void travel(ServerLevel level, Villager v, BlockPos to, double speed) {
        if (v.getVehicle() instanceof AbstractHorse horse) {
            net.betterthanvillage.ai.nav.WaypointNavigator.travel(level, horse, to, speed * 1.5);
        } else {
            net.betterthanvillage.ai.nav.WaypointNavigator.travel(level, v, to, speed);
        }
    }

    /**
     * Moves a stuck soldier over an obstacle; a rider moves with its horse. Never past a village wall or onto it
     * (false then): an army gets in through a breach it made.
     */
    static boolean hop(net.minecraft.world.entity.Entity e, double x, double y, double z) {
        if (!(e.level() instanceof ServerLevel level) || overWall(level, e, x, y, z)) return false;
        if (e.getVehicle() instanceof AbstractHorse horse) {
            horse.teleportTo(x, y, z);
            return true;
        }
        // A rider thrown off (deep water) takes its horse along, or it would be left behind for good.
        AbstractHorse own = ownHorse(level, e, REMOUNT_RANGE);
        e.teleportTo(x, y, z);
        if (own != null) own.teleportTo(x, y, z);
        return true;
    }

    /** The point is on a village wall, or a built ring stands between it and the entity. */
    static boolean overWall(ServerLevel level, net.minecraft.world.entity.Entity e, double x, double y, double z) {
        BlockPos to = BlockPos.containing(x, y, z);
        for (Colony c : ColonyManager.data(level).colonies()) {
            if (c.fortifications.managed.isEmpty()) continue;
            double r = c.radius() + 24;
            if (c.core().distSqr(to) > r * r && c.core().distSqr(e.blockPosition()) > r * r) continue;
            for (int dy = -2; dy <= 1; dy++) {
                if (c.fortifications.managed.containsKey(to.above(dy))) return true;
            }
            if (net.betterthanvillage.construction.Fortifications.acrossWall(c, e.getX(), e.getZ(), x, z)) return true;
        }
        return false;
    }

    /**
     * A soldier on campaign or its horse stuck in a wall is moved, with its rider or its horse, to the nearest open
     * ground around it. Returns false for anyone else (or nowhere to go).
     */
    public static boolean freeFromWall(ServerLevel level, LivingEntity e) {
        boolean horse = e instanceof AbstractHorse h && !h.getPersistentData().getString(CAMPAIGN_HORSE_TAG).isEmpty();
        boolean soldier = e instanceof Villager && Campaigns.away(level, e.getUUID());
        if (!horse && !soldier) return false;
        net.minecraft.world.entity.Entity mover = e.getVehicle() != null ? e.getVehicle() : e;
        BlockPos at = mover.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos p = Campaigns.surface(level, at.getX() + dx, at.getZ() + dz);
                if (!level.getFluidState(p.below()).isEmpty()) continue;
                boolean open = true;
                for (int y = 0; y <= 2 && open; y++) open = level.getBlockState(p.above(y)).getCollisionShape(level, p.above(y)).isEmpty();
                if (!open) continue;
                double score = dx * dx + dz * dz + Math.abs(p.getY() - at.getY());
                if (score < bestScore && !overWall(level, mover, p.getX() + 0.5, p.getY(), p.getZ() + 0.5)) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        if (best == null) return false;
        mover.teleportTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
        if (mover instanceof net.minecraft.world.entity.Mob m) net.betterthanvillage.ai.nav.WaypointNavigator.reset(m);
        return true;
    }

    /** A campaign horse looks this far for its rider. */
    private static final double REMOUNT_RANGE = 48;

    /** The soldier's own campaign horse standing within the distance, or null. */
    static AbstractHorse ownHorse(ServerLevel level, net.minecraft.world.entity.Entity rider, double within) {
        String id = rider.getUUID().toString();
        for (AbstractHorse h : level.getEntitiesOfClass(AbstractHorse.class, rider.getBoundingBox().inflate(within),
                h -> h.isAlive() && !h.isVehicle() && id.equals(h.getPersistentData().getString(CAMPAIGN_HORSE_TAG)))) {
            return h;
        }
        return null;
    }

    /**
     * A cavalry soldier that got off (thrown off in deep water, a fight) gets back on its horse nearby, unless it
     * left the horse to storm a wall on foot.
     */
    private static void remount(ServerLevel level, Villager v) {
        Campaign c = Campaigns.ofSoldier(level, v.getUUID());
        Campaign.Soldier s = c == null ? null : c.soldier(v.getUUID());
        if (s == null || !s.mounted || s.dismounted) return;
        AbstractHorse h = ownHorse(level, v, REMOUNT_RANGE);
        if (h == null) return;
        if (h.distanceToSqr(v) <= 9) {
            v.startRiding(h, true);
        } else {
            net.betterthanvillage.ai.nav.WaypointNavigator.travel(level, h, v.blockPosition(), 1.4);
        }
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

    /** A rider fell: the nearest soldier of the campaign on foot (no archer) takes its horse over. */
    static void passHorse(ServerLevel level, Campaign c, Campaign.Soldier dead) {
        if (!dead.mounted) return;
        String id = dead.id.toString();
        AbstractHorse horse = null;
        for (Campaign.Soldier s : c.soldiers) {
            if (horse != null) break;
            if (s == dead || !(level.getEntity(s.id) instanceof Villager v)) continue;
            for (AbstractHorse h : level.getEntitiesOfClass(AbstractHorse.class, v.getBoundingBox().inflate(64),
                    h -> h.isAlive() && id.equals(h.getPersistentData().getString(CAMPAIGN_HORSE_TAG)))) {
                horse = h;
                break;
            }
        }
        if (horse == null) return;
        Campaign.Soldier heir = null;
        double best = Double.MAX_VALUE;
        for (Campaign.Soldier s : c.soldiers) {
            if (s == dead || s.mounted || s.golem || s.cls == WarriorClass.RANGED) continue;
            if (!(level.getEntity(s.id) instanceof Villager v) || !v.isAlive()) continue;
            double d = v.distanceToSqr(horse);
            if (d < best) {
                best = d;
                heir = s;
            }
        }
        if (heir == null) return;
        heir.mounted = true;
        heir.dismounted = false;
        heir.cls = WarriorClass.CAVALRY;
        horse.getPersistentData().putString(CAMPAIGN_HORSE_TAG, heir.id.toString());
        ColonyManager.markDirty(level);
        LOGGER.info("[war] soldier {} takes over the horse of the fallen {}", heir.id.toString().substring(0, 8), id.substring(0, 8));
    }

    /** A village horse died: the stables lose it (a horse on campaign: its rider fights on foot). */
    public static void horseDied(ServerLevel level, AbstractHorse horse, net.minecraft.world.damagesource.DamageSource source) {
        String rider = horse.getPersistentData().getString(CAMPAIGN_HORSE_TAG);
        if (!rider.isEmpty()) {
            LOGGER.info("[war] the horse of soldier {} died at {}: {}", rider.substring(0, Math.min(8, rider.length())),
                    horse.blockPosition().toShortString(), source.getLocalizedDeathMessage(horse).getString());
            try {
                UUID riderId = UUID.fromString(rider);
                Campaign c = Campaigns.ofSoldier(level, riderId);
                Campaign.Soldier s = c == null ? null : c.soldier(riderId);
                if (s != null) {
                    s.mounted = false;
                    s.horse = null;
                    s.cls = WarriorClass.INFANTRY;
                    ColonyManager.markDirty(level);
                }
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
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
