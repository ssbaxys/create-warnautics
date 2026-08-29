package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.compat.SableTrackCompat;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.network.MissileFirePayload;
import com.cbc_more_content.registry.ModSounds;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * The designator, as a remote for a missile already set to remote guidance.
 * <p>
 * It only has anything to say about hulls that are under way — a parked plot is a
 * building, and a building can be hit with typed coordinates. Every hull that is moving
 * gets a marker on its centre; holding use on one winds a lock up over a couple of
 * seconds, and attack then sends the missile.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class TargetLockClient {
    /** How long the operator has to hold the aim before the lock takes. */
    private static final int LOCK_TICKS = 40;
    /** Beyond this a hull is too far to designate. */
    private static final double MAX_RANGE = 220.0D;
    /** Marker size on screen, as a share of the distance to it. */
    private static final double MARKER_SCALE = 0.03D;

    private static final int CONFIRM_TICKS = 40;

    private static List<SableTrackCompat.Track> tracks = List.of();

    @Nullable
    private static UUID aimed;

    @Nullable
    private static UUID locked;

    private static int progress;
    private static int confirm;
    private static boolean firePrimed;

    private TargetLockClient() {}

    /** The missile this designator is paired with, or null when it is not in hand. */
    @Nullable
    private static BlockPos boundMissile(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return stack.getItem() instanceof TargetDesignatorItem ? TargetDesignatorItem.boundMissile(stack) : null;
    }

    /** The designator is not a pickaxe, and attack is the trigger. */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getItemStack().getItem() instanceof TargetDesignatorItem) {
            event.setCanceled(true);
        }
    }

    /**
     * Holding use is how a lock is wound up, so it must not also be placing blocks.
     * Clicking the missile itself still has to get through — that is how the pair is made.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof TargetDesignatorItem)) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof CruiseMissileBlock)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        tracks = List.of();
        aimed = null;
        locked = null;
        progress = 0;
        confirm = 0;
        SableTrackCompat.forget();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (confirm > 0) {
            confirm--;
        }
        if (player == null || mc.level == null || !ModList.get().isLoaded("sable")) {
            tracks = List.of();
            return;
        }

        BlockPos missile = boundMissile(player);
        if (missile == null || mc.screen != null) {
            tracks = List.of();
            aimed = null;
            locked = null;
            progress = 0;
            return;
        }

        // Sampled once a tick, because that is what the speeds are differenced against.
        tracks = SableTrackCompat.sampleMoving(mc.level);

        SableTrackCompat.Track underCrosshair = aimedTrack(player);
        aimed = underCrosshair == null ? null : underCrosshair.id();

        // A hull that stops or unloads is no longer a target.
        if (locked != null && findTrack(locked) == null) {
            locked = null;
        }

        boolean holdingUse = mc.options.keyUse.isDown();
        if (locked == null && holdingUse && underCrosshair != null) {
            if (progress == 0) {
                player.playSound(ModSounds.C4_BUTTON.get(), 0.35f, 0.8f);
            }
            progress++;
            if (progress >= LOCK_TICKS) {
                locked = underCrosshair.id();
                progress = 0;
                confirm = CONFIRM_TICKS;
                player.playSound(ModSounds.C4_BUTTON.get(), 1.0f, 1.8f);
            }
        } else if (!holdingUse || underCrosshair == null) {
            progress = 0;
        }

        // Attack fires, once, on the press rather than for as long as it is held.
        boolean attack = mc.options.keyAttack.isDown();
        if (attack && !firePrimed && locked != null) {
            PacketDistributor.sendToServer(new MissileFirePayload(missile, locked));
            player.playSound(ModSounds.C4_BUTTON.get(), 1.0f, 1.2f);
            locked = null;
            confirm = 0;
        }
        firePrimed = attack;
    }

    /** The moving hull nearest the line of sight, if any is close enough to count. */
    @Nullable
    private static SableTrackCompat.Track aimedTrack(LocalPlayer player) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        SableTrackCompat.Track best = null;
        double bestScore = Double.MAX_VALUE;
        for (SableTrackCompat.Track track : tracks) {
            Vec3 toward = track.centre().subtract(eye);
            double along = toward.dot(look);
            if (along <= 0.0D || along > MAX_RANGE) {
                continue;
            }
            // Off-axis distance rather than an angle, so a big hull is as easy to hold
            // at range as a small one is up close.
            double off = toward.subtract(look.scale(along)).length();
            double tolerance = Math.max(3.0D, track.size() * 0.5D);
            double score = off / tolerance;
            if (score <= 1.0D && score < bestScore) {
                bestScore = score;
                best = track;
            }
        }
        return best;
    }

    @Nullable
    private static SableTrackCompat.Track findTrack(UUID id) {
        for (SableTrackCompat.Track track : tracks) {
            if (track.id().equals(id)) {
                return track;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || tracks.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || boundMissile(mc.player) == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f upVec = event.getCamera().getUpVector();
        Vec3 right = new Vec3(-left.x(), -left.y(), -left.z());
        Vec3 up = new Vec3(upVec.x(), upVec.y(), upVec.z());

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float time = mc.player.tickCount + event.getPartialTick().getGameTimeDeltaPartialTick(false);

        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        for (SableTrackCompat.Track track : tracks) {
            double distance = track.centre().distanceTo(camera);
            double size = Mth.clamp(distance * MARKER_SCALE, 0.5D, 6.0D);
            boolean isLocked = track.id().equals(locked);
            boolean isAimed = track.id().equals(aimed);

            float r;
            float g;
            float b;
            float a;
            if (isLocked) {
                r = 1.0f;
                g = 0.22f;
                b = 0.16f;
                a = 0.65f + 0.35f * Mth.abs(Mth.sin(time * 0.3f));
            } else if (isAimed) {
                r = 1.0f;
                g = 1.0f;
                b = 1.0f;
                a = 0.95f;
            } else if (track.fast()) {
                r = 1.0f;
                g = 0.78f;
                b = 0.28f;
                a = 0.6f;
            } else {
                r = 0.74f;
                g = 0.80f;
                b = 0.86f;
                a = 0.4f;
            }

            square(lines, pose, track.centre(), right, up, size, r, g, b, a);

            // The closing ring: an outer square that shrinks onto the marker as the lock
            // winds up, so progress is legible without looking away from the target.
            if (isAimed && progress > 0 && locked == null) {
                double t = progress / (double) LOCK_TICKS;
                square(
                        lines,
                        pose,
                        track.centre(),
                        right,
                        up,
                        size * (3.0D - 2.0D * t),
                        1.0f,
                        0.52f + 0.3f * (float) t,
                        0.18f,
                        0.85f);
            }
            if (isLocked) {
                square(lines, pose, track.centre(), right, up, size * 1.6D, r, g, b, a * 0.6f);
            }
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** A camera-facing square outline, so the marker reads the same from any angle. */
    private static void square(
            VertexConsumer lines,
            PoseStack pose,
            Vec3 centre,
            Vec3 right,
            Vec3 up,
            double size,
            float r,
            float g,
            float b,
            float a) {
        List<Vec3> corners = new ArrayList<>(4);
        corners.add(centre.add(right.scale(-size)).add(up.scale(-size)));
        corners.add(centre.add(right.scale(size)).add(up.scale(-size)));
        corners.add(centre.add(right.scale(size)).add(up.scale(size)));
        corners.add(centre.add(right.scale(-size)).add(up.scale(size)));
        for (int i = 0; i < 4; i++) {
            edge(lines, pose, corners.get(i), corners.get((i + 1) % 4), r, g, b, a);
        }
    }

    private static void edge(
            VertexConsumer lines, PoseStack pose, Vec3 from, Vec3 to, float r, float g, float b, float a) {
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-5F) {
            return;
        }
        lines.addVertex(pose.last(), (float) from.x, (float) from.y, (float) from.z)
                .setColor(r, g, b, a)
                .setNormal(pose.last(), dx / length, dy / length, dz / length);
        lines.addVertex(pose.last(), (float) to.x, (float) to.y, (float) to.z)
                .setColor(r, g, b, a)
                .setNormal(pose.last(), dx / length, dy / length, dz / length);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        BlockPos missile = boundMissile(mc.player);
        if (missile == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 2 + 22;

        // The missile has to be told it answers to a remote before any of this matters,
        // and the operator has no other way of finding that out from over here.
        if (mc.level.getBlockEntity(missile) instanceof CruiseMissileBlockEntity guidance
                && guidance.guidance() != CruiseMissileBlockEntity.Guidance.REMOTE) {
            graphics.drawCenteredString(
                    mc.font,
                    Component.translatable("gui.cbc_more_content.designator.not_remote")
                            .withStyle(ChatFormatting.RED),
                    x,
                    y,
                    0xFFFFFFFF);
            return;
        }

        if (locked != null) {
            graphics.drawCenteredString(
                    mc.font, Component.translatable("gui.cbc_more_content.designator.locked"), x, y, 0xFFFF5A46);
            return;
        }
        if (progress > 0) {
            int percent = Math.min(99, progress * 100 / LOCK_TICKS);
            graphics.drawCenteredString(
                    mc.font,
                    Component.translatable("gui.cbc_more_content.designator.locking", percent),
                    x,
                    y,
                    0xFFFFB036);
            return;
        }
        if (confirm > 0) {
            return;
        }
        if (aimed != null) {
            graphics.drawCenteredString(
                    mc.font, Component.translatable("gui.cbc_more_content.designator.hold"), x, y, 0xFFE8E2CF);
        }
    }
}
