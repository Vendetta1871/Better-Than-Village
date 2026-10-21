package net.betterthanvillage.registry;

import net.betterthanvillage.BetterThanVillage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    /** Blocks villagers never break or replace (default: beacon). */
    public static final TagKey<Block> BUILDER_PROTECTED = TagKey.create(Registries.BLOCK, BetterThanVillage.id("builder_protected"));
    /** Items that count as a weapon in hand inside a territory (docs_v2/05-law.md §Offences); axes are not weapons. */
    public static final TagKey<Item> WEAPONS = TagKey.create(Registries.ITEM, BetterThanVillage.id("weapons"));
    /** White items a player shows in the main hand to surrender (docs_v2/05-law.md §Surrender). */
    public static final TagKey<Item> SURRENDER_FLAGS = TagKey.create(Registries.ITEM, BetterThanVillage.id("surrender_flags"));
    /** Farm animals whose killing inside a territory is an offence. */
    public static final TagKey<EntityType<?>> PROTECTED_ANIMALS = TagKey.create(Registries.ENTITY_TYPE, BetterThanVillage.id("protected_animals"));
    public static final ResourceKey<DamageType> GOLEM_ATTACK = ResourceKey.create(Registries.DAMAGE_TYPE, BetterThanVillage.id("golem_attack"));

    private ModTags() {
    }
}
