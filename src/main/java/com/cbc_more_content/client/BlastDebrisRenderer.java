package com.cbc_more_content.client;

import com.cbc_more_content.entity.BlastDebrisEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a debris chunk as a cut-down cube wearing the broken block's own particle
 * texture, tumbling in flight and sinking away once it has landed.
 * <p>
 * The particle sprite is what vanilla's own dig particles sample, so this reads as "a
 * piece of that block" for anything with a texture at all, without needing to interpret
 * the block's real model.
 */
public class BlastDebrisRenderer extends EntityRenderer<BlastDebrisEntity> {
    public BlastDebrisRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.15f;
    }

    @Override
    public ResourceLocation getTextureLocation(BlastDebrisEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(
            BlastDebrisEntity entity, float yaw, float partialTick,
            PoseStack pose, MultiBufferSource buffers, int packedLight) {
        BlockState state = entity.blockState();
        if (state.isAir()) {
            return;
        }

        float melt = entity.meltProgress(partialTick);
        if (melt >= 1.0f) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        TextureAtlasSprite sprite = model.getParticleIcon();

        float[] box = entity.cutBox();
        // Melting reads as sinking and shrinking into the ground it landed on, not as
        // shrinking toward mid-air — the same way an ice cube goes.
        float shrink = 1.0f - melt * melt;
        float sinkY = box[4] * melt;

        pose.pushPose();
        pose.translate(0.0D, sinkY, 0.0D);

        if (!entity.isSettled()) {
            float age = entity.tickCount + partialTick;
            float[] spin = entity.spinDegreesPerTick();
            pose.translate(0.0D, 0.2D, 0.0D);
            pose.mulPose(Axis.XP.rotationDegrees(spin[0] * age));
            pose.mulPose(Axis.YP.rotationDegrees(spin[1] * age));
            pose.mulPose(Axis.ZP.rotationDegrees(spin[2] * age));
            pose.translate(0.0D, -0.2D, 0.0D);
        }

        pose.translate(-0.2D, 0.0D, -0.2D);
        pose.scale(shrink, shrink, shrink);

        VertexConsumer consumer = buffers.getBuffer(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));
        PoseStack.Pose last = pose.last();
        int light = melt > 0.0f
                ? packedLight
                : net.minecraft.client.renderer.LevelRenderer.getLightColor(
                        entity.level(), entity.blockPosition());

        cuboid(consumer, last, box, sprite, light);
        pose.popPose();

        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    private static void cuboid(
            VertexConsumer consumer, PoseStack.Pose pose, float[] box,
            TextureAtlasSprite sprite, int light) {
        float x0 = box[0];
        float y0 = box[1];
        float z0 = box[2];
        float x1 = box[3];
        float y1 = box[4];
        float z1 = box[5];

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        // -X / +X
        quad(consumer, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, u0, v0, u1, v1, light, -1, 0, 0);
        quad(consumer, pose, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, u0, v0, u1, v1, light, 1, 0, 0);
        // -Y / +Y
        quad(consumer, pose, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, u0, v0, u1, v1, light, 0, -1, 0);
        quad(consumer, pose, x0, y1, z1, x0, y1, z0, x1, y1, z0, x1, y1, z1, u0, v0, u1, v1, light, 0, 1, 0);
        // -Z / +Z
        quad(consumer, pose, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0, u0, v0, u1, v1, light, 0, 0, -1);
        quad(consumer, pose, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, u0, v0, u1, v1, light, 0, 0, 1);
    }

    private static void quad(
            VertexConsumer consumer, PoseStack.Pose pose,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4,
            float u0, float v0, float u1, float v1, int light,
            float nx, float ny, float nz) {
        vertex(consumer, pose, x1, y1, z1, u0, v0, light, nx, ny, nz);
        vertex(consumer, pose, x2, y2, z2, u0, v1, light, nx, ny, nz);
        vertex(consumer, pose, x3, y3, z3, u1, v1, light, nx, ny, nz);
        vertex(consumer, pose, x4, y4, z4, u1, v0, light, nx, ny, nz);
    }

    private static void vertex(
            VertexConsumer consumer, PoseStack.Pose pose,
            float x, float y, float z, float u, float v, int light,
            float nx, float ny, float nz) {
        consumer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
