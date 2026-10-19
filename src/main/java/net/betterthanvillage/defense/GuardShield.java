package net.betterthanvillage.defense;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Guard shield against incoming projectiles and aiming archers (02 §3.5). */
public final class GuardShield {
    public static final String STATUS = "прикрывается щитом от стрелы";

    private record Memo(long time, boolean result) {
    }

    private record Scan(ServerLevel level, long time, List<AbstractArrow> projectiles) {
    }

    private static final Map<Villager, Long> RAISE_UNTIL = new WeakHashMap<>();
    private static final Map<Villager, Memo> MEMO = new WeakHashMap<>();
    private static final Map<Colony, Scan> SCANS = new WeakHashMap<>();
    /** Debug counter of projectile scans. */
    public static long scans;

    private GuardShield() {
    }

    /** Returns true when the guard is raising its shield this tick (the caller then stops). */
    public static boolean tick(ServerLevel level, Villager v, Colony colony) {
        if (colony == null || colony.roleOf(v.getUUID()) != ColonyRole.GUARD || v.isSleeping() || v.isTrading()
                || !v.getOffhandItem().is(Items.SHIELD)) {
            return false;
        }
        long now = level.getGameTime();
        Memo memo = MEMO.get(v);
        if (memo != null && memo.time() == now) {
            return memo.result();
        }
        Entity danger = incomingProjectile(level, v, colony, now);
        boolean archer = false;
        if (danger == null) {
            danger = aimingArcher(v, now);
            archer = danger != null;
        }
        if (danger != null) {
            double dx = danger.getX() - v.getX();
            double dz = danger.getZ() - v.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            v.setYRot(yaw);
            v.setYBodyRot(yaw);
            v.setYHeadRot(yaw);
            v.getLookControl().setLookAt(danger, 90f, 90f);
            boolean archerIdle = archer && !((LivingEntity) danger).isUsingItem();
            RAISE_UNTIL.put(v, now + (archerIdle ? 6 : 12));
        }
        Long until = RAISE_UNTIL.get(v);
        if (now >= (until == null ? Long.MIN_VALUE : until)) {
            RAISE_UNTIL.remove(v);
            if (until != null && v.isUsingItem() && v.getUsedItemHand() == InteractionHand.OFF_HAND) {
                v.stopUsingItem();
            }
            MEMO.put(v, new Memo(now, false));
            return false;
        }
        if (!(v.isUsingItem() && v.getUsedItemHand() == InteractionHand.OFF_HAND)) {
            v.stopUsingItem();
            v.startUsingItem(InteractionHand.OFF_HAND);
        }
        v.getNavigation().stop();
        WorkTracker.status(v, STATUS);
        MEMO.put(v, new Memo(now, true));
        return true;
    }

    private static List<AbstractArrow> scan(ServerLevel level, Colony colony, long now) {
        Scan s = SCANS.get(colony);
        if (s != null && s.level() == level && s.time() == now) {
            return s.projectiles();
        }
        scans++;
        AABB box = ThreatManager.colonyBox(colony).inflate(32);
        List<AbstractArrow> list = level.getEntitiesOfClass(AbstractArrow.class, box,
                p -> !p.isRemoved() && p.getDeltaMovement().lengthSqr() >= 0.04);
        SCANS.put(colony, new Scan(level, now, list));
        return list;
    }

    private static Entity incomingProjectile(ServerLevel level, Villager v, Colony colony, long now) {
        AbstractArrow best = null;
        int bestSteps = Integer.MAX_VALUE;
        for (AbstractArrow p : scan(level, colony, now)) {
            Entity owner = p.getOwner();
            if (owner == v || p.isRemoved()) continue;
            if (owner instanceof Villager ov && colony.roleOf(ov.getUUID()) == ColonyRole.GUARD) continue;
            int steps = stepsToHit(p, v);
            if (steps < 0 || steps >= bestSteps || !v.hasLineOfSight(p)) continue;
            best = p;
            bestSteps = steps;
        }
        return best;
    }

    /** Simulates up to 24 steps; returns the step at which the projectile hits the guard, or -1. */
    private static int stepsToHit(AbstractArrow p, LivingEntity guard) {
        Vec3 vel = p.getDeltaMovement();
        if (vel.lengthSqr() < 0.04) return -1;
        AABB box = guard.getBoundingBox().inflate(0.2);
        Vec3 pos = p.position();
        double drag = p.isInWater() ? 0.6 : 0.99;
        double gravity = p.getGravity();
        for (int i = 0; i < 24; i++) {
            Vec3 next = pos.add(vel);
            if (box.contains(pos) || box.clip(pos, next).isPresent()) {
                return i;
            }
            vel = vel.scale(drag).add(0, -gravity, 0);
            pos = next;
        }
        return -1;
    }

    private static LivingEntity aimingArcher(Villager v, long now) {
        LivingEntity e = v.getBrain().getMemory(ModRegistries.THREAT_TARGET.get()).orElse(null);
        if (e == null || !e.isAlive() || e.distanceToSqr(v) > 576 || !v.hasLineOfSight(e)) return null;
        boolean drawing = e.isUsingItem() && (e.getUseItem().is(Items.BOW) || e.getUseItem().is(Items.CROSSBOW));
        boolean skeleton = e instanceof AbstractSkeleton sk && sk.getTarget() == v && sk.isHolding(Items.BOW) && Math.floorMod(now, 40L) < 20;
        return drawing || skeleton ? e : null;
    }

    public static void clearCaches() {
        RAISE_UNTIL.clear();
        MEMO.clear();
        SCANS.clear();
        scans = 0;
    }
}
