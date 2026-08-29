package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;

/**
 * Renderer-agnostic detonation flash.
 * <p>
 * The Veil pipeline draws real point lights and a bloom pass, but Veil's post
 * processing does not co-exist with Sodium/Iris. On those clients nothing consumed
 * {@link BombFlashClient} at all, so the flash packet arrived and simply produced no
 * picture. This overlay is plain {@link GuiGraphics} work — no custom shader stage, no
 * framebuffer of our own — so it renders identically under Sodium, Iris and vanilla,
 * and it stands in whenever {@link FlashRenderMode#veilOwnsFlash()} is false.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class BombFlashOverlay {
    /** Warm fireball tint the overlay fades toward at full intensity. */
    private static final Vector3f CORE_COLOR = new Vector3f(1.0f, 0.86f, 0.55f);

    private static final Vector3f EDGE_COLOR = new Vector3f(1.0f, 0.52f, 0.16f);
    /** A blast fully behind the camera still lifts ambient light this much. */
    private static final float BEHIND_CAMERA_GAIN = 0.28f;

    private static final float MAX_ALPHA = 0.82f;

    private BombFlashOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        if (BombFlashClient.flashes().isEmpty()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vector3f look = camera.getLookVector();

        float total = 0.0f;
        float warmth = 0.0f;
        for (BombFlashClient.Flash flash : BombFlashClient.flashes()) {
            if (flash.level != mc.level) {
                continue;
            }
            float contribution = contributionOf(mc, flash, camPos, look, partialTick);
            if (contribution <= 0.0f) {
                continue;
            }
            // Overlapping bursts brighten but must not stack into a white screen.
            total = total + contribution - (total * contribution);
            warmth = Math.max(warmth, sizeWarmth(flash.size));
        }

        total *= FlashRenderMode.overlayWeight();
        if (total <= 0.004f) {
            return;
        }

        float alpha = Mth.clamp(total, 0.0f, 1.0f) * MAX_ALPHA;
        int argb = packColor(alpha, warmth);
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), argb);
    }

    /** Per-flash screen brightness from distance, view angle and line of sight. */
    private static float contributionOf(
            Minecraft mc, BombFlashClient.Flash flash, Vec3 camPos, Vector3f look, float partialTick) {
        float fade = flash.fade(partialTick);
        if (fade <= 0.0f) {
            return 0.0f;
        }

        Vec3 toBlast = flash.pos.subtract(camPos);
        double distance = toBlast.length();
        double reach = reachOf(flash.size);
        if (distance > reach) {
            return 0.0f;
        }

        // Inverse falloff rather than linear: a near miss should be blinding while a
        // detonation at the edge of the range is only a flicker on the horizon.
        float falloff = (float) (1.0D - (distance / reach));
        falloff = falloff * falloff;

        float facing = 1.0f;
        if (distance > 1.0E-4D) {
            Vec3 dir = toBlast.scale(1.0D / distance);
            double dot = dir.x * look.x() + dir.y * look.y() + dir.z * look.z();
            // Looking away still leaves a glow: the world around the player is lit too.
            facing = (float) Mth.lerp((dot + 1.0D) * 0.5D, BEHIND_CAMERA_GAIN, 1.0D);
        }

        // Standing in the blast itself must not be dimmed by looking away from it.
        if (distance <= 8.0D) {
            facing = Math.max(facing, 0.75f);
        }

        float occlusion = occlusionOf(mc, camPos, flash.pos, distance);
        return Mth.clamp(fade * falloff * facing * occlusion * flash.intensity, 0.0f, 1.0f);
    }

    /**
     * 1 with clear line of sight, a floor value through walls (light still leaks in).
     * <p>
     * Several samples rather than one centre ray: detonations sit slightly inside the
     * surface they struck, so a ray aimed at the exact blast point lands in that block
     * from nearly every angle. Anything inside the fireball skips the test entirely.
     */
    private static float occlusionOf(Minecraft mc, Vec3 camPos, Vec3 blastPos, double distance) {
        if (mc.level == null || distance <= 8.0D) {
            return 1.0f;
        }
        Vec3[] samples = {
            blastPos.add(0.0D, 1.6D, 0.0D),
            blastPos,
            blastPos.add(0.0D, 3.2D, 0.0D),
            blastPos.add(1.6D, 0.8D, 0.0D),
            blastPos.add(-1.6D, 0.8D, 0.0D),
            blastPos.add(0.0D, 0.8D, 1.6D),
            blastPos.add(0.0D, 0.8D, -1.6D),
        };
        try {
            for (Vec3 sample : samples) {
                BlockHitResult hit = mc.level.clip(
                        new ClipContext(camPos, sample, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == HitResult.Type.MISS) {
                    return 1.0f;
                }
                // A ray that dies right next to the blast still means the fireball
                // itself is visible; only distant hits are real cover.
                if (hit.getLocation().distanceToSqr(sample) < 4.0D) {
                    return 0.85f;
                }
            }
        } catch (Throwable ignored) {
            return 1.0f;
        }
        return 0.18f;
    }

    private static double reachOf(BombSize size) {
        return switch (size) {
            case SMALL -> 140.0D;
            case SEA -> 160.0D;
            case MEDIUM -> 240.0D;
            case LARGE -> 280.0D;
        };
    }

    /** Bigger charges burn whiter at the core. */
    private static float sizeWarmth(BombSize size) {
        return switch (size) {
            case SMALL -> 0.25f;
            case SEA -> 0.3f;
            case MEDIUM -> 0.55f;
            case LARGE -> 0.8f;
        };
    }

    private static int packColor(float alpha, float warmth) {
        float r = Mth.lerp(warmth, EDGE_COLOR.x(), CORE_COLOR.x());
        float g = Mth.lerp(warmth, EDGE_COLOR.y(), CORE_COLOR.y());
        float b = Mth.lerp(warmth, EDGE_COLOR.z(), CORE_COLOR.z());
        int a = (int) (Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f);
        return (a << 24)
                | ((int) (Mth.clamp(r, 0.0f, 1.0f) * 255.0f) << 16)
                | ((int) (Mth.clamp(g, 0.0f, 1.0f) * 255.0f) << 8)
                | (int) (Mth.clamp(b, 0.0f, 1.0f) * 255.0f);
    }
}
