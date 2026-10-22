package net.betterthanvillage.defense;

import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Villager fighting moves shared by guards and soldiers on campaign (02 §3.1, docs_v2/07-warfare.md). */
public final class Combat {
    private Combat() {
    }

    /** One melee blow; returns the ticks until the next one (weapon attack speed). */
    public static long melee(ServerLevel level, Villager v, LivingEntity target) {
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
        return Mth.clamp((long) Math.ceil(20.0 / Math.max(0.1, speed)), 5L, 200L);
    }

    /** An arrow at the target. */
    public static void shoot(ServerLevel level, Villager v, LivingEntity target) {
        Arrow arrow = new Arrow(level, v, new ItemStack(Items.ARROW), v.getMainHandItem().copy());
        double dx = target.getX() - arrow.getX();
        double dz = target.getZ() - arrow.getZ();
        double dy = target.getY() + target.getBbHeight() * 0.5 - arrow.getY() + Math.sqrt(dx * dx + dz * dz) * 0.18;
        arrow.shoot(dx, dy, dz, 1.6f, 5.0f);
        arrow.setBaseDamage(4.0);
        level.addFreshEntity(arrow);
        v.swing(InteractionHand.MAIN_HAND);
    }

    /** No villager, golem or player (other than the target) blocks the eye-to-eye segment. */
    public static boolean lineOfFireClear(ServerLevel level, Villager v, LivingEntity target) {
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

    /** Puts a matching weapon from the inventory into the main hand; false if it is already there or none is carried. */
    public static boolean swapWeapon(ServerLevel level, Villager v, Predicate<ItemStack> wanted) {
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
}
