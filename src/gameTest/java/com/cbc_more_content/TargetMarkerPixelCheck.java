package com.cbc_more_content;

import com.cbc_more_content.client.TargetLockClient;
import com.cbc_more_content.compat.SableTrackCompat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** Exercises the production marker draw on a real GPU after the world view has been popped. */
final class TargetMarkerPixelCheck {
    static void run() throws Exception {
        var draw = TargetLockClient.class.getDeclaredMethod(
                "renderMarkers", Matrix4f.class, Vec3.class, Vec3.class, Vec3.class, float.class);
        draw.setAccessible(true);
        var tracks = TargetLockClient.class.getDeclaredField("tracks");
        tracks.setAccessible(true);
        var previousTracks = tracks.get(null);
        var previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var previousSorting = RenderSystem.getVertexSorting();
        var matrix = RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.identity();
        RenderSystem.applyModelViewMatrix();
        var target = new TextureTarget(256, 256, true, Minecraft.ON_OSX);
        try {
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().perspective((float) Math.toRadians(70), 1, .05f, 500),
                    VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var camera = new Vec3(1250, 83, -940);
            int caseIndex = 0;
            for (float[] angles : new float[][] {{0, 0}, {90, 0}, {180, 0}, {270, 0}, {42, 35}, {215, -50}}) {
                var rotation = new Quaternionf()
                        .rotationYXZ((float) Math.toRadians(angles[0]), (float) Math.toRadians(angles[1]), 0);
                var forward = rotation.transform(new Vector3f(0, 0, -1));
                var right = rotation.transform(new Vector3f(1, 0, 0));
                var up = rotation.transform(new Vector3f(0, 1, 0));
                tracks.set(
                        null,
                        List.of(new SableTrackCompat.Track(
                                UUID.randomUUID(), camera.add(vec(forward).scale(80)), 6, .5)));
                target.setClearColor(0, 0, 0, 1);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                // An opaque hull already fills depth. The authorized marker must still be visible.
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                GL11.glClearDepth(0);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glClearDepth(1);
                draw.invoke(
                        null,
                        new Matrix4f().rotation(rotation.conjugate(new Quaternionf())),
                        camera,
                        vec(right),
                        vec(up),
                        0f);
                if (!RenderSystem.getModelViewMatrix().equals(new Matrix4f(), .00001f)) {
                    throw new AssertionError("Marker renderer leaked its camera matrix");
                }
                if (!GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
                    throw new AssertionError("Marker renderer did not restore depth testing");
                }
                target.bindRead();
                try (var pixels = new NativeImage(256, 256, false)) {
                    pixels.downloadTexture(0, false);
                    int count = 0;
                    int minX = 256, minY = 256, maxX = -1, maxY = -1;
                    for (int y = 0; y < 256; y++) {
                        for (int x = 0; x < 256; x++) {
                            if ((pixels.getPixelRGBA(x, y) & 0xFFFFFF) == 0) {
                                continue;
                            }
                            count++;
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }
                    }
                    pixels.writeToFile(Path.of("missile-marker-" + caseIndex++ + ".png"));
                    if (count < 24
                            || minX < 112
                            || maxX > 144
                            || minY < 112
                            || maxY > 144
                            || maxX - minX < 8
                            || maxY - minY < 8) {
                        throw new AssertionError("Marker missing or misplaced at yaw/pitch "
                                + angles[0] + "/" + angles[1] + ": pixels=" + count
                                + " bounds=" + minX + "," + minY + ".." + maxX + "," + maxY);
                    }
                }
            }
        } finally {
            tracks.set(null, previousTracks);
            target.destroyBuffers();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            matrix.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
