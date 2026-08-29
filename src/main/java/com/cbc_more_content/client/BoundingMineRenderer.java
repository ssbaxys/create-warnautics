package com.cbc_more_content.client;

import com.cbc_more_content.entity.BoundingMineEntity;
import com.cbc_more_content.registry.ModBlocks;
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
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the charge in flight using the same model it had in the ground, so the jump reads
 * as the mine leaving its hole rather than as one object being swapped for another.
 */
@OnlyIn(Dist.CLIENT)
public class BoundingMineRenderer extends EntityRenderer<BoundingMineEntity> {
    /** A slow tumble off the propelling charge — enough to see it turn, not to spin it. */
    private static final float SPIN_PER_TICK = 7.0f;

    public BoundingMineRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.2f;
    }

    @Override
    public ResourceLocation getTextureLocation(BoundingMineEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(
            BoundingMineEntity entity,
            float yaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        pose.pushPose();
        pose.translate(0.0D, 0.1D, 0.0D);
        pose.mulPose(Axis.YP.rotationDegrees((entity.tickCount + partialTick) * SPIN_PER_TICK));
        // The model is authored standing in a block; centre it on the entity instead.
        pose.translate(-0.5D, 0.0D, -0.5D);

        Minecraft mc = Minecraft.getInstance();
        BlockState state = ModBlocks.BOUNDING_MINE.get().defaultBlockState();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());
        mc.getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(), consumer, state, model, 1.0f, 1.0f, 1.0f, packedLight, OverlayTexture.NO_OVERLAY);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }
}
