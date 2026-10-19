package net.betterthanvillage.mixin.client;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Villager model: arms always visible, hat hidden under a helmet, robe hidden under leggings (02 §2.6). */
@Mixin(VillagerModel.class)
public abstract class VillagerModelMixin {
    @Shadow
    @Final
    private ModelPart root;
    @Shadow
    @Final
    private ModelPart hat;
    @Shadow
    @Final
    private ModelPart hatRim;

    @Unique
    private boolean btv$hideHat;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", at = @At("RETURN"))
    private void btv$gearVisibility(Entity entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        btv$hideHat = false;
        boolean legs = false;
        if (entity instanceof Villager v) {
            btv$hideHat = !v.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
            legs = !v.getItemBySlot(EquipmentSlot.LEGS).isEmpty();
            root.getChild("arms").visible = true;
        }
        ModelPart body = root.getChild("body");
        if (body.hasChild("jacket")) body.getChild("jacket").visible = !legs;
        if (entity instanceof Villager && !btv$hideHat) {
            // the model is shared: undo the hiding left over from the previous helmeted villager
            hat.visible = true;
            hatRim.visible = true;
        }
        btv$applyHat();
    }

    @Inject(method = "hatVisible", at = @At("TAIL"))
    private void btv$keepHatHidden(boolean visible, CallbackInfo ci) {
        btv$applyHat();
    }

    @Unique
    private void btv$applyHat() {
        if (btv$hideHat) {
            hat.visible = false;
            hatRim.visible = false;
        }
    }
}
