package com.cbc_more_content.client;

import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.mojang.math.Axis;

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

        // Oriented from the velocity rather than the synced rotation. A missile launched
        // off a tilted Sable hull gets its heading from the transformed launch vector, so
        // the rotation fields can still read as level while it is climbing or diving;
        // the motion vector is the one thing that always matches the real flight path.
        Vec3 motion = entity.getDeltaMovement();
        float yRot;
        float xRot;
        if (motion.lengthSqr() > 1.0E-6D) {
            Vec3 dir = motion.normalize();
            yRot = (float) (Mth.atan2(dir.z, dir.x) * (180.0F / Math.PI)) - 90.0f;
            xRot = (float) (-Math.asin(Mth.clamp(dir.y, -1.0D, 1.0D)) * (180.0F / Math.PI));
        } else {
            yRot = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            xRot = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        }

        // The model is authored nose-along-negative-X, so it is turned to match the
        // heading and then shifted so its middle sits on the entity origin.
        pose.mulPose(Axis.YP.rotationDegrees(-yRot - 90.0f));
        pose.mulPose(Axis.ZP.rotationDegrees(xRot));
        pose.translate(-0.5D, -0.5D, -0.5D);

        var mc = net.minecraft.client.Minecraft.getInstance();
        BlockState state = ModBlocks.CRUISE_MISSILE.get().defaultBlockState();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());
        mc.getBlockRenderer().getModelRenderer().renderModel(
                pose.last(), consumer, state, model,
                1.0f, 1.0f, 1.0f, packedLight, OverlayTexture.NO_OVERLAY);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CruiseMissileProjectile entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
