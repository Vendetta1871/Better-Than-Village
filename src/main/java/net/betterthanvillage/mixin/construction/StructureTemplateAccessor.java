package net.betterthanvillage.mixin.construction;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Access to the template palettes (the builder uses the first palette, 05 §2.4). */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
    @Accessor("palettes")
    List<StructureTemplate.Palette> btv$palettes();
}
