package com.cbc_more_content;

import com.cbc_more_content.client.veil.VeilMissileSmoke;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;

/** Exercises the real batched smoke shader, including wisps below vanilla's alpha cutoff. */
final class MissileSmokePixelCheck {
    static void run() throws Exception {
        if (!VeilMissileSmoke.available()) {
            throw new AssertionError("Soft smoke shader unavailable");
        }
        var mc = Minecraft.getInstance();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var previousShader = RenderSystem.getShader();
        float start = RenderSystem.getShaderFogStart(), end = RenderSystem.getShaderFogEnd();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.identity();
        RenderSystem.applyModelViewMatrix();
        var target = new TextureTarget(256, 256, true, Minecraft.ON_OSX);
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().ortho(-1, 1, -1, 1, -1, 1), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.setShaderFogStart(100);
            RenderSystem.setShaderFogEnd(200);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            for (int sample = 0; sample < 3; sample++) {
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                var buffer = VeilMissileSmoke.TYPE.begin(Tesselator.getInstance(), mc.getTextureManager());
                RenderSystem.disableDepthTest();
                RenderSystem.disableCull();
                float alpha = sample == 0 ? 1 : sample == 1 ? .08f : 0;
                vertex(buffer, -1, -1, 0, 0, alpha);
                vertex(buffer, 1, -1, 1, 0, alpha);
                vertex(buffer, 1, 1, 1, 1, alpha);
                vertex(buffer, -1, 1, 0, 1, alpha);
                BufferUploader.drawWithShader(buffer.buildOrThrow());
                target.bindRead();
                try (var pixels = new NativeImage(256, 256, false)) {
                    pixels.downloadTexture(0, false);
                    int count = 0;
                    java.util.Set<Integer> shades = new java.util.HashSet<>();
                    for (int rgb : pixels.getPixelsRGBA()) {
                        if ((rgb & 0xFFFFFF) != 0) {
                            count++;
                            shades.add(rgb & 0xFFFFFF);
                        }
                    }
                    if (sample < 2 && (count < 4000 || shades.size() < 4)) {
                        throw new AssertionError(
                                "Smoke lost soft volume: " + sample + " pixels=" + count + " shades=" + shades.size());
                    }
                    if (sample == 2 && count != 0) {
                        throw new AssertionError("Dead smoke remains visible");
                    }
                    pixels.flipY();
                    pixels.writeToFile(Path.of("soft-smoke-" + sample + ".png"));
                    System.out.println(
                            "MISSILE_SMOKE_GPU sample=" + sample + " pixels=" + count + " shades=" + shades.size());
                }
            }
        } finally {
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, sorting);
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    private static void vertex(
            com.mojang.blaze3d.vertex.VertexConsumer b, float x, float y, float u, float v, float alpha) {
        b.addVertex(x, y, 0).setUv(u, v).setColor(1, 1, 1, alpha).setLight(LightTexture.FULL_BRIGHT);
    }
}
