package net.betterthanvillage.mixin.food;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/** Exposes the owner ("target") UUID of an item entity. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("target")
    UUID btv$owner();
}
