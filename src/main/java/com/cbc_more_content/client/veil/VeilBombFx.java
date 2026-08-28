package com.cbc_more_content.client.veil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Vector3f;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.client.BombFlashClient;
import com.cbc_more_content.client.FlashRenderMode;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.forge.event.ForgeVeilPostProcessingEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Veil point lights + long-range screen flash/bloom for bomb detonations.
 */
public final class VeilBombFx {
    /**
     * Deliberately a single plain blit, and left that way.
     * <p>
     * Three attempts to reshape this pipeline while chasing the missing first-person hand
     * all made things worse: mask and depth-function stages stopped the flash rendering
     * outright, and copying the frame aside first left the hand worse than before. Until
     * the fault is actually isolated — turn the halves off one at a time with
     * {@link com.cbc_more_content.config.WarnauticsClientConfig} — this stays as it is.
     */
    public static final ResourceLocation PIPELINE =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bomb_flash");
    private static final ResourceLocation SEED_SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bomb_flash_seed");
    private static final ResourceLocation COMPOSITE_SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bomb_flash_composite");

    private static final float SMOOTH_UP = 0.62f;
    private static final float SMOOTH_DOWN = 0.28f;
    private static final int VISIBILITY_REFRESH_TICKS = 3;
    private static final int MAX_ACTIVE_LIGHTS = 12;
    private static final float LIGHT_VOLUME_MARGIN = 1.5f;

    private static final List<ActiveLight> LIGHTS = new ArrayList<>();

    private static float uIntensity;
    private static float uLook;
    private static float uProximity;
    private static float uSkyGlow;
    /** Parking spot for the hotspot when no blast is actually in frame. */
    private static final float OFFSCREEN_UV = -0.2f;

    private static float uBlastU = OFFSCREEN_UV;
    private static float uBlastV = OFFSCREEN_UV;
    private static final Vector3f uColor = new Vector3f(1.0f, 0.82f, 0.32f);

    private VeilBombFx() {
    }

    public static void onFlash(BombFlashClient.Flash flash) {
        try {
            // Sodium Extras changes the same render-state boundaries used by Veil.
            // Use the renderer-independent overlay path in that combination.
            if (FlashRenderMode.sodiumExtrasLoaded()
                    || (!com.cbc_more_content.config.WarnauticsClientConfig.bombLights()
                    && !com.cbc_more_content.config.WarnauticsClientConfig.screenEffects())) {
                return;
            }
            if (LIGHTS.size() >= MAX_ACTIVE_LIGHTS) {
                evictWeakestLight();
            }

            float radius = radiusFor(flash);
            float brightness = brightnessFor(flash);

            // Registration is deliberately deferred to the client tick, where the
            // camera position is known. Veil renders point lights as inverted cubes;
            // registering one around the camera can expose cube faces as magenta sheets.
            PointLightData light = new PointLightData()
                    .setPosition(flash.pos.x, flash.pos.y + 0.25D, flash.pos.z)
                    .setColor(1.0f, 0.72f, 0.22f)
                    .setBrightness(brightness)
                    .setRadius(radius)
                    .setOcclusionEnabled(true);
            double skyY = flash.pos.y + skyOffsetFor(flash.size);
            PointLightData skyLight = new PointLightData()
                    .setPosition(flash.pos.x, skyY, flash.pos.z)
                    .setColor(1.0f, 0.66f, 0.18f)
                    .setBrightness(brightness * 0.52f)
                    .setRadius(radius * 1.28f)
                    .setOcclusionEnabled(true);

            LIGHTS.add(new ActiveLight(flash, light, skyLight));

            if (com.cbc_more_content.config.WarnauticsClientConfig.screenEffects()
                    && !FlashRenderMode.sodiumExtrasLoaded()) {
                PostProcessingManager post = VeilRenderSystem.renderer().getPostProcessingManager();
                if (!post.isActive(PIPELINE)) {
                    post.add(50, PIPELINE);
                }
            }
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.debug("Veil bomb light/post failed: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clearLights();
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f right = new Vector3f(camera.getLeftVector()).mul(-1.0f);
        Vector3f up = camera.getUpVector();
        LocalPlayer player = mc.player;

        float targetIntensity = 0.0f;
        float targetLook = 0.0f;
        float targetProximity = 0.0f;
        float targetSkyGlow = 0.0f;
        float bestU = uBlastU;
        float bestV = uBlastV;
        float weightSum = 0.0f;
        boolean insideBlast = false;
        float sumU = 0.0f;
        float sumV = 0.0f;

        Iterator<ActiveLight> it = LIGHTS.iterator();
        while (it.hasNext()) {
            ActiveLight active = it.next();
            float screenFade = active.flash.fade(0.0f);
            float physicalFade = active.flash.lightFade(0.0f);
            if (active.flash.level != mc.level
                    || physicalFade <= 0.001f
                    || active.flash.age >= active.flash.life) {
                freeHandles(active);
                it.remove();
                continue;
            }

            float lightFade = smoothstep(physicalFade);
            float screenLevel = smoothstep(screenFade);
            float baseBrightness = brightnessFor(active.flash);
            float baseRadius = radiusFor(active.flash);
            float renderedRadius = Math.max(2.5f, baseRadius * (0.65f + 0.35f * lightFade));
            double lightX = active.flash.pos.x;
            double lightY = active.flash.pos.y + 0.25D;
            double lightZ = active.flash.pos.z;

            active.light.setPosition(active.flash.pos.x, active.flash.pos.y + 0.25D, active.flash.pos.z);
            active.light.setOcclusionEnabled(true);
            active.light.setBrightness(baseBrightness * lightFade);
            active.light.setRadius(renderedRadius);
            float hot = smoothstep(Mth.clamp(1.0f - active.flash.age / (float) Math.max(6, active.flash.life / 3), 0.0f, 1.0f));
            active.light.setColor(
                    1.0f,
                    Mth.lerp(hot, 0.58f, 0.90f),
                    Mth.lerp(hot, 0.16f, 0.48f));
            double skyY = active.flash.pos.y + skyOffsetFor(active.flash.size);
            float skyRadius = renderedRadius * 1.28f;
            active.skyLight.setPosition(active.flash.pos.x, skyY, active.flash.pos.z);
            active.skyLight.setOcclusionEnabled(true);
            active.skyLight.setBrightness(baseBrightness * lightFade * 0.52f);
            active.skyLight.setRadius(skyRadius);
            active.skyLight.setColor(
                    1.0f,
                    Mth.lerp(hot, 0.50f, 0.78f),
                    Mth.lerp(hot, 0.10f, 0.30f));

            // Brightness zero is insufficient: Veil still submits the light cube.
            // Remove the handle whenever the camera intersects (or nearly touches)
            // that cube, then recreate it only after the camera is safely outside.
            if (FlashRenderMode.sodiumExtrasLoaded()
                    || !com.cbc_more_content.config.WarnauticsClientConfig.bombLights()
                    || cameraIntersectsLightVolume(camPos, lightX, lightY, lightZ, renderedRadius)) {
                freeMainHandle(active);
            } else {
                ensureMainHandle(active);
            }
            if (active.handle != null) {
                active.handle.markDirty();
            }
            if (FlashRenderMode.sodiumExtrasLoaded()
                    || !com.cbc_more_content.config.WarnauticsClientConfig.bombLights()
                    || cameraIntersectsLightVolume(
                            camPos, active.flash.pos.x, skyY, active.flash.pos.z, skyRadius)) {
                freeSkyHandle(active);
            } else {
                ensureSkyHandle(active);
            }
            if (active.skyHandle != null) {
                active.skyHandle.markDirty();
            }

            if (screenLevel <= 0.001f) {
                continue;
            }

            Vec3 delta = active.flash.pos.subtract(camPos);
            double dist = delta.length();
            if (dist < 0.15D) {
                // Camera exactly at the blast center has no usable direction, but it
                // must still receive a safe full-screen pulse. The Veil light cube
                // remains unregistered above, so this cannot restore the magenta box.
                float centerPulse = Math.min(1.55f, active.flash.intensity * screenLevel * 0.96f);
                targetIntensity = Math.max(targetIntensity, centerPulse);
                targetLook = Math.max(targetLook, centerPulse);
                targetProximity = 1.0f;
                bestU = 0.5f;
                bestV = 0.5f;
                // Standing inside the fireball is the one case where a centred hotspot
                // is correct, so it is flagged rather than left to the projection below.
                insideBlast = true;
                continue;
            }

            // Screen FX reach is independent of the small occluded point-light radius.
            float reach = postReach(active.flash.size);
            if (dist > reach) {
                continue;
            }

            if (active.visibilityRefresh <= 0) {
                active.directVisibility = visibilityFactor(mc, camPos, active.flash.pos, player);
                active.skyVisibility = visibilityFactor(
                        mc,
                        camPos,
                        active.flash.pos.add(0.0D, skyOffsetFor(active.flash.size), 0.0D),
                        player);
                active.visibilityRefresh = VISIBILITY_REFRESH_TICKS;
            } else {
                active.visibilityRefresh--;
            }
            boolean skyOnly = active.directVisibility <= 0.05f && active.skyVisibility > 0.02f;
            Vec3 effectPos = skyOnly
                    ? active.flash.pos.add(0.0D, skyOffsetFor(active.flash.size), 0.0D)
                    : active.flash.pos;
            float visibility = skyOnly
                    ? active.skyVisibility * 0.68f
                    : active.directVisibility;
            if (visibility <= 0.02f) {
                continue;
            }

            delta = effectPos.subtract(camPos);
            dist = delta.length();
            if (dist < 0.15D) {
                continue;
            }
            Vector3f dir = new Vector3f((float) (delta.x / dist), (float) (delta.y / dist), (float) (delta.z / dist));
            float facing = look.dot(dir);
            // Wide cone — peripheral / partial facing still counts.
            float lookFactor = Mth.clamp((facing + 0.25f) / 1.25f, 0.0f, 1.0f);
            float distNorm = Mth.clamp(1.0f - (float) (dist / reach), 0.0f, 1.0f);
            // Soft far falloff so mid/long range still pops.
            float distFactor = (float) Math.sqrt(distNorm);
            float proximity = Mth.clamp(1.0f - (float) (dist / 18.0D), 0.0f, 1.0f);
            // Close proximity now increases exposure strongly. The unsafe Veil
            // light volume is still disabled around the camera; this brightness is
            // produced by the post pass and cannot become a magenta light cube.
            float closeT = Mth.clamp((float) ((dist - 0.75D) / 8.25D), 0.0f, 1.0f);
            float closeBoost = Mth.lerp(smoothstep(closeT), 1.72f, 1.0f);

            float strength = Math.min(1.85f, active.flash.intensity * screenLevel * visibility
                    * (0.4f + 0.6f * distFactor)
                    * (0.35f + 0.65f * lookFactor)
                    * closeBoost);
            if (skyOnly) {
                strength *= 0.62f;
            }
            // Always keep a readable flash when roughly aimed toward the blast.
            float lookStrength = strength * (0.4f + 0.6f * lookFactor);

            targetIntensity = Math.max(targetIntensity, strength);
            targetLook = Math.max(targetLook, lookStrength);
            targetProximity = Math.max(targetProximity, skyOnly ? 0.0f : proximity * visibility);
            if (skyOnly) {
                targetSkyGlow = Math.max(targetSkyGlow, lookStrength);
            }

            float z = look.dot(dir);
            if (z > 0.02f && lookFactor > 0.05f) {
                float fovRad = (float) Math.toRadians(Math.max(30.0D, mc.options.fov().get()));
                float tanHalf = (float) Math.tan(fovRad * 0.5D);
                float aspect = aspectOf(mc);
                float ndcX = (right.dot(dir) / z) / (tanHalf * aspect);
                float ndcY = (up.dot(dir) / z) / tanHalf;
                float u = ndcX * 0.5f + 0.5f;
                float v = ndcY * 0.5f + 0.5f;
                float w = Math.max(lookStrength, 0.05f);
                sumU += u * w;
                sumV += v * w;
                weightSum += w;
            }
        }

        // Nothing projected on screen this frame: every live blast is behind the camera
        // or out of frame. Park the hotspot well outside the viewport so only the
        // ambient, full-screen part of the pass contributes. Leaving the previous value
        // in place meant an unseen blast lit a blob at the screen centre, because the
        // field starts at (0.5, 0.5) and simply stayed there.
        boolean onScreen = weightSum > 1.0e-4f || insideBlast;
        if (weightSum > 1.0e-4f) {
            bestU = sumU / weightSum;
            bestV = sumV / weightSum;
        } else if (!insideBlast) {
            bestU = OFFSCREEN_UV;
            bestV = OFFSCREEN_UV;
        }

        uIntensity = damp(uIntensity, targetIntensity, targetIntensity > uIntensity ? SMOOTH_UP : SMOOTH_DOWN);
        uLook = damp(uLook, targetLook, targetLook > uLook ? SMOOTH_UP : SMOOTH_DOWN);
        uProximity = damp(uProximity, targetProximity, SMOOTH_UP);
        uSkyGlow = damp(uSkyGlow, targetSkyGlow, targetSkyGlow > uSkyGlow ? SMOOTH_UP : SMOOTH_DOWN);
        // Easing the hotspot across the screen looks right while it stays in view, but
        // it must not be eased back from off-screen: turning away and back gave the
        // marker a long crawl to its real position, and a flash that only lives about a
        // second was over before it arrived. Coming back into view, it snaps.
        float targetU = Mth.clamp(bestU, -0.2f, 1.2f);
        float targetV = Mth.clamp(bestV, -0.2f, 1.2f);
        boolean wasOffScreen = uBlastU <= -0.19f || uBlastU >= 1.19f
                || uBlastV <= -0.19f || uBlastV >= 1.19f;
        if (!onScreen || wasOffScreen) {
            uBlastU = targetU;
            uBlastV = targetV;
        } else {
            uBlastU = damp(uBlastU, targetU, SMOOTH_UP);
            uBlastV = damp(uBlastV, targetV, SMOOTH_UP);
        }
        uColor.set(1.0f, 0.82f, 0.32f);

        try {
            PostProcessingManager post = VeilRenderSystem.renderer().getPostProcessingManager();
            if (LIGHTS.isEmpty() && uIntensity < 0.01f && uLook < 0.01f) {
                post.remove(PIPELINE);
                uIntensity = 0.0f;
                uLook = 0.0f;
                uProximity = 0.0f;
                uSkyGlow = 0.0f;
            } else if (!FlashRenderMode.sodiumExtrasLoaded()
                    && com.cbc_more_content.config.WarnauticsClientConfig.screenEffects()
                    && !post.isActive(PIPELINE)
                    && (targetIntensity > 0.01f || !LIGHTS.isEmpty())) {
                post.add(50, PIPELINE);
            }
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.debug("Veil post-processing update failed: {}", t.toString());
        }
    }


    @SubscribeEvent
    public static void onPostPre(ForgeVeilPostProcessingEvent.Pre event) {
        if (!PIPELINE.equals(event.getName())) {
            return;
        }
        if (uIntensity <= 0.001f && uLook <= 0.001f) {
            return;
        }
        try {
            upload(SEED_SHADER);
            upload(COMPOSITE_SHADER);
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.debug("Veil uniform upload failed: {}", t.toString());
        }
    }

    private static void upload(ResourceLocation shaderId) {
        ShaderProgram shader = VeilRenderSystem.renderer().getShaderManager().getShader(shaderId);
        if (shader == null) {
            return;
        }
        shader.getUniformSafe("FlashIntensity").setFloat(uIntensity);
        shader.getUniformSafe("LookStrength").setFloat(uLook);
        shader.getUniformSafe("Proximity").setFloat(uProximity);
        shader.getUniformSafe("SkyGlow").setFloat(uSkyGlow);
        shader.getUniformSafe("BlastUV").setVector(uBlastU, uBlastV);
        shader.getUniformSafe("FlashColor").setVector(uColor.x, uColor.y, uColor.z);
    }

    private static float postReach(BombSize size) {
        return switch (size) {
            case SMALL -> 140.0f;
            case SEA -> 160.0f;
            case MEDIUM -> 240.0f;
            case LARGE -> 280.0f;
        };
    }

    private static float radiusFor(BombFlashClient.Flash flash) {
        float radius = switch (flash.size) {
            case SMALL -> 16.0f;
            case SEA -> 20.0f;
            case MEDIUM -> 28.0f;
            case LARGE -> 38.0f;
        };
        return radius * Mth.clamp(flash.intensity, 0.55f, 1.35f);
    }

    private static float brightnessFor(BombFlashClient.Flash flash) {
        float brightness = switch (flash.size) {
            case SMALL -> 13.0f;
            case SEA -> 17.0f;
            case MEDIUM -> 25.0f;
            case LARGE -> 34.0f;
        };
        return brightness * Mth.clamp(flash.intensity, 0.55f, 1.35f);
    }

    private static double skyOffsetFor(BombSize size) {
        return switch (size) {
            case SMALL -> 5.0D;
            case SEA -> 7.0D;
            case MEDIUM -> 10.0D;
            case LARGE -> 14.0D;
        };
    }

    private static void clearLights() {
        for (ActiveLight active : LIGHTS) {
            freeHandles(active);
        }
        LIGHTS.clear();
        uIntensity = 0.0f;
        uLook = 0.0f;
        uProximity = 0.0f;
        uSkyGlow = 0.0f;
        uBlastU = OFFSCREEN_UV;
        uBlastV = OFFSCREEN_UV;
        try {
            VeilRenderSystem.renderer().getPostProcessingManager().remove(PIPELINE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0..1 visibility: full if clear LOS, attenuated for partial cover (ray got most of the way).
     */
    private static float visibilityFactor(Minecraft mc, Vec3 from, Vec3 to, LocalPlayer player) {
        if (mc.level == null) {
            return 0.0f;
        }
        Vec3 eye = player != null ? player.getEyePosition(1.0f) : from;
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye,
                to,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player != null ? player : mc.getCameraEntity()));
        if (hit.getType() == HitResult.Type.MISS) {
            return 1.0f;
        }
        double full = eye.distanceTo(to);
        if (full < 1.0E-3D) {
            return 1.0f;
        }
        double reached = eye.distanceTo(hit.getLocation());
        double remain = hit.getLocation().distanceTo(to);
        if (remain <= 10.0D) {
            return 1.0f;
        }
        float frac = (float) (reached / full);
        // Seen most of the path / only light foliage cover → still show flash.
        return Mth.clamp((frac - 0.25f) / 0.55f, 0.0f, 1.0f);
    }

    private static float damp(float current, float target, float rate) {
        return current + (target - current) * Mth.clamp(rate, 0.05f, 1.0f);
    }

    private static float smoothstep(float x) {
        x = Mth.clamp(x, 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    private static float aspectOf(Minecraft mc) {
        int w = Math.max(1, mc.getWindow().getWidth());
        int h = Math.max(1, mc.getWindow().getHeight());
        return (float) w / (float) h;
    }

    private static boolean cameraIntersectsLightVolume(
            Vec3 camera,
            double lightX,
            double lightY,
            double lightZ,
            float radius) {
        double extent = radius + LIGHT_VOLUME_MARGIN;
        return Math.abs(camera.x - lightX) <= extent
                && Math.abs(camera.y - lightY) <= extent
                && Math.abs(camera.z - lightZ) <= extent;
    }

    private static void ensureMainHandle(ActiveLight active) {
        if (active.handle != null && active.handle.isValid()) {
            return;
        }
        freeMainHandle(active);
        try {
            active.handle = VeilRenderSystem.renderer().getLightRenderer().addLight(active.light);
        } catch (Throwable t) {
            active.handle = null;
            CBCMoreContent.LOGGER.debug("Veil point light registration failed: {}", t.toString());
        }
    }

    private static void freeMainHandle(ActiveLight active) {
        if (active.handle == null) {
            return;
        }
        try {
            active.handle.free();
        } catch (Throwable ignored) {
        }
        active.handle = null;
    }

    private static void ensureSkyHandle(ActiveLight active) {
        if (active.skyHandle != null && active.skyHandle.isValid()) {
            return;
        }
        freeSkyHandle(active);
        try {
            active.skyHandle = VeilRenderSystem.renderer().getLightRenderer().addLight(active.skyLight);
        } catch (Throwable t) {
            active.skyHandle = null;
            CBCMoreContent.LOGGER.debug("Veil sky light registration failed: {}", t.toString());
        }
    }

    private static void freeSkyHandle(ActiveLight active) {
        if (active.skyHandle == null) {
            return;
        }
        try {
            active.skyHandle.free();
        } catch (Throwable ignored) {
        }
        active.skyHandle = null;
    }

    private static void freeHandles(ActiveLight active) {
        freeMainHandle(active);
        freeSkyHandle(active);
    }

    private static void evictWeakestLight() {
        ActiveLight weakest = null;
        float weakestPriority = Float.MAX_VALUE;
        for (ActiveLight active : LIGHTS) {
            float priority = active.flash.intensity * active.flash.fade(0.0f);
            if (priority < weakestPriority) {
                weakestPriority = priority;
                weakest = active;
            }
        }
        if (weakest != null) {
            freeHandles(weakest);
            LIGHTS.remove(weakest);
        }
    }

    private static final class ActiveLight {
        final BombFlashClient.Flash flash;
        final PointLightData light;
        final PointLightData skyLight;
        LightRenderHandle<?> handle;
        LightRenderHandle<?> skyHandle;
        float directVisibility = 1.0f;
        float skyVisibility = 1.0f;
        int visibilityRefresh;

        ActiveLight(BombFlashClient.Flash flash, PointLightData light, PointLightData skyLight) {
            this.flash = flash;
            this.light = light;
            this.skyLight = skyLight;
        }
    }
}
