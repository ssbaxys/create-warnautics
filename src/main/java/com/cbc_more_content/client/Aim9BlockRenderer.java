package com.cbc_more_content.client;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;

public class Aim9BlockRenderer implements BlockEntityRenderer<Aim9BlockEntity> {
    public Aim9BlockRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
            Aim9BlockEntity missile,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        if (!missile.isLiveAirframe()) {
            return;
        }
        var facing = missile.getBlockState().getValue(Aim9Block.FACING);
        pose.pushPose();
        if (facing.getAxis().isVertical()) {
            pose.translate(.5, .5, .5);
            pose.mulPose(Axis.XP.rotationDegrees(facing == Direction.UP ? 90 : -90));
            pose.translate(-.5, -.5, -.5);
        }
        var state = facing.getAxis().isVertical() ? ModBlocks.AIM9.get().defaultBlockState() : missile.getBlockState();
        var renderer = Minecraft.getInstance().getBlockRenderer();
        renderer.getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(RenderType.cutout()),
                        state,
                        renderer.getBlockModel(state),
                        1,
                        1,
                        1,
                        packedLight,
                        OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(Aim9BlockEntity missile) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
