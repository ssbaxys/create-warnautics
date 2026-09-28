package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.client.Aim9BlockRenderer;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/** Offscreen previews use the actual block renderer, with only its world-liveness guard substituted. */
final class Aim9ModelPixelCheck {
    static void run() throws Exception {
        var minecraft = Minecraft.getInstance();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.identity().rotateX(.35f).rotateY(-.7f);
        RenderSystem.applyModelViewMatrix();
        var target = new TextureTarget(512, 512, true, Minecraft.ON_OSX);
        try {
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().ortho(-1.8f, 1.8f, -1.8f, 1.8f, -10, 10), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            Lighting.setupFor3DItems();
            for (var facing : Direction.values()) {
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                var state = ModBlocks.AIM9.get().defaultBlockState().setValue(Aim9Block.FACING, facing);
                Aim9BlockEntity be = new Aim9BlockEntity(BlockPos.ZERO, state) {
                    @Override
                    public boolean isLiveAirframe() {
                        return true;
                    }
                };
                var renderer = (Aim9BlockRenderer)
                        minecraft.getBlockEntityRenderDispatcher().getRenderer(be);
                var pose = new PoseStack();
                pose.translate(-.5, -.5, -.5);
                var buffers = minecraft.renderBuffers().bufferSource();
                renderer.render(be, 0, pose, buffers, LightTexture.FULL_BRIGHT, 0);
                buffers.endBatch(RenderType.cutout());
                target.bindRead();
                try (var image = new NativeImage(512, 512, false)) {
                    image.downloadTexture(0, false);
                    image.flipY();
                    image.writeToFile(Path.of("aim9-" + facing.getSerializedName() + ".png"));
                    int count = 0;
                    for (int y = 0; y < 512; y++) {
                        for (int x = 0; x < 512; x++) {
                            if ((image.getPixelRGBA(x, y) & 0xFFFFFF) != 0) {
                                count++;
                            }
                        }
                    }
                    if (count < 100) {
                        throw new AssertionError("AIM-9 renderer produces no visible model on " + facing);
                    }
                }
            }
        } finally {
            target.destroyBuffers();
            minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, sorting);
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
