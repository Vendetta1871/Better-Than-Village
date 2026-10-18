package net.betterthanvillage.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Draws guard gear on villagers: armor through a villager-shaped armor model and the off-arm item
 * through a private biped model posed from the villager (02 §2.6, adapted to the 1.21.1 renderer API).
 */
public final class VillagerGearLayer extends RenderLayer<Villager, VillagerModel<Villager>> {
    private final HumanoidModel<Villager> biped;
    private final VillagerArmorModel inner;
    private final VillagerArmorModel outer;
    private final ArmorLayer armor;
    private final OffArmLayer offArm;

    public VillagerGearLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent, EntityRendererProvider.Context ctx) {
        super(parent);
        biped = new HumanoidModel<>(LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0f), 64, 32).bakeRoot());
        RenderLayerParent<Villager, HumanoidModel<Villager>> proxy = new RenderLayerParent<>() {
            @Override
            public HumanoidModel<Villager> getModel() {
                return biped;
            }

            @Override
            public ResourceLocation getTextureLocation(Villager v) {
                return parent.getTextureLocation(v);
            }
        };
        inner = VillagerArmorModel.create(0.5f);
        outer = VillagerArmorModel.create(1.0f);
        armor = new ArmorLayer(proxy, inner, outer, ctx);
        offArm = new OffArmLayer(proxy, ctx);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, Villager v, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (v.isInvisible()) return;
        poseBiped(v, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
        inner.limbs(limbSwing, limbSwingAmount);
        outer.limbs(limbSwing, limbSwingAmount);
        armor.render(pose, buffers, light, v, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
        offArm.renderOffArm(pose, buffers, light, v);
    }

    private void poseBiped(Villager v, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        HumanoidArm mainArm = v.getMainArm();
        HumanoidModel.ArmPose mainPose = v.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        HumanoidModel.ArmPose offPose = v.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        if (v.isUsingItem()) {
            ItemStack using = v.getUseItem();
            boolean mainHand = v.getUsedItemHand() == InteractionHand.MAIN_HAND;
            if (using.canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
                if (mainHand) mainPose = HumanoidModel.ArmPose.BLOCK;
                else offPose = HumanoidModel.ArmPose.BLOCK;
            } else if (using.is(Items.BOW)) {
                mainPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
        }
        biped.rightArmPose = mainArm == HumanoidArm.RIGHT ? mainPose : offPose;
        biped.leftArmPose = mainArm == HumanoidArm.RIGHT ? offPose : mainPose;
        biped.attackTime = v.getAttackAnim(partialTick);
        biped.riding = v.isPassenger();
        biped.young = v.isBaby();
        biped.crouching = false;
        biped.prepareMobModel(v, limbSwing, limbSwingAmount, partialTick);
        biped.setupAnim(v, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
    }

    /** Vanilla armor rendering, re-posing the villager armor model after the biped pose is copied. */
    private static final class ArmorLayer extends HumanoidArmorLayer<Villager, HumanoidModel<Villager>, VillagerArmorModel> {
        ArmorLayer(RenderLayerParent<Villager, HumanoidModel<Villager>> parent, VillagerArmorModel inner, VillagerArmorModel outer,
                   EntityRendererProvider.Context ctx) {
            super(parent, inner, outer, ctx.getModelManager());
        }

        @Override
        protected net.minecraft.client.model.Model getArmorModelHook(Villager entity, ItemStack stack, EquipmentSlot slot, VillagerArmorModel model) {
            model.repose();
            return super.getArmorModelHook(entity, stack, slot, model);
        }
    }

    /** Held-item layer that draws only the arm opposite the main arm (vanilla draws the main hand crossed). */
    private static final class OffArmLayer extends ItemInHandLayer<Villager, HumanoidModel<Villager>> {
        OffArmLayer(RenderLayerParent<Villager, HumanoidModel<Villager>> parent, EntityRendererProvider.Context ctx) {
            super(parent, ctx.getItemInHandRenderer());
        }

        void renderOffArm(PoseStack pose, MultiBufferSource buffers, int light, Villager v) {
            ItemStack stack = v.getOffhandItem();
            if (stack.isEmpty()) return;
            HumanoidArm arm = v.getMainArm().getOpposite();
            pose.pushPose();
            if (getParentModel().young) {
                pose.translate(0f, 0.75f, 0f);
                pose.scale(0.5f, 0.5f, 0.5f);
            }
            renderArmWithItem(v, stack, arm == HumanoidArm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    arm, pose, buffers, light);
            pose.popPose();
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, Villager v, float a, float b, float c, float d, float e, float f) {
            renderOffArm(pose, buffers, light, v);
        }
    }
}
