package net.betterthanvillage.registry;

import net.betterthanvillage.BetterThanVillage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    /** Blocks villagers never break or replace (default: beacon). */
    public static final TagKey<Block> BUILDER_PROTECTED = TagKey.create(Registries.BLOCK, BetterThanVillage.id("builder_protected"));
    public static final ResourceKey<DamageType> GOLEM_ATTACK = ResourceKey.create(Registries.DAMAGE_TYPE, BetterThanVillage.id("golem_attack"));

    private ModTags() {
    }
}
