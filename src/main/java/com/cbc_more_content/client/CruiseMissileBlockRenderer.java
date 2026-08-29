package com.cbc_more_content.client;

import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws a missile standing on end.
 * <p>
 * A blockstate can only turn a model about x and y, and neither lifts an X-aligned
 * airframe upright, so the vertical case used to ship as a second model with its
 * coordinates swapped by hand — which left the texture on its flanks rotated a quarter
 * turn. Rotating the flat model on the pose stack is a real rotation and gets every face
 * right for free.
 */
@OnlyIn(Dist.CLIENT)
public class CruiseMissileBlockRenderer implements BlockEntityRenderer<CruiseMissileBlockEntity> {
    public CruiseMissileBlockRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
            CruiseMissileBlockEntity missile,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        Direction facing = missile.getBlockState().getValue(CruiseMissileBlock.FACING);
        if (facing.getAxis().isHorizontal()) {
            // The baked variants already handle these, and they batch.
            return;
        }

        pose.pushPose();
        pose.translate(0.5D, 0.5D, 0.5D);
        // The model is authored nose-along-negative-X; a quarter turn about Z stands it up.
        pose.mulPose(Axis.ZP.rotationDegrees(facing == Direction.UP ? -90.0f : 90.0f));
        pose.translate(-0.5D, -0.5D, -0.5D);

        Minecraft mc = Minecraft.getInstance();
        BlockState flat = ModBlocks.CRUISE_MISSILE.get().defaultBlockState();
        BakedModel model = mc.getBlockRenderer().getBlockModel(flat);
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());
        mc.getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(), consumer, flat, model, 1.0f, 1.0f, 1.0f, packedLight, OverlayTexture.NO_OVERLAY);

        pose.popPose();
    }

    /** The airframe reaches a cell either side, so it must not vanish at the edge of view. */
    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public boolean shouldRenderOffScreen(CruiseMissileBlockEntity missile) {
        return true;
    }
}
