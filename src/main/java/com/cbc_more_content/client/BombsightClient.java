package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bombsight.BombsightBlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.item.BombSettingsKeyItem;
import com.cbc_more_content.network.BombsightSolutionPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * What the crew sees of a bombsight's work.
 * <p>
 * In the world: a ring where a bomb let go now would come down, the track it is moving
 * along, and, with a target set, a cross and a tall beam on the target. On the screen, at
 * the top: a few plates with the height, ground speed and fall time, and on a run the
 * distance and time to release, which way to steer, and a strip showing how far off the
 * track the target lies. The readings follow the sight the player stands by, or the one
 * their settings key is bound to, from anywhere within the crew range.
 */
@OnlyIn(Dist.CLIENT)
public final class BombsightClient {
    public static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bombsight");

    /** Readings older than this are stale: the sight has stopped sending. */
    private static final int STALE_TICKS = 20;
    /** Standing this close to a sight counts as manning it. */
    private static final double MAN_RANGE = 10.0D;
    /** Half the width of the steering strip, in blocks off the track. */
    private static final float STRIP_SPAN = 24.0F;

    private static final Map<BlockPos, Reading> READINGS = new HashMap<>();

    private BombsightClient() {}

    private static final class Reading {
        BombsightSolutionPayload solution;
        int age;
        /** Ticks since the release lamp came on, for the flash. */
        int releasedFor = -1;

        Reading(BombsightSolutionPayload solution) {
            this.solution = solution;
        }
    }

    /** Network entry point. */
    public static void receive(BombsightSolutionPayload solution) {
        Reading reading = READINGS.get(solution.sight());
        if (reading == null) {
            READINGS.put(solution.sight(), new Reading(solution));
            return;
        }
        if (solution.releasing() && !reading.solution.releasing()) {
            reading.releasedFor = 0;
        }
        reading.solution = solution;
        reading.age = 0;
    }

    /** The newest readings of the sight at this block, or null if none are fresh. */
    @Nullable
    public static BombsightSolutionPayload latest(BlockPos sight) {
        Reading reading = READINGS.get(sight);
        return reading == null || reading.age > STALE_TICKS ? null : reading.solution;
    }

    /** Ticks since the sight at this block last reported. */
    public static int readingAge(BlockPos sight) {
        Reading reading = READINGS.get(sight);
        return reading == null ? 0 : reading.age;
    }

    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.BOSS_OVERLAY, LAYER, BombsightClient::render);
    }

    /** The sight this player is working with right now, if any. */
    @Nullable
    private static Reading manned(LocalPlayer player) {
        BlockPos bound = null;
        for (ItemStack stack : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.getItem() instanceof BombSettingsKeyItem) {
                bound = BombSettingsKeyItem.bombsight(stack);
                if (bound != null) {
                    break;
                }
            }
        }
        Reading best = null;
        double bestDistance = MAN_RANGE * MAN_RANGE;
        for (var entry : READINGS.entrySet()) {
            Reading reading = entry.getValue();
            if (reading.age > STALE_TICKS) {
                continue;
            }
            if (entry.getKey().equals(bound)) {
                return reading;
            }
            double distance = player.distanceToSqr(reading.solution.sightWorld());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = reading;
            }
        }
        return best;
    }

    @EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (Minecraft.getInstance().isPaused()) {
                return;
            }
            READINGS.values().removeIf(reading -> {
                if (reading.releasedFor >= 0) {
                    reading.releasedFor++;
                }
                return ++reading.age > STALE_TICKS * 6;
            });
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            READINGS.clear();
        }

        @SubscribeEvent
        public static void renderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null
                    || mc.level == null
                    || mc.options.hideGui
                    || READINGS.isEmpty()
                    || BombsightView.active()) {
                return;
            }
            Reading reading = manned(mc.player);
            if (reading == null) {
                return;
            }
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            drawWorld(event, reading, partial, mc.player.tickCount + partial);
        }
    }

    /** The impact point carried forward from the last reading, so the ring glides with the carrier. */
    private static Vec3 impact(Reading reading, float partial) {
        var s = reading.solution;
        double ahead = Math.min(reading.age + partial, 4);
        return s.impact().add(s.velocity().x * ahead, 0, s.velocity().z * ahead);
    }

    private static void drawWorld(RenderLevelStageEvent event, Reading reading, float partial, float time) {
        var s = reading.solution;
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        Vec3 impact = impact(reading, partial).add(0, 0.08D, 0);
        boolean releasing = s.releasing();
        float[] ring = releasing ? rgb(Ui.RED) : s.landed() ? rgb(Ui.AMBER) : rgb(Ui.TEXT_DIM);
        // Two rings: a steady one where the bomb lands, and one breathing out from it.
        circle(lines, pose, impact, 1.5D, ring, 0.95f);
        float breath = (time % 20) / 20f;
        circle(lines, pose, impact, 1.5D + breath * 3.5D, ring, 0.7f * (1 - breath));
        line(lines, pose, impact.add(-0.6D, 0, 0), impact.add(0.6D, 0, 0), ring, 0.95f);
        line(lines, pose, impact.add(0, 0, -0.6D), impact.add(0, 0, 0.6D), ring, 0.95f);

        // The track: where the impact point is heading, dashed out ahead of it.
        double speed = Math.hypot(s.velocity().x, s.velocity().z);
        if (speed > 0.05D) {
            Vec3 dir = new Vec3(s.velocity().x / speed, 0, s.velocity().z / speed);
            float[] track = rgb(Ui.TEXT_MUTED);
            for (int i = 0; i < 12; i++) {
                Vec3 from = impact.add(dir.scale(3 + i * 6));
                line(lines, pose, from, from.add(dir.scale(3)), track, 0.55f * (1 - i / 12f));
            }
        }

        if (s.hasTarget()) {
            Vec3 target = new Vec3(s.target().x, impact.y, s.target().z);
            boolean onTrack = Math.abs(s.cross()) <= BombsightBlockEntity.CROSS_TOLERANCE;
            float[] mark = releasing ? rgb(Ui.RED) : onTrack ? rgb(Ui.GREEN) : rgb(Ui.AMBER);
            double size = 2.5D;
            line(lines, pose, target.add(-size, 0, -size), target.add(size, 0, size), mark, 1);
            line(lines, pose, target.add(-size, 0, size), target.add(size, 0, -size), mark, 1);
            circle(lines, pose, target, BombsightBlockEntity.CROSS_TOLERANCE, mark, 0.5f);
            // A beam to find it by from the air, pulsing gently.
            float pulse = 0.55f + 0.35f * Mth.sin(time * 0.15f);
            line(lines, pose, target, target.add(0, 48, 0), mark, pulse);
            line(lines, pose, impact, target, rgb(Ui.TEXT_MUTED), 0.35f);
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static float[] rgb(int argb) {
        return new float[] {(argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f};
    }

    private static void circle(VertexConsumer lines, PoseStack pose, Vec3 centre, double radius, float[] c, float a) {
        int segments = Mth.clamp((int) (radius * 8), 16, 48);
        Vec3 previous = centre.add(radius, 0, 0);
        for (int i = 1; i <= segments; i++) {
            double angle = i * Math.PI * 2 / segments;
            Vec3 next = centre.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            line(lines, pose, previous, next, c, a);
            previous = next;
        }
    }

    private static void line(VertexConsumer lines, PoseStack pose, Vec3 from, Vec3 to, float[] c, float a) {
        if (a <= 0.01f) {
            return;
        }
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-5F) {
            return;
        }
        lines.addVertex(pose.last(), (float) from.x, (float) from.y, (float) from.z)
                .setColor(c[0], c[1], c[2], a)
                .setNormal(pose.last(), dx / length, dy / length, dz / length);
        lines.addVertex(pose.last(), (float) to.x, (float) to.y, (float) to.z)
                .setColor(c[0], c[1], c[2], a)
                .setNormal(pose.last(), dx / length, dy / length, dz / length);
    }

    // ---------------------------------------------------------------- HUD

    private static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.options.hideGui
                || mc.screen != null
                || READINGS.isEmpty()
                || BombsightView.active()) {
            return;
        }
        Reading reading = manned(mc.player);
        if (reading == null) {
            return;
        }
        var s = reading.solution;
        var font = mc.font;
        int cx = g.guiWidth() / 2;
        int y = 4;
        int max = Math.min(260, g.guiWidth() - 20);
        float fade = 1 - Mth.clamp((reading.age - 10) / 10f, 0, 1);

        boolean run = s.mode() == 1 && s.hasTarget();
        Component title = Component.translatable(
                run ? "hud.cbc_more_content.bombsight.title_run" : "hud.cbc_more_content.bombsight.title_point");
        int titleTone = s.releasing() ? Ui.RED : run ? Ui.AMBER : Ui.PHOSPHOR;
        Ui.hudPlate(g, font, cx, y, title, titleTone, fade, -1, 0, max);
        y += 17;

        double height = s.sightWorld().y - s.impact().y;
        double speed = Math.hypot(s.velocity().x, s.velocity().z) * 20;
        Ui.hudPlate(
                g,
                font,
                cx,
                y,
                Component.translatable(
                        "hud.cbc_more_content.bombsight.flight",
                        Math.round(height),
                        Math.round(speed),
                        one(s.fallTicks() / 20.0D)),
                Ui.TEXT_MUTED,
                fade,
                -1,
                0,
                max);
        y += 17;

        if (s.releasing()) {
            float flash = reading.releasedFor < 0 ? 0 : Mth.clamp(1 - reading.releasedFor / 12f, 0, 1);
            Ui.hudPlate(
                    g,
                    font,
                    cx,
                    y,
                    Component.translatable("hud.cbc_more_content.bombsight.release"),
                    Ui.RED,
                    fade,
                    -1,
                    flash,
                    max);
            y += 17;
        } else if (s.hasTarget()) {
            Component line;
            int tone;
            float progress = -1;
            if (s.untilDrop() >= 0) {
                line = Component.translatable(
                        "hud.cbc_more_content.bombsight.until", Math.round(s.along()), one(s.untilDrop() / 20.0D));
                tone = s.untilDrop() <= 40 ? Ui.RED : Ui.AMBER;
                // The foot fills over the last ten seconds of the run.
                progress = Mth.clamp(1 - s.untilDrop() / 200f, 0, 1);
            } else if (speed < 1.0D) {
                line = Component.translatable("hud.cbc_more_content.bombsight.hover", Math.round(s.along()));
                tone = Ui.AMBER;
            } else {
                line = Component.translatable("hud.cbc_more_content.bombsight.passed", Math.round(-s.along()));
                tone = Ui.TEXT_DIM;
            }
            Ui.hudPlate(g, font, cx, y, line, tone, fade, progress, 0, max);
            y += 17;
            if (speed >= 1.0D) {
                y = steering(g, font, cx, y, s.cross(), fade, max);
            }
        }

        if (!s.rack()) {
            Ui.hudPlate(
                    g,
                    font,
                    cx,
                    y,
                    Component.translatable("hud.cbc_more_content.bombsight.no_rack"),
                    Ui.TEXT_DIM,
                    fade,
                    -1,
                    0,
                    max);
            y += 17;
        }
        if (!s.landed()) {
            Ui.hudPlate(
                    g,
                    font,
                    cx,
                    y,
                    Component.translatable("hud.cbc_more_content.bombsight.unloaded"),
                    Ui.TEXT_DIM,
                    fade,
                    -1,
                    0,
                    max);
            y += 17;
        }
        if (s.mode() == 1 && !s.hasTarget()) {
            Ui.hudPlate(
                    g,
                    font,
                    cx,
                    y,
                    Component.translatable("hud.cbc_more_content.bombsight.no_target"),
                    Ui.AMBER,
                    fade,
                    -1,
                    0,
                    max);
        }
    }

    /**
     * Which way to steer, in words, and under it a strip: the centre mark is the track, the
     * diamond is the target, and the green band is how far off it may be and still be hit.
     */
    private static int steering(
            GuiGraphics g, net.minecraft.client.gui.Font font, int cx, int y, float cross, float fade, int max) {
        boolean onTrack = Math.abs(cross) <= BombsightBlockEntity.CROSS_TOLERANCE;
        Component text = onTrack
                ? Component.translatable("hud.cbc_more_content.bombsight.on_track")
                : Component.translatable(
                        cross > 0 ? "hud.cbc_more_content.bombsight.right" : "hud.cbc_more_content.bombsight.left",
                        Math.round(Math.abs(cross)));
        Ui.hudPlate(g, font, cx, y, text, onTrack ? Ui.GREEN : Ui.AMBER, fade, -1, 0, max);
        y += 16;
        int half = 60;
        int top = y;
        g.fill(cx - half - 1, top, cx + half + 1, top + 9, Ui.withAlpha(Ui.OUTLINE, fade * 0.92f));
        g.fill(cx - half, top + 1, cx + half, top + 8, Ui.withAlpha(Ui.WELL, fade * 0.88f));
        int band = (int) Math.round(half * BombsightBlockEntity.CROSS_TOLERANCE / STRIP_SPAN);
        g.fill(cx - band, top + 1, cx + band, top + 8, Ui.withAlpha(Ui.PHOSPHOR_DIM, fade));
        for (int i = -4; i <= 4; i++) {
            int tx = cx + i * half / 4;
            g.fill(tx, top + (i == 0 ? 1 : 5), tx + 1, top + 8, Ui.withAlpha(Ui.TEXT_DIM, fade));
        }
        int dx = Math.round(Mth.clamp(cross / STRIP_SPAN, -1, 1) * (half - 3));
        int colour = Ui.withAlpha(onTrack ? Ui.GREEN : Ui.AMBER, fade);
        int mx = cx + dx;
        g.fill(mx - 1, top + 1, mx + 2, top + 8, colour);
        g.fill(mx - 2, top + 3, mx + 3, top + 6, colour);
        return top + 12;
    }
}
