package com.cbc_more_content;

import com.cbc_more_content.client.veil.VeilMissilePlume;
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
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** Real Veil compiler + GPU: all view angles, animation, depth occlusion and motor cutoff. */
final class MissilePlumePixelCheck {
    static void run() throws Exception {
        checkNozzlesAndLights();
        if (!VeilMissilePlume.available()) {
            throw new AssertionError("Veil plume shader did not compile");
        }
        var mc = Minecraft.getInstance();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.identity();
        RenderSystem.applyModelViewMatrix();
        var target = new TextureTarget(512, 512, true, Minecraft.ON_OSX);
        long previousHash = 0;
        try {
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().perspective((float) Math.toRadians(55), 1, .05f, 500),
                    VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(140);
            RenderSystem.setShaderFogEnd(160);
            Vector3f[] eyes = {
                new Vector3f(2, 1.4f, 9),
                new Vector3f(2, 1.4f, -9),
                new Vector3f(9, 0, 0),
                new Vector3f(-7, 0, 0),
                new Vector3f(2, 9, 0),
                new Vector3f(2, -9, 0),
                new Vector3f(2, 0, 0)
            };
            for (int i = 0; i < 17; i++) {
                boolean blocked = i == 8, dead = i == 9, secondFrame = i == 7;
                var eye = i >= 15
                        ? new Vector3f(480, 0, 0)
                        : i >= 11
                                ? new Vector3f(i == 11 ? 48 : 210, 0, 0)
                                : i == 10 ? new Vector3f(.6f, 2, 10) : eyes[i < 7 ? i : 0];
                var aim = i == 10 ? new Vector3f(.6f, 0, 0) : i == 6 ? new Vector3f(4, 0, 0) : new Vector3f(2, 0, 0);
                var up = i == 4 || i == 5 ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
                var matrix = new Matrix4f().lookAt(eye, aim, up);
                if (i == 16) {
                    // A spyglass magnifies the same distant source using the current projection.
                    RenderSystem.setProjectionMatrix(
                            new Matrix4f().perspective((float) Math.toRadians(10), 1, .05f, 1024),
                            VertexSorting.DISTANCE_TO_ORIGIN);
                }
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                if (blocked) {
                    GL11.glClearDepth(0);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glClearDepth(1);
                }
                if (i == 10) {
                    // Put the authored airframe ahead of the nozzle for a reviewable preview.
                    view.set(matrix);
                    RenderSystem.applyModelViewMatrix();
                    Lighting.setupFor3DItems();
                    var pose = new PoseStack();
                    pose.translate(-2.01, -.5, -.5);
                    var state = ModBlocks.CRUISE_MISSILE.get().defaultBlockState();
                    var buffers = mc.renderBuffers().bufferSource();
                    mc.getBlockRenderer()
                            .getModelRenderer()
                            .renderModel(
                                    pose.last(),
                                    buffers.getBuffer(RenderType.cutout()),
                                    state,
                                    mc.getBlockRenderer().getBlockModel(state),
                                    1,
                                    1,
                                    1,
                                    LightTexture.FULL_BRIGHT,
                                    OverlayTexture.NO_OVERLAY);
                    buffers.endBatch(RenderType.cutout());
                    view.identity();
                    RenderSystem.applyModelViewMatrix();
                    RenderSystem.enableDepthTest();
                }
                var worldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
                if (i == 13) {
                    // Another render pass has installed UI projection and near fog globals.
                    RenderSystem.setProjectionMatrix(
                            new Matrix4f().ortho(0, 512, 512, 0, -1, 1), VertexSorting.ORTHOGRAPHIC_Z);
                    RenderSystem.setShaderFogStart(0);
                    RenderSystem.setShaderFogEnd(1);
                }
                VeilMissilePlume.render(
                        matrix,
                        worldProjection,
                        secondFrame ? 2.2f : 1.3f,
                        dead ? 0 : 1,
                        i >= 15 ? 8 : i == 5 || i >= 11 ? 36 : 12,
                        i == 14 ? Float.MAX_VALUE : i >= 15 ? 500 : i >= 11 ? 240 : 140,
                        i == 14 ? Float.MAX_VALUE : i >= 15 ? 512 : i >= 11 ? 256 : 160);
                if (i == 13) {
                    RenderSystem.setProjectionMatrix(worldProjection, VertexSorting.DISTANCE_TO_ORIGIN);
                    RenderSystem.setShaderFogStart(140);
                    RenderSystem.setShaderFogEnd(160);
                }
                if (!RenderSystem.getModelViewMatrix().equals(new Matrix4f(), .00001f)
                        || !GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                        || !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)) {
                    throw new AssertionError("Plume leaked GL view/depth state");
                }
                target.bindRead();
                try (var pixels = new NativeImage(512, 512, false)) {
                    pixels.downloadTexture(0, false);
                    pixels.flipY();
                    pixels.writeToFile(Path.of("missile-plume-" + i + ".png"));
                    int count = 0;
                    long hash = 1;
                    for (int y = 0; y < 512; y++) {
                        for (int x = 0; x < 512; x++) {
                            int rgb = pixels.getPixelRGBA(x, y) & 0xFFFFFF;
                            if (rgb != 0) {
                                count++;
                            }
                            hash = hash * 31 + rgb;
                        }
                    }
                    if (blocked || dead) {
                        if (count != 0) {
                            throw new AssertionError("Plume visible through solid depth or with dead motor: " + i);
                        }
                    } else if (count < (i >= 11 ? 1 : 50)) {
                        throw new AssertionError("Invisible plume at view " + i + ": " + count);
                    }
                    if (i == 0) {
                        previousHash = hash;
                    }
                    if (secondFrame && hash == previousHash) {
                        throw new AssertionError("Plume animation is frozen");
                    }
                    System.out.println("MISSILE_PLUME_GPU view=" + i + " pixels=" + count);
                }
            }
        } finally {
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void checkNozzlesAndLights() throws Exception {
        var missiles = new java.util.ArrayList<com.cbc_more_content.munitions.CruiseMissileProjectile>();
        var mapField = com.cbc_more_content.client.veil.VeilMissileFx.class.getDeclaredField("LIGHTS");
        mapField.setAccessible(true);
        var lights = (java.util.Map<?, ?>) mapField.get(null);
        try {
            for (int i = 0; i < 6; i++) {
                var missile = new com.cbc_more_content.munitions.CruiseMissileProjectile(
                        com.cbc_more_content.registry.ModEntityTypes.CRUISE_MISSILE.get(), null);
                missiles.add(missile);
                missile.setPos(100 + i * 3, 64, 100);
                missile.xo = missile.getX();
                missile.yo = missile.getY();
                missile.zo = missile.getZ();
                missile.xOld = missile.getX() - 1;
                missile.yOld = missile.getY() + .5;
                missile.zOld = missile.getZ() - .25;
                missile.yRotO = -175 + i * 60;
                missile.setYRot(missile.yRotO + 15);
                missile.xRotO = -80 + i * 30;
                missile.setXRot(missile.xRotO + 10);
                var nozzle = com.cbc_more_content.client.MissileExhaustLights.nozzleOf(missile, .5f);
                var rotation = new Matrix4f()
                        .rotateY((90 - (missile.yRotO + 7.5f)) * (float) Math.PI / 180)
                        .rotateZ((missile.xRotO + 5) * (float) Math.PI / 180);
                var offset = rotation.transformPosition(new Vector3f(1.51f, 0, 0));
                var expected = missile.position().add(-.5, .25, -.125).add(offset.x, offset.y, offset.z);
                if (nozzle.distanceTo(expected) > .001) {
                    throw new AssertionError("Exhaust detached from the rotated/interpolated airframe");
                }
                com.cbc_more_content.client.veil.VeilMissileFx.follow(missile, nozzle, 1);
            }
            if (lights.size() != 6) {
                throw new AssertionError("Multiple missile lights evict one another: " + lights.size());
            }
            com.cbc_more_content.client.veil.VeilMissileFx.tick();
            if (lights.size() != 6) {
                throw new AssertionError("Lights expire before the next tick");
            }
            com.cbc_more_content.client.veil.VeilMissileFx.tick();
            com.cbc_more_content.client.veil.VeilMissileFx.tick();
            if (!lights.isEmpty()) {
                throw new AssertionError("Untracked missile lights leaked");
            }
            for (var missile : missiles) {
                com.cbc_more_content.client.veil.VeilMissileFx.follow(missile, missile.position(), 1);
            }
            com.cbc_more_content.client.veil.VeilMissileFx.clear();
            if (!lights.isEmpty()) {
                throw new AssertionError("World unload leaves stale missile lights");
            }
        } finally {
            com.cbc_more_content.client.veil.VeilMissileFx.clear();
        }
    }
}
