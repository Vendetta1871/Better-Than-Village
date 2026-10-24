package net.betterthanvillage.client;

import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.Villager;

/** Biped armor model re-posed to the villager shape (folded arms, deeper body, villager leg swing) (02 §2.6). */
public final class VillagerArmorModel extends HumanoidModel<Villager> {
    private float limbSwing;
    private float limbSwingAmount;

    private VillagerArmorModel(net.minecraft.client.model.geom.ModelPart root) {
        super(root);
    }

    /** Builds an unregistered armor model with the given dilation and villager arm cuboids. */
    public static VillagerArmorModel create(float dilation) {
        MeshDefinition mesh = HumanoidArmorModel.createBodyLayer(new CubeDeformation(dilation));
        PartDefinition root = mesh.getRoot();
        CubeDeformation arm = new CubeDeformation(0.35f);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-8f, -2f, -2f, 4f, 8f, 4f, arm), PartPose.ZERO);
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 16).mirror().addBox(4f, -2f, -2f, 4f, 8f, 4f, arm), PartPose.ZERO);
        return new VillagerArmorModel(LayerDefinition.create(mesh, 64, 32).bakeRoot());
    }

    /** Limb animation values used by the next {@link #repose()}. */
    public void limbs(float swing, float amount) {
        limbSwing = swing;
        limbSwingAmount = amount;
    }

    /** Applies the villager shape on top of the copied biped pose. */
    public void repose() {
        head.y = -2f;
        hat.y = -2f;
        body.zScale = 1.55f;
        rightLeg.x = -2f;
        leftLeg.x = 2f;
        if (riding) {
            // The biped riding pose, as the villager model's legs take it
            rightLeg.xRot = -1.4137167f;
            rightLeg.yRot = 0.31415927f;
            rightLeg.zRot = 0.07853982f;
            leftLeg.xRot = -1.4137167f;
            leftLeg.yRot = -0.31415927f;
            leftLeg.zRot = -0.07853982f;
        } else {
            rightLeg.xRot = Mth.cos(limbSwing * 0.6662f) * 0.7f * limbSwingAmount;
            leftLeg.xRot = Mth.cos(limbSwing * 0.6662f + (float) Math.PI) * 0.7f * limbSwingAmount;
        }
        for (var a : new net.minecraft.client.model.geom.ModelPart[]{rightArm, leftArm}) {
            a.x = 0f;
            a.y = 3f;
            a.z = -1f;
            a.xRot = -0.75f;
            a.yRot = 0f;
            a.zRot = 0f;
        }
    }
}
