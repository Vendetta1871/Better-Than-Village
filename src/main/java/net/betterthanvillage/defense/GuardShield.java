package net.betterthanvillage.defense;

import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Guard shield against incoming projectiles (02 §3.5). The shield only goes up for an arrow that
 * would actually hit, and the guard keeps walking while blocking (no stalemate against archers).
 */
public final class GuardShield {
    public static final String STATUS = "прикрывается щитом от стрелы";
    /** Projectile scan radius around each guard. */
    private static final double SCAN_RADIUS = 24.0;

    private record Memo(long time, boolean result) {
    }

    private static final Map<Villager, Long> RAISE_UNTIL = new WeakHashMap<>();
    private static final Map<Villager, Memo> MEMO = new WeakHashMap<>();
    /** Debug counter of projectile scans. */
    public static long scans;

    private GuardShield() {
    }

    /** Returns true while the guard holds its shield up this tick (the caller must not attack). */
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
        Long until = RAISE_UNTIL.get(v);
        // peacetime: no scan at all
        boolean alert = until != null || colony.recentThreat(now, 200) || RaidShelter.isActive(level, colony)
                || v.getBrain().hasMemoryValue(ModRegistries.THREAT_TARGET.get());
        Entity danger = alert ? incomingProjectile(level, v, colony) : null;
        if (danger != null) {
            double dx = danger.getX() - v.getX();
            double dz = danger.getZ() - v.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            v.setYRot(yaw);
            v.setYBodyRot(yaw);
            v.setYHeadRot(yaw);
            v.getLookControl().setLookAt(danger, 90f, 90f);
            until = now + 12;
            RAISE_UNTIL.put(v, until);
        }
        if (until == null || now >= until) {
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
        WorkTracker.status(v, STATUS);
        MEMO.put(v, new Memo(now, true));
        return true;
    }

    private static Entity incomingProjectile(ServerLevel level, Villager v, Colony colony) {
        scans++;
        AbstractArrow best = null;
        int bestSteps = Integer.MAX_VALUE;
        for (AbstractArrow p : level.getEntitiesOfClass(AbstractArrow.class, v.getBoundingBox().inflate(SCAN_RADIUS),
                a -> !a.isRemoved() && a.getDeltaMovement().lengthSqr() >= 0.04)) {
            Entity owner = p.getOwner();
            if (owner == v) continue;
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

    public static void clearCaches() {
        RAISE_UNTIL.clear();
        MEMO.clear();
        scans = 0;
    }
}
