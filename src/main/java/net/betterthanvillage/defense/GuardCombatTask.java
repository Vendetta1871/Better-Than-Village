package net.betterthanvillage.defense;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.nav.WaypointNavigator;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.schedule.Activity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Guard combat task, the only FIGHT task (02 §3.1). */
public final class GuardCombatTask extends ColonyTask {
    // axes stay with the lumberjacks: guards fight with swords and bows only
    private static final Predicate<ItemStack> MELEE = s -> s.is(ItemTags.SWORDS);
    private static final Predicate<ItemStack> ANY_WEAPON = s -> MELEE.test(s) || s.is(ItemTags.BOW_ENCHANTABLE);

    private Colony colony;
    private LivingEntity target;
    private long attackReadyAt;
    private long retargetAt;
    private long moveAt;
    private long swapAt;
    /** Give-up bookkeeping: tracked target id, best distance so far and the tick of the last progress. */
    private int trackedId = -1;
    private double trackedBest;
    private long trackedSince;
    /** Targets this guard gave up on (entity id -> until tick). */
    private final Map<Integer, Long> unreachable = new HashMap<>();
    private static final int GIVE_UP_TICKS = 200;
    private static final int IGNORE_TICKS = 600;

    public GuardCombatTask() {
        super(1200);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || v.isTrading() || colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR) return false;
        this.colony = colony;
        LivingEntity t = pickTarget(level, v);
        return t != null || recentAlarm(level, colony) || v.getHealth() < v.getMaxHealth() * 0.3f;
    }

    /** Memory target if it attacks the colony; else the nearest attacker; else memory; else the nearest threat. */
    private LivingEntity pickTarget(ServerLevel level, Villager v) {
        long now = level.getGameTime();
        unreachable.values().removeIf(until -> until <= now);
        LivingEntity mem = v.getBrain().getMemory(ModRegistries.THREAT_TARGET.get()).orElse(null);
        boolean memOk = mem != null && ThreatManager.keepsTarget(level, colony, mem) && !gaveUp(mem)
                && !ThreatManager.isIgnored(colony, mem, now);
        if (memOk && ThreatManager.isAttacking(level, colony, mem)) return mem;
        LivingEntity attacker = ThreatManager.nearestThreat(level, colony, v, this::gaveUp, true);
        if (attacker != null) return attacker;
        if (memOk) return mem;
        return ThreatManager.nearestThreat(level, colony, v, this::gaveUp, false);
    }

    private boolean gaveUp(LivingEntity e) {
        return unreachable.containsKey(e.getId());
    }

    /** True (and the target is dropped) when the guard made no progress on it for GIVE_UP_TICKS. */
    private boolean giveUpIfStuck(ServerLevel level, Villager v, long now) {
        double dist = Math.sqrt(v.distanceToSqr(target));
        boolean hitting = target.getLastHurtByMob() == v && target.tickCount - target.getLastHurtByMobTimestamp() <= 100;
        if (target.getId() != trackedId) {
            trackedId = target.getId();
            trackedBest = dist;
            trackedSince = now;
        } else if (dist < trackedBest - 1.0 || hitting) {
            trackedBest = Math.min(trackedBest, dist);
            trackedSince = now;
        } else if (now - trackedSince > GIVE_UP_TICKS) {
            unreachable.put(target.getId(), now + IGNORE_TICKS);
            // nobody can reach it: it must not keep the alarm alive either (cleared again if it hurts someone)
            ThreatManager.ignore(level, colony, target, IGNORE_TICKS);
            v.getBrain().eraseMemory(ModRegistries.THREAT_TARGET.get());
            WaypointNavigator.reset(v);
            target = null;
            trackedId = -1;
            return true;
        }
        return false;
    }

    private static boolean recentAlarm(ServerLevel level, Colony colony) {
        return colony.lastThreatTick != Colony.NEVER && level.getGameTime() - colony.lastThreatTick <= 100;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        if (v.isSleeping() || v.isTrading() || colony == null || colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR) return false;
        // running behaviors keep ticking after an activity switch: stop once the controller leaves FIGHT
        if (VillagerUtil.currentActivity(v) != Activity.FIGHT) return false;
        return (target != null && target.isAlive() && ThreatManager.inColony(colony, target, ThreatManager.BORDER_MARGIN))
                || recentAlarm(level, colony) || v.getHealth() < v.getMaxHealth() * 0.6f;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        attackReadyAt = retargetAt = moveAt = swapAt = now;
        target = pickTarget(level, v);
        trackedId = -1;
        if (v.getMainHandItem().isEmpty() && !swapWeapon(level, v, ANY_WEAPON)) {
            ItemStack weapon = ColonyStorage.withdrawOne(level, colony, ANY_WEAPON);
            if (!weapon.isEmpty()) v.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        }
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        boolean shielding = GuardShield.tick(level, v, colony);
        if (v.getHealth() < v.getMaxHealth() * 0.3f) {
            retreat(level, v, now);
            return;
        }
        if (now >= retargetAt) {
            target = pickTarget(level, v);
            retargetAt = now + 10;
        }
        if (target != null && giveUpIfStuck(level, v, now)) {
            retargetAt = now;
        }
        if (target == null) {
            v.getBrain().eraseMemory(ModRegistries.THREAT_TARGET.get());
            if (now >= attackReadyAt) {
                BlockPos rally = rallyPoint(level, v);
                if (v.blockPosition().distSqr(rally) <= 36) {
                    attackReadyAt = now + 20;
                } else if (PathBudget.tryAcquire(level)) {
                    v.getNavigation().moveTo(rally.getX() + 0.5, rally.getY(), rally.getZ() + 0.5, 0.65);
                    attackReadyAt = now + 20;
                }
            }
            return;
        }
        fight(level, v, now, shielding);
    }

    private void retreat(ServerLevel level, Villager v, long now) {
        BlockPos storage = ColonyStorage.nearest(level, colony, v.blockPosition());
        if (storage != null) {
            Nav.approach(level, v, storage, 0.75);
            if (v.distanceToSqr(storage.getX() + 0.5, storage.getY(), storage.getZ() + 0.5) <= 9 && now >= attackReadyAt) {
                ItemStack food = ColonyStorage.withdrawOne(level, colony, ColonyStorage::isFood);
                if (!food.isEmpty()) v.heal(4f);
                attackReadyAt = now + 40;
            }
        } else if (PathBudget.tryAcquire(level)) {
            BlockPos core = colony.core();
            v.getNavigation().moveTo(core.getX(), core.getY(), core.getZ(), 0.75);
        }
    }

    /** Smallest-UUID non-archer guard near the core, or the core itself. */
    private BlockPos rallyPoint(ServerLevel level, Villager v) {
        AABB box = new AABB(colony.core()).inflate(Math.min(colony.radius(), 48));
        Villager best = null;
        for (Villager g : level.getEntitiesOfClass(Villager.class, box, LivingEntity::isAlive)) {
            if (colony.roleOf(g.getUUID()) != ColonyRole.WARRIOR || g.getMainHandItem().is(Items.BOW)) continue;
            if (best == null || g.getUUID().compareTo(best.getUUID()) < 0) best = g;
        }
        return best == null || best == v ? colony.core() : best.blockPosition();
    }

    private void fight(ServerLevel level, Villager v, long now, boolean shielding) {
        v.getBrain().setMemory(ModRegistries.THREAT_TARGET.get(), target);
        double d2 = v.distanceToSqr(target);
        if (shielding) {
            // blocking an arrow: keep closing in, just do not attack
            if (d2 > 4 && now >= moveAt && !v.getMainHandItem().is(Items.BOW)) {
                WaypointNavigator.travel(level, v, target.blockPosition(), 0.72);
                moveAt = now + 12;
            }
            return;
        }
        boolean recentlyHurt = v.getLastHurtByMob() != null && v.tickCount - v.getLastHurtByMobTimestamp() <= 30;
        boolean visible = v.hasLineOfSight(target);
        // Archers shoot whenever they can; the other classes only fight in melee (docs_v2/07-warfare.md §Classes).
        boolean archer = net.betterthanvillage.warfare.Army.classOf(colony, v.getUUID()) == net.betterthanvillage.warfare.WarriorClass.RANGED;
        boolean preferRanged = archer && d2 >= 16 && visible && (d2 >= 25 || !recentlyHurt);
        if (now >= swapAt && swapWeapon(level, v, preferRanged ? s -> s.is(Items.BOW) : MELEE)) {
            swapAt = now + 20;
        }
        v.getLookControl().setLookAt(target, 30f, 30f);
        if (v.getOffhandItem().is(Items.SHIELD) && (recentlyHurt || d2 <= 16) && now < attackReadyAt) {
            float yaw = (float) Math.toDegrees(Math.atan2(target.getZ() - v.getZ(), target.getX() - v.getX())) - 90f;
            v.setYRot(yaw);
            v.setYBodyRot(yaw);
            v.setYHeadRot(yaw);
            if (!usingOffhand(v)) v.startUsingItem(InteractionHand.OFF_HAND);
            if (d2 > 4 && now >= moveAt) {
                WaypointNavigator.travel(level, v, target.blockPosition(), 0.72);
                moveAt = now + 12;
            }
            return;
        }
        if (usingOffhand(v)) v.stopUsingItem();
        if (v.getMainHandItem().is(Items.BOW) && d2 <= 225 && visible) {
            boolean clear = lineOfFireClear(level, v);
            if (d2 >= 49 && clear) {
                v.getNavigation().stop();
            } else if (now >= moveAt) {
                kite(level, v);
                moveAt = now + 12;
            }
            if (now >= attackReadyAt && clear) {
                shoot(level, v);
                attackReadyAt = now + 30;
            }
        } else if (d2 > 4) {
            if (now >= moveAt) {
                WaypointNavigator.travel(level, v, target.blockPosition(), 0.85);
                moveAt = now + 12;
            }
        } else if (now >= attackReadyAt && visible) {
            melee(level, v, now);
        }
    }

    private static boolean usingOffhand(Villager v) {
        return v.isUsingItem() && v.getUsedItemHand() == InteractionHand.OFF_HAND;
    }

    private static boolean swapWeapon(ServerLevel level, Villager v, Predicate<ItemStack> wanted) {
        return Combat.swapWeapon(level, v, wanted);
    }

    private void melee(ServerLevel level, Villager v, long now) {
        attackReadyAt = now + Combat.melee(level, v, target);
    }

    private boolean lineOfFireClear(ServerLevel level, Villager v) {
        return Combat.lineOfFireClear(level, v, target);
    }

    private void shoot(ServerLevel level, Villager v) {
        Combat.shoot(level, v, target);
    }

    /** Back away from the target, trying a lateral offset first. */
    private void kite(ServerLevel level, Villager v) {
        double ax = v.getX() - target.getX();
        double az = v.getZ() - target.getZ();
        double len = Math.max(0.1, Math.sqrt(ax * ax + az * az));
        ax /= len;
        az /= len;
        int side = v.getId() % 2 == 0 ? 1 : -1;
        double r2 = (double) colony.radius() * colony.radius();
        for (int offset : new int[]{2 * side, -4 * side, 0}) {
            int x = Mth.floor(v.getX() + ax * 5 - az * offset);
            int z = Mth.floor(v.getZ() + az * 5 + ax * offset);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos cand = new BlockPos(x, y, z);
            if (Math.abs(y - v.getY()) > 2 || !level.getFluidState(cand.below()).isEmpty() || cand.distSqr(colony.core()) > r2) continue;
            if (!PathBudget.tryAcquire(level)) return;
            Path path = v.getNavigation().createPath(cand, 0);
            if (path != null && path.canReach()) {
                v.getNavigation().moveTo(path, 0.95);
                return;
            }
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        if (usingOffhand(v)) v.stopUsingItem();
        WaypointNavigator.reset(v);
        v.getBrain().eraseMemory(ModRegistries.THREAT_TARGET.get());
        colony = null;
        target = null;
        trackedId = -1;
    }
}
