package com.cbc_more_content.client;

import com.cbc_more_content.entity.TripwireEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The wire itself: one thin black run, sagging a little, and bowed out around whatever is
 * currently dragging it.
 * <p>
 * Built from real geometry rather than {@code RenderType.lines()}. A GL line is measured
 * in screen pixels, so it holds the same thickness however far away it is — a wire that
 * should be a hairline across a field instead stayed as fat as it looked underfoot. Two
 * crossed strips a hundredth of a block wide shrink with distance the way everything else
 * in the world does.
 */
@OnlyIn(Dist.CLIENT)
public class TripwireRenderer extends EntityRenderer<TripwireEntity> {
    /** Enough joints to read as a curve without turning a wire into geometry. */
    private static final int SEGMENTS = 16;
    /** Slack in the middle of an untouched run. */
    private static final double SAG = 0.09D;
    /** Half the wire's thickness, in blocks. */
    private static final float RADIUS = 0.012f;

    public TripwireRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public ResourceLocation getTextureLocation(TripwireEntity wire) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(
            TripwireEntity wire,
            float yaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        Vec3 origin = wire.position();
        Vec3 a = wire.endA().subtract(origin);
        Vec3 b = wire.endB().subtract(origin);
        if (a.distanceToSqr(b) < 1.0E-6D) {
            return;
        }
        Vec3 pull = wire.pull();
        float caughtAt = wire.caughtAt();

        VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());
        PoseStack.Pose last = pose.last();

        Vec3 previous = point(a, b, pull, caughtAt, 0.0f);
        for (int i = 1; i <= SEGMENTS; i++) {
            Vec3 next = point(a, b, pull, caughtAt, i / (float) SEGMENTS);
            strand(consumer, last, previous, next);
            previous = next;
        }

        super.render(wire, yaw, partialTick, pose, buffers, packedLight);
    }

    /** One length of wire, as a cross so it reads the same from every angle. */
    private static void strand(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 to) {
        Vec3 along = to.subtract(from);
        double length = along.length();
        if (length < 1.0E-5D) {
            return;
        }
        along = along.scale(1.0D / length);

        // Any perpendicular will do; the far one is picked so a vertical run does not
        // fall back on a cross product with itself.
        Vec3 reference = Math.abs(along.y) < 0.9D ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 side = along.cross(reference).normalize().scale(RADIUS);
        Vec3 up = along.cross(side).normalize().scale(RADIUS);

        face(consumer, pose, from, to, side);
        face(consumer, pose, from, to, up);
    }

    private static void face(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 to, Vec3 offset) {
        vertex(consumer, pose, from.subtract(offset));
        vertex(consumer, pose, from.add(offset));
        vertex(consumer, pose, to.add(offset));
        vertex(consumer, pose, to.subtract(offset));
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 at) {
        consumer.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(0.05f, 0.05f, 0.06f, 1.0f);
    }

    /**
     * A point on the run: the straight line, plus its own slack, plus a tent-shaped bow
     * that peaks wherever the wire is snagged and dies away toward the posts.
     */
    private static Vec3 point(Vec3 a, Vec3 b, Vec3 pull, float caughtAt, float t) {
        Vec3 base = a.add(b.subtract(a).scale(t));
        double sag = -SAG * 4.0D * t * (1.0D - t);
        if (pull.lengthSqr() < 1.0E-6D) {
            return base.add(0.0D, sag, 0.0D);
        }
        // Linear falloff either side of the snag, so the wire reads as pulled from one
        // point rather than uniformly bent.
        double reach = t <= caughtAt
                ? (caughtAt <= 0.0f ? 0.0D : t / caughtAt)
                : (caughtAt >= 1.0f ? 0.0D : (1.0f - t) / (1.0f - caughtAt));
        return base.add(0.0D, sag, 0.0D).add(pull.scale(net.minecraft.util.Mth.clamp(reach, 0.0D, 1.0D)));
    }
}
