package net.betterthanvillage.mixin.society;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Read access to a template's block palettes (repair command). */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccess {
    @Accessor("palettes")
    List<StructureTemplate.Palette> btv$palettes();
}
