package net.betterthanvillage.defense;

import net.betterthanvillage.ai.ColonyTask;
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

import java.util.function.Predicate;

/** Guard combat task, the only FIGHT task (02 §3.1). */
public final class GuardCombatTask extends ColonyTask {
    private static final Predicate<ItemStack> MELEE = s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES);
    private static final Predicate<ItemStack> ANY_WEAPON = s -> MELEE.test(s) || s.is(ItemTags.BOW_ENCHANTABLE);

    private Colony colony;
    private LivingEntity target;
    private long attackReadyAt;
    private long retargetAt;
    private long moveAt;
    private long swapAt;

    public GuardCombatTask() {
        super(1200);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || v.isTrading() || colony.roleOf(v.getUUID()) != ColonyRole.GUARD) return false;
        this.colony = colony;
        LivingEntity t = pickTarget(level, v, colony);
        return t != null || recentAlarm(level, colony) || v.getHealth() < v.getMaxHealth() * 0.3f;
    }

    private static LivingEntity pickTarget(ServerLevel level, Villager v, Colony colony) {
        LivingEntity mem = v.getBrain().getMemory(ModRegistries.THREAT_TARGET.get()).orElse(null);
        if (ThreatManager.isValidHostile(mem) && ThreatManager.inColony(colony, mem)) return mem;
        return ThreatManager.findThreat(level, colony);
    }

    private static boolean recentAlarm(ServerLevel level, Colony colony) {
        return colony.lastThreatTick != Colony.NEVER && level.getGameTime() - colony.lastThreatTick <= 100;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        if (v.isSleeping() || v.isTrading() || colony == null || colony.roleOf(v.getUUID()) != ColonyRole.GUARD) return false;
        return (target != null && target.isAlive() && ThreatManager.inColony(colony, target))
                || recentAlarm(level, colony) || v.getHealth() < v.getMaxHealth() * 0.6f;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        attackReadyAt = retargetAt = moveAt = swapAt = now;
        target = pickTarget(level, v, colony);
        if (v.getMainHandItem().isEmpty()) {
            ItemStack weapon = ColonyStorage.withdrawOne(level, colony, ANY_WEAPON);
            if (!weapon.isEmpty()) v.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        }
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (GuardShield.tick(level, v, colony)) return;
        if (v.getHealth() < v.getMaxHealth() * 0.3f) {
            retreat(level, v, now);
            return;
        }
        if (now >= retargetAt) {
            target = pickTarget(level, v, colony);
            retargetAt = now + 10;
        }
        if (target == null) {
            v.getBrain().eraseMemory(ModRegistries.THREAT_TARGET.get());
            BlockPos rally = rallyPoint(level, v);
            if (now >= attackReadyAt && v.blockPosition().distSqr(rally) > 36 && PathBudget.tryAcquire(level)) {
                v.getNavigation().moveTo(rally.getX() + 0.5, rally.getY(), rally.getZ() + 0.5, 0.65);
                attackReadyAt = now + 20;
            }
            return;
        }
        fight(level, v, now);
    }

    private void retreat(ServerLevel level, Villager v, long now) {
        if (!colony.storages.isEmpty()) {
            BlockPos storage = colony.storages.get(0);
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
            if (colony.roleOf(g.getUUID()) != ColonyRole.GUARD || g.getMainHandItem().is(Items.BOW)) continue;
            if (best == null || g.getUUID().compareTo(best.getUUID()) < 0) best = g;
        }
        return best == null || best == v ? colony.core() : best.blockPosition();
    }

    private void fight(ServerLevel level, Villager v, long now) {
        v.getBrain().setMemory(ModRegistries.THREAT_TARGET.get(), target);
        double d2 = v.distanceToSqr(target);
        boolean recentlyHurt = v.getLastHurtByMob() != null && v.tickCount - v.getLastHurtByMobTimestamp() <= 30;
        boolean visible = v.hasLineOfSight(target);
        boolean preferRanged = d2 >= 25 && !recentlyHurt && visible;
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
        if (wanted.test(v.getMainHandItem())) return false;
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !wanted.test(s)) continue;
            v.stopUsingItem();
            ItemStack weapon = inv.removeItemNoUpdate(i);
            ItemStack old = v.getMainHandItem();
            v.setItemSlot(EquipmentSlot.MAINHAND, weapon);
            if (!old.isEmpty()) {
                ItemStack rest = inv.addItem(old);
                if (!rest.isEmpty()) level.addFreshEntity(new ItemEntity(level, v.getX(), v.getY() + 0.5, v.getZ(), rest));
            }
            inv.setChanged();
            return true;
        }
        return false;
    }

    private void melee(ServerLevel level, Villager v, long now) {
        ItemStack weapon = v.getMainHandItem();
        DamageSource source = ColonyDamage.source(level, v, target, level.damageSources().mobAttack(v));
        float base = (float) Math.max(1.0, ColonyDamage.attackDamage(weapon));
        float amount = EnchantmentHelper.modifyDamage(level, weapon, target, source, base);
        if (target.hurt(source, amount)) {
            EnchantmentHelper.doPostAttackEffects(level, target, source);
            if (!weapon.isEmpty() && weapon.getItem().hurtEnemy(weapon, target, v)) {
                weapon.getItem().postHurtEnemy(weapon, target, v);
            }
        }
        v.swing(InteractionHand.MAIN_HAND);
        double speed = ColonyDamage.attackSpeed(weapon);
        attackReadyAt = now + Mth.clamp((long) Math.ceil(20.0 / Math.max(0.1, speed)), 5L, 200L);
    }

    /** No villager, golem or player (other than the target) blocks the eye-to-eye segment. */
    private boolean lineOfFireClear(ServerLevel level, Villager v) {
        Vec3 from = v.getEyePosition();
        Vec3 to = target.getEyePosition();
        AABB box = new AABB(from, to).inflate(1.0);
        for (Entity e : level.getEntities(v, box, e -> (e instanceof Villager || e instanceof IronGolem || e instanceof Player) && e != target)) {
            if (e.getBoundingBox().inflate(0.4).clip(from, to).isPresent() || e.getBoundingBox().inflate(0.4).contains(from)) {
                return false;
            }
        }
        return true;
    }

    private void shoot(ServerLevel level, Villager v) {
        Arrow arrow = new Arrow(level, v, new ItemStack(Items.ARROW), v.getMainHandItem().copy());
        double dx = target.getX() - arrow.getX();
        double dz = target.getZ() - arrow.getZ();
        double dy = target.getY() + target.getBbHeight() * 0.5 - arrow.getY() + Math.sqrt(dx * dx + dz * dz) * 0.18;
        arrow.shoot(dx, dy, dz, 1.6f, 5.0f);
        arrow.setBaseDamage(4.0);
        level.addFreshEntity(arrow);
        v.swing(InteractionHand.MAIN_HAND);
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
    }
}
