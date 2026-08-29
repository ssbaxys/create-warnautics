package com.cbc_more_content.client;

import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the missile in flight using the same block model the placed airframe uses, so
 * the thing that leaves the rack is visibly the thing that was on it.
 */
@OnlyIn(Dist.CLIENT)
public class CruiseMissileRenderer extends EntityRenderer<CruiseMissileProjectile> {
    public CruiseMissileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            CruiseMissileProjectile entity,
            float yaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        pose.pushPose();

        // The synced rotation, not the velocity. Steering happens on the server only, so
        // the client copy of the delta movement never changes after the spawn packet —
        // reading it drew every missile frozen at its launch heading and dead level, no
        // matter where it was actually going.
        float yRot = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        float xRot = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        // The model is authored nose-along-negative-X. Yaw 0 faces south, so the nose
        // needs a quarter turn on top of the heading; without it the airframe flew
        // tail-first.
        pose.mulPose(Axis.YP.rotationDegrees(90.0f - yRot));
        pose.mulPose(Axis.ZP.rotationDegrees(xRot));
        pose.translate(-0.5D, -0.5D, -0.5D);

        var mc = net.minecraft.client.Minecraft.getInstance();
        BlockState state = ModBlocks.CRUISE_MISSILE.get().defaultBlockState();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());
        mc.getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(), consumer, state, model, 1.0f, 1.0f, 1.0f, packedLight, OverlayTexture.NO_OVERLAY);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CruiseMissileProjectile entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
