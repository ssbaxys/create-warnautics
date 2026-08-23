package com.cbc_more_content.client;

import com.cbc_more_content.munitions.C4Projectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws a charge in the air as the solid model rather than a camera-facing item.
 * <p>
 * The charge is turned so its underside points back down the flight path — you watch the
 * flat bottom of the brick recede as you throw it — and it holds that pose the whole way
 * instead of tumbling, because the orientation comes from the launch heading rather than
 * from the current velocity.
 */
@OnlyIn(Dist.CLIENT)
public class C4ProjectileRenderer extends EntityRenderer<C4Projectile> {
    /** Model centre, from the authored geometry: x 0–14, y 0–9, z 1–13. */
    private static final float CENTRE_X = 7.0f / 16.0f;
    private static final float CENTRE_Y = 4.5f / 16.0f;
    private static final float CENTRE_Z = 7.0f / 16.0f;
    private static final float SCALE = 0.75f;

    public C4ProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            C4Projectile entity,
            float entityYaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        pose.pushPose();

        // Same pair of turns a thrown trident uses to point a Y-aligned model along its
        // heading; here it puts the charge's top forward, so its base faces the thrower.
        pose.mulPose(Axis.YP.rotationDegrees(
                Mth.lerp(partialTick, entity.yRotO, entity.getYRot()) - 90.0f));
        pose.mulPose(Axis.ZP.rotationDegrees(
                Mth.lerp(partialTick, entity.xRotO, entity.getXRot()) + 90.0f));

        pose.scale(SCALE, SCALE, SCALE);
        pose.translate(-CENTRE_X, -CENTRE_Y, -CENTRE_Z);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity.getItem(),
                ItemDisplayContext.NONE,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                pose,
                buffers,
                entity.level(),
                entity.getId());

        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(C4Projectile entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
