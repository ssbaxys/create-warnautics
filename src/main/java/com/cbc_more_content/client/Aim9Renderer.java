package com.cbc_more_content.client;

import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class Aim9Renderer extends EntityRenderer<Aim9Projectile> {
    public Aim9Renderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            Aim9Projectile entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        // Supplied AIM-9 model points north (-Z), unlike the cruise airframe's -X axis.
        pose.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partial, entity.yRotO, entity.getYRot())));
        pose.mulPose(Axis.XP.rotationDegrees(-Mth.lerp(partial, entity.xRotO, entity.getXRot())));
        pose.translate(-.5, -.5, -.5);
        var renderer = Minecraft.getInstance().getBlockRenderer();
        var state = ModBlocks.AIM9.get().defaultBlockState();
        renderer.getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(RenderType.cutout()),
                        state,
                        renderer.getBlockModel(state),
                        1,
                        1,
                        1,
                        light,
                        OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(entity, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(Aim9Projectile entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
