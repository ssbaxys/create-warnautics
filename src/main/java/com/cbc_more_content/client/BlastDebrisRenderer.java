package com.cbc_more_content.client;

import com.cbc_more_content.entity.BlastDebrisEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Cached fractured prisms: rock chips, long wood splinters and thin metal plates. */
public class BlastDebrisRenderer extends EntityRenderer<BlastDebrisEntity> {
    private final Map<BlastDebrisEntity, Mesh> meshes = new WeakHashMap<>();

    public BlastDebrisRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = .12f;
    }

    @Override
    public ResourceLocation getTextureLocation(BlastDebrisEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(
            BlastDebrisEntity entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        if (entity.blockState().isAir()) {
            return;
        }
        float melt = entity.meltProgress(partial);
        if (melt >= .995f) {
            return;
        }
        var shape = entity.shape();
        var mesh = meshes.get(entity);
        if (mesh == null || mesh.shape != shape) {
            mesh = mesh(shape);
            meshes.put(entity, mesh);
        }
        var mc = Minecraft.getInstance();
        var sprite = mc.getBlockRenderer().getBlockModel(entity.blockState()).getParticleIcon();
        float easing = melt * melt * (3 - 2 * melt);
        float shrink = 1 - easing;
        float age = entity.spinAge(partial);
        var rotation = new Quaternionf()
                .rotationXYZ(
                        shape.spinX() * age * Mth.DEG_TO_RAD,
                        (shape.heading() + shape.spinY() * age) * Mth.DEG_TO_RAD,
                        shape.spinZ() * age * Mth.DEG_TO_RAD);
        float settling = Mth.clamp(entity.restingAge(partial) / 8, 0, 1);
        rotation.slerp(
                new Quaternionf().rotationY((shape.heading() + shape.spinY() * age) * Mth.DEG_TO_RAD),
                settling * settling * (3 - 2 * settling));
        pose.pushPose();
        pose.translate(0, -shape.height() * easing * .12, 0);
        pose.scale(shrink, shrink * shrink, shrink);
        pose.translate(0, shape.height() * .5, 0);
        pose.mulPose(rotation);
        pose.translate(0, -shape.height() * .5, 0);
        var consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        var transform = pose.last();
        for (Face face : mesh.faces) {
            for (int k = 0; k < 4; k++) {
                float[] vertex = mesh.vertices[face.indices[k]];
                float u = mesh.u + (k == 0 || k == 3 ? 0 : mesh.span);
                float v = mesh.v + (k < 2 ? 0 : mesh.span);
                consumer.addVertex(transform, vertex[0], vertex[1], vertex[2])
                        .setColor(face.shade, face.shade, face.shade, 1)
                        .setUv(sprite.getU(u), sprite.getV(v))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(transform, face.normal.x, face.normal.y, face.normal.z);
            }
        }
        pose.popPose();
        // Avoid a full-size vanilla entity shadow remaining after the mesh has melted.
    }

    private static Mesh mesh(BlastDebrisEntity.Shape shape) {
        var random = RandomSource.create(shape.seed());
        float[][] vertices = new float[14][3];
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3;
            float radius = .8f + random.nextFloat() * .2f;
            float x = (float) Math.cos(angle) * shape.width() * .5f;
            float z = (float) Math.sin(angle) * shape.depth() * .5f;
            vertices[i] = new float[] {x * radius, 0, z * radius};
            float top = .66f + random.nextFloat() * .34f;
            vertices[i + 6] = new float[] {x * top, shape.height() * (.7f + random.nextFloat() * .3f), z * top};
        }
        vertices[12] = new float[] {0, shape.height(), 0};
        vertices[13] = new float[] {0, 0, 0};
        Face[] faces = new Face[18];
        for (int i = 0; i < 6; i++) {
            int next = (i + 1) % 6;
            faces[i * 3] = face(vertices, new int[] {i, i + 6, next + 6, next}, .88f + random.nextFloat() * .12f);
            faces[i * 3 + 1] = face(vertices, new int[] {12, next + 6, i + 6, i + 6}, 1);
            faces[i * 3 + 2] = face(vertices, new int[] {13, i, next, next}, .82f);
        }
        float span = .25f + random.nextFloat() * .35f;
        return new Mesh(shape, vertices, faces, random.nextFloat() * (1 - span), random.nextFloat() * (1 - span), span);
    }

    private static Face face(float[][] vertices, int[] indices, float shade) {
        var a = new Vector3f(vertices[indices[0]]);
        var normal = new Vector3f(vertices[indices[1]])
                .sub(a)
                .cross(new Vector3f(vertices[indices[2]]).sub(a))
                .normalize();
        return new Face(indices, normal, shade);
    }

    private record Face(int[] indices, Vector3f normal, float shade) {}

    private record Mesh(
            BlastDebrisEntity.Shape shape, float[][] vertices, Face[] faces, float u, float v, float span) {}
}
