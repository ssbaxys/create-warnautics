package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.RocketProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** An S-8 rocket in flight: the standalone model, nose along its velocity. */
@OnlyIn(Dist.CLIENT)
public class RocketRenderer extends EntityRenderer<RocketProjectile> {
    public static final ModelResourceLocation MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "entity/s8_rocket"));

    public RocketRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            RocketProjectile entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        // The model points north (-Z), as the AIM-9's does.
        pose.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partial, entity.yRotO, entity.getYRot())));
        pose.mulPose(Axis.XP.rotationDegrees(-Mth.lerp(partial, entity.xRotO, entity.getXRot())));
        pose.translate(-.5, -.5, -.5);
        var mc = Minecraft.getInstance();
        var model = mc.getModelManager().getModel(MODEL);
        mc.getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(RenderType.cutout()),
                        ModBlocks.ROCKET_POD.get().defaultBlockState(),
                        model,
                        1,
                        1,
                        1,
                        entity.isBurning() ? 0xF000F0 : light,
                        OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(entity, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(RocketProjectile entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
