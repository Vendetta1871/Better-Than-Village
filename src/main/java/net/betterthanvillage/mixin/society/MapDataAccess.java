package net.betterthanvillage.mixin.society;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the map data constructor: deed maps are centred on the property, not on vanilla's map grid. */
@Mixin(MapItemSavedData.class)
public interface MapDataAccess {
    @Invoker("<init>")
    static MapItemSavedData btv$create(int centerX, int centerZ, byte scale, boolean trackingPosition, boolean unlimitedTracking,
                                       boolean locked, ResourceKey<Level> dimension) {
        throw new AssertionError();
    }
}
