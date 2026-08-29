package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.entity.TripwireEntity;
import com.cbc_more_content.item.TripwireCoilItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Shows where a half-strung wire would end up.
 * <p>
 * Green while the run is legal, red when it is not — which is the only way to find out
 * that a post is too far off without walking there and being told no.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class TripwirePreviewClient {
    private static final int SEGMENTS = 16;
    private static final double SAG = 0.09D;

    private TripwirePreviewClient() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        BlockPos first = pendingPost(player);
        if (first == null) {
            return;
        }
        BlockPos second = lookingAt(mc);
        if (second == null || second.equals(first)) {
            return;
        }

        boolean legal = TripwireEntity.canAnchor(mc.level.getBlockState(second))
                && TripwireEntity.canAnchor(mc.level.getBlockState(first))
                && Math.sqrt(first.distSqr(second)) <= TripwireEntity.MAX_SPAN;

        Vec3 camera = event.getCamera().getPosition();
        Vec3 a = TripwireEntity.tie(first);
        Vec3 b = TripwireEntity.tie(second);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float r = legal ? 0.28f : 0.95f;
        float g = legal ? 0.92f : 0.24f;
        float bl = legal ? 0.36f : 0.20f;

        Vec3 previous = sagged(a, b, 0.0f);
        for (int i = 1; i <= SEGMENTS; i++) {
            Vec3 next = sagged(a, b, i / (float) SEGMENTS);
            edge(lines, pose, previous, next, r, g, bl);
            previous = next;
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** The same slack the strung wire hangs with, so the preview does not flatter it. */
    private static Vec3 sagged(Vec3 a, Vec3 b, float t) {
        return a.add(b.subtract(a).scale(t)).add(0.0D, -SAG * 4.0D * t * (1.0D - t), 0.0D);
    }

    @Nullable
    private static BlockPos pendingPost(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return stack.getItem() instanceof TripwireCoilItem ? TripwireCoilItem.pendingPost(stack) : null;
    }

    @Nullable
    private static BlockPos lookingAt(Minecraft mc) {
        return mc.hitResult instanceof BlockHitResult hit && mc.hitResult.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos()
                : null;
    }

    private static void edge(VertexConsumer lines, PoseStack pose, Vec3 from, Vec3 to, float r, float g, float b) {
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float length = net.minecraft.util.Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-5F) {
            return;
        }
        lines.addVertex(pose.last(), (float) from.x, (float) from.y, (float) from.z)
                .setColor(r, g, b, 0.55f)
                .setNormal(pose.last(), dx / length, dy / length, dz / length);
        lines.addVertex(pose.last(), (float) to.x, (float) to.y, (float) to.z)
                .setColor(r, g, b, 0.55f)
                .setNormal(pose.last(), dx / length, dy / length, dz / length);
    }
}
