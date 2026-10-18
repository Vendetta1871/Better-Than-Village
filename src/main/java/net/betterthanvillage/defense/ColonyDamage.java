package net.betterthanvillage.defense;

import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.registry.ModTags;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** golem_attack source selection and weapon stats from attribute modifiers (02 §2.3, §3.1, §5). */
public final class ColonyDamage {
    private ColonyDamage() {
    }

    /**
     * A {@code golem_attack} source with the attacker when the victim is a player and the attacker
     * stands inside a colony; otherwise the original source.
     */
    public static DamageSource source(ServerLevel level, LivingEntity attacker, Entity target, DamageSource original) {
        if (target instanceof Player && ColonyManager.colonyAt(level, attacker.blockPosition()) != null) {
            return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(ModTags.GOLEM_ATTACK), attacker);
        }
        return original;
    }

    /** The stack's ATTACK_DAMAGE modifiers applied to a base of 1.0 in the main hand (bare hand = 1). */
    public static double attackDamage(ItemStack stack) {
        return apply(stack, Attributes.ATTACK_DAMAGE, 1.0);
    }

    /** The stack's ATTACK_SPEED modifiers applied to a base of 4.0 in the main hand. */
    public static double attackSpeed(ItemStack stack) {
        return apply(stack, Attributes.ATTACK_SPEED, 4.0);
    }

    private static double apply(ItemStack stack, Holder<Attribute> attribute, double base) {
        if (stack.isEmpty()) return base;
        AttributeInstance instance = new AttributeInstance(attribute, i -> {
        });
        instance.setBaseValue(base);
        stack.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, (attr, modifier) -> {
            if (attr.equals(attribute) && !instance.hasModifier(modifier.id())) {
                instance.addTransientModifier(modifier);
            }
        });
        return instance.getValue();
    }
}
