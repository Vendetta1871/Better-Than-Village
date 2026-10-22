package net.betterthanvillage.economy;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.politics.Countries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Predicate;

/**
 * The healer cures a zombie villager in the village (docs_v2/02-professions.md §Healer) with what a player uses: a
 * splash potion of weakness, then a golden apple. Both come from the healer's home chest (its workshop makes them)
 * or from the treasury, which pays the healer for the work. A villager who sees a zombie villager calls the healer
 * (who gets up even at night); the warriors leave it alone while the call stands. The healer throws the potion from
 * a few blocks away (the zombie then hardly hurts anyone) and cures it at once with the apple. With no healer, no
 * cure at hand, or a call unanswered for a minute, the warriors fight it as any monster.
 */
public final class CureTask extends ColonyTask {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STATUS = "лечение зомби-жителя";
    private static final Predicate<ItemStack> WEAKNESS = s -> Workshop.potion(s, Potions.WEAKNESS);
    private static final Predicate<ItemStack> APPLE = s -> s.is(Items.GOLDEN_APPLE);
    /** Colony runtime map of the calls for a healer: zombie villager UUID -> game time of the call. */
    private static final String CALLS = "btv_cure_calls";
    /** A call unanswered for this long is given up: the warriors take the zombie on. */
    private static final long CALL_TIME = 1200;
    /** Who calls the healer: a villager of the village within this distance that sees the zombie villager. */
    private static final double WITNESS_RANGE = 24;
    /** The healer throws the potion of weakness from this far. */
    private static final double THROW_RANGE = 8;
    /** Persistent-data key of a zombie villager a healer goes to cure: the game time it was chosen. */
    private static final String PATIENT_TAG = "better_than_village_patient";
    private static final String CURE_CACHE = "btv_cure_at_hand";
    /** Labour paid for a cure (public work, like the masons' blocks). */
    private static final int LABOR = 64;

    private ZombieVillager patient;
    private long giveUpAt;
    private boolean done;

    public CureTask() {
        super(2400);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || v.getTradingPlayer() != null || colony.roleOf(v.getUUID()) != ColonyRole.HEALER) return false;
        if ((level.getGameTime() + v.getId()) % 20 != 0) return false;
        Household h = colony.households.get(v.getUUID());
        if (h == null || !has(level, colony, h, WEAKNESS) || !has(level, colony, h, APPLE)) return false;
        patient = patient(level, colony, v);
        return patient != null;
    }

    /** The nearest zombie villager in the village the healer was called to. */
    private static ZombieVillager patient(ServerLevel level, Colony colony, Villager v) {
        int r = colony.radius();
        java.util.Map<java.util.UUID, Long> calls = calls(level, colony);
        if (calls.isEmpty()) return null;
        List<ZombieVillager> found = level.getEntitiesOfClass(ZombieVillager.class, new AABB(colony.core()).inflate(r, 12, r),
                z -> z.isAlive() && !z.isConverting() && calls.containsKey(z.getUUID()) && ThreatManager.inColony(colony, z));
        ZombieVillager best = null;
        double d = Double.MAX_VALUE;
        for (ZombieVillager z : found) {
            double x = z.distanceToSqr(v);
            if (x < d) {
                d = x;
                best = z;
            }
        }
        return best;
    }

    private static boolean has(ServerLevel level, Colony colony, Household h, Predicate<ItemStack> what) {
        return Chests.count(ColonyStorage.usable(level, h), what) > 0 || Chests.count(ColonyStorage.treasury(level, colony), what) > 0;
    }

    /** One of the items, from the healer's chest first, then the treasury (which then pays for the work). */
    private static boolean take(ServerLevel level, Colony colony, Household h, Predicate<ItemStack> what) {
        if (!Chests.take(ColonyStorage.usable(level, h), what, 1).isEmpty()) return true;
        if (Chests.take(ColonyStorage.treasury(level, colony), what, 1).isEmpty()) return false;
        h.labor += LABOR;
        return true;
    }

    /** A healer is on its way to cure this zombie villager: the warriors leave it alone for a minute. */
    public static boolean patient(ZombieVillager z) {
        long since = z.getPersistentData().getLong(PATIENT_TAG);
        return since != 0 && z.level().getGameTime() - since < 1200;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        return !done && patient != null && patient.isAlive() && !v.isSleeping() && now < giveUpAt;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        done = false;
        giveUpAt = now + CALL_TIME;
        patient.getPersistentData().putLong(PATIENT_TAG, Math.max(1, now));
        WorkTracker.status(v, STATUS);
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        Colony colony = ColonyManager.colonyOf(level, v);
        Household h = colony == null ? null : colony.households.get(v.getUUID());
        if (h == null) {
            done = true;
            return;
        }
        double d = v.distanceToSqr(patient);
        // The potion is thrown from a few blocks away, as soon as the healer sees the zombie.
        if (!patient.hasEffect(MobEffects.WEAKNESS) && d <= THROW_RANGE * THROW_RANGE && v.hasLineOfSight(patient)) {
            v.getLookControl().setLookAt(patient);
            if (!take(level, colony, h, WEAKNESS)) {
                done = true;
                return;
            }
            v.swing(InteractionHand.MAIN_HAND);
            patient.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 1800, 0), v);
            level.levelEvent(2002, patient.blockPosition(), 0x484D48);
            return;
        }
        if (d > 9 || !patient.hasEffect(MobEffects.WEAKNESS)) {
            Nav.approach(level, v, patient.blockPosition(), 0.7);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(patient);
        if (!take(level, colony, h, APPLE)) {
            done = true;
            return;
        }
        v.swing(InteractionHand.MAIN_HAND);
        // Unlike a player's cure, the healer's works at once: the conversion ends on the next tick.
        patient.startConverting(null, 1);
        calls(level, colony).remove(patient.getUUID());
        level.playSound(null, patient.getX(), patient.getY(), patient.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.NEUTRAL, 1f, 1f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getY() + 1, patient.getZ(), 6, 0.4, 0.5, 0.4, 0);
        WorkTracker.credit(v, STATUS, 1);
        LOGGER.info("[village] a healer of {} cures a zombie villager", Countries.villageName(colony));
        done = true;
    }

    /**
     * A zombie villager the village would rather cure than kill: the village has a healer and the cure is at hand
     * (in a healer's home or the treasury), and a villager who sees it has called the healer (the call is made here,
     * the first time) less than a minute ago. The warriors then leave it for the healer.
     */
    public static boolean awaitsCure(ServerLevel level, Colony colony, net.minecraft.world.entity.LivingEntity e) {
        if (!(e instanceof ZombieVillager z) || !z.isAlive() || !cureAtHand(level, colony)) return false;
        long now = level.getGameTime();
        java.util.Map<java.util.UUID, Long> calls = colony.runtime(CALLS, java.util.HashMap::new);
        Long called = calls.get(z.getUUID());
        if (called == null) {
            // Called once: a zombie the healer did not come to in time is the warriors' for good.
            if (z.getPersistentData().getBoolean(PATIENT_TAG + "_called")) return false;
            Villager witness = witness(level, colony, z);
            if (witness == null) return false;
            calls.put(z.getUUID(), now);
            z.getPersistentData().putBoolean(PATIENT_TAG + "_called", true);
            LOGGER.info("[village] a villager of {} sees a zombie villager at {} and calls the healer", Countries.villageName(colony),
                    z.blockPosition().toShortString());
            return true;
        }
        if (now - called < CALL_TIME) return true;
        calls.remove(z.getUUID());
        return false;
    }

    /** A grown villager of the village that sees the zombie villager, or null. */
    private static Villager witness(ServerLevel level, Colony colony, ZombieVillager z) {
        for (Villager v : level.getEntitiesOfClass(Villager.class, z.getBoundingBox().inflate(WITNESS_RANGE),
                v -> v.isAlive() && !v.isBaby() && !v.isSleeping())) {
            if (ColonyManager.colonyOf(level, v) == colony && v.hasLineOfSight(z)) return v;
        }
        return null;
    }

    /** The standing calls of the village (expired ones dropped). */
    private static java.util.Map<java.util.UUID, Long> calls(ServerLevel level, Colony colony) {
        java.util.Map<java.util.UUID, Long> calls = colony.runtime(CALLS, java.util.HashMap::new);
        long now = level.getGameTime();
        if (calls.values().removeIf(t -> now - t >= CALL_TIME)) {
            LOGGER.info("[village] no healer of {} came to a zombie villager in time: the warriors take it on", Countries.villageName(colony));
        }
        return calls;
    }

    /** The village has called a healer and has the cure: a healer gets up and goes (ai.VillagerController). */
    public static boolean called(ServerLevel level, Colony colony) {
        return !calls(level, colony).isEmpty() && cureAtHand(level, colony);
    }

    /** A healer of the village is alive and the cure is at hand; looked up at most every five seconds. */
    private static boolean cureAtHand(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        long[] cache = colony.runtime(CURE_CACHE, () -> new long[]{-1, 0});
        if (now - cache[0] >= 100) {
            cache[0] = now;
            cache[1] = lookUpCure(level, colony) ? 1 : 0;
        }
        return cache[1] == 1;
    }

    private static boolean lookUpCure(ServerLevel level, Colony colony) {
        for (Household h : colony.households.values()) {
            if (colony.roleOf(h.owner) != ColonyRole.HEALER || !(level.getEntity(h.owner) instanceof Villager v) || !v.isAlive()) continue;
            if (has(level, colony, h, WEAKNESS) && has(level, colony, h, APPLE)) return true;
        }
        return false;
    }

    /** A zombie villager became a villager again (a healer's or a player's cure). */
    public static void onConverted(net.neoforged.neoforge.event.entity.living.LivingConversionEvent.Post event) {
        if (event.getEntity() instanceof ZombieVillager && event.getOutcome() instanceof Villager v && v.level() instanceof ServerLevel level) {
            Colony colony = ColonyManager.colonyAt(level, v.blockPosition());
            LOGGER.info("[village] a zombie villager is cured at {}{}", v.blockPosition().toShortString(),
                    colony == null ? "" : " in " + Countries.villageName(colony));
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        patient = null;
        v.getNavigation().stop();
    }
}
