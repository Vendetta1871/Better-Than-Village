package net.betterthanvillage.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the bobber and its line for villager owners; the line starts at the villager's hand (02 §2.6). */
@Mixin(FishingHookRenderer.class)
public abstract class FishingHookRendererMixin extends EntityRenderer<FishingHook> {
    @Shadow
    @Final
    private static RenderType RENDER_TYPE;

    protected FishingHookRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    @Shadow
    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light, float x, int y, int u, int v) {
        throw new AssertionError();
    }

    @Shadow
    private static void stringVertex(float x, float y, float z, VertexConsumer consumer, PoseStack.Pose pose, float from, float to) {
        throw new AssertionError();
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void btv$renderVillagerBobber(FishingHook hook, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!(hook.getOwner() instanceof Villager villager)) {
            return;
        }
        poseStack.pushPose();
        poseStack.pushPose();
        poseStack.scale(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        PoseStack.Pose quad = poseStack.last();
        VertexConsumer consumer = buffers.getBuffer(RENDER_TYPE);
        vertex(consumer, quad, light, 0.0F, 0, 0, 1);
        vertex(consumer, quad, light, 1.0F, 0, 1, 1);
        vertex(consumer, quad, light, 1.0F, 1, 1, 0);
        vertex(consumer, quad, light, 0.0F, 1, 0, 0);
        poseStack.popPose();
        float yaw = Mth.lerp(partialTicks, villager.yBodyRotO, villager.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 hand = villager.getPosition(partialTicks).add(new Vec3(-0.25, 1.15, 0.45).yRot(-yaw));
        Vec3 line = hand.subtract(hook.getPosition(partialTicks).add(0.0, 0.25, 0.0));
        VertexConsumer lines = buffers.getBuffer(RenderType.lineStrip());
        PoseStack.Pose pose = poseStack.last();
        for (int j = 0; j <= 16; j++) {
            stringVertex((float) line.x, (float) line.y, (float) line.z, lines, pose, j / 16.0F, (j + 1) / 16.0F);
        }
        poseStack.popPose();
        super.render(hook, entityYaw, partialTicks, poseStack, buffers, light);
        ci.cancel();
    }
}
