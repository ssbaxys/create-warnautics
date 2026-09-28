package com.cbc_more_content.client.veil;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.client.BombFlashClient;
import com.cbc_more_content.client.FlashExposure;
import com.cbc_more_content.client.FlashRenderMode;
import com.cbc_more_content.config.WarnauticsClientConfig;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import foundry.veil.forge.event.ForgeVeilPostProcessingEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Bounded world lighting, with camera exposure shared by Veil and the fallback overlay. */
public final class VeilBombFx {
    public static final ResourceLocation PIPELINE =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bomb_flash");
    private static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bomb_flash_composite");
    private static final int MAX_LIGHTS = 6;
    private static final List<ActiveLight> LIGHTS = new ArrayList<>();
    private static final ResourceLocation LIGHTING_BUFFERS =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "effect_lighting");
    private static net.minecraft.client.multiplayer.ClientLevel bufferLevel;
    private static boolean buffersReserved;

    private VeilBombFx() {}

    public static void onFlash(BombFlashClient.Flash flash) {
        if (FlashRenderMode.sodiumExtrasLoaded()) {
            return;
        }
        if (WarnauticsClientConfig.bombLights()) {
            if (LIGHTS.size() >= MAX_LIGHTS) {
                var weakest = LIGHTS.stream()
                        .min(java.util.Comparator.comparingDouble(a -> priority(a.flash)))
                        .orElseThrow();
                if (priority(weakest.flash) > priority(flash)) {
                    return;
                }
                free(weakest);
                LIGHTS.remove(weakest);
            }
            LIGHTS.add(new ActiveLight(flash));
        }
        updatePost();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        updateLightingBuffers();
        var iterator = LIGHTS.iterator();
        while (iterator.hasNext()) {
            var active = iterator.next();
            if (mc.level != active.flash.level
                    || active.flash.age >= active.flash.life
                    || !WarnauticsClientConfig.bombLights()) {
                free(active);
                iterator.remove();
            }
        }
        updatePost();
    }

    private static void updateLightingBuffers() {
        var mc = Minecraft.getInstance();
        var renderer = VeilRenderSystem.renderer();
        var albedo = foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType.ALBEDO;
        var normal = foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType.NORMAL;
        if (bufferLevel != mc.level) {
            if (buffersReserved) {
                renderer.disableBuffers(LIGHTING_BUFFERS, albedo, normal);
            }
            buffersReserved = false;
            bufferLevel = mc.level;
        }
        boolean wanted = mc.level != null
                && mc.player != null
                && !FlashRenderMode.sodiumExtrasLoaded()
                && WarnauticsClientConfig.stableLightingBuffers()
                && (WarnauticsClientConfig.bombLights() || WarnauticsClientConfig.missileLights());
        if (wanted == buffersReserved) {
            return;
        }
        if (wanted) {
            renderer.enableBuffers(LIGHTING_BUFFERS, albedo, normal);
        } else {
            renderer.disableBuffers(LIGHTING_BUFFERS, albedo, normal);
        }
        buffersReserved = wanted;
    }

    private static void updatePost() {
        var mc = Minecraft.getInstance();
        boolean enabled = mc.level != null
                && mc.player != null
                && WarnauticsClientConfig.screenEffects()
                && !FlashRenderMode.sodiumExtrasLoaded()
                && (FlashExposure.visible()
                        || BombFlashClient.flashes().stream().anyMatch(f -> f.fade(0) > .001));
        var post = VeilRenderSystem.renderer().getPostProcessingManager();
        if (enabled && !post.isActive(PIPELINE)) {
            post.add(50, PIPELINE);
        } else if (!enabled && post.isActive(PIPELINE)) {
            post.remove(PIPELINE);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS
                || Minecraft.getInstance().isPaused()) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        int skyBudget = 3;
        for (var active : LIGHTS) {
            float fade = active.flash.lightFade(partial);
            float radius = radiusFor(active.flash) * (.65f + .35f * fade);
            float brightness = brightnessFor(active.flash) * fade;
            float hot = (float) Math.exp(-(active.flash.age + partial) / 7);
            var pos = active.flash.pos;
            update(active.main, pos.add(0, .25, 0), radius, brightness, hot);
            boolean safe = safeCamera(camera, active.main, active.mainHandle != null);
            active.mainHandle = handle(active.mainHandle, active.main, safe && fade > .002f);
            boolean elevated = active.flash.size == BombSize.MEDIUM
                    || active.flash.size == BombSize.LARGE
                    || active.flash.size == BombSize.MOAB;
            update(
                    active.sky,
                    pos.add(0, FlashExposure.skyHeight(active.flash.size), 0),
                    radius * 1.15f,
                    brightness * .26f,
                    hot * .75f);
            boolean skySafe = elevated && skyBudget > 0 && safeCamera(camera, active.sky, active.skyHandle != null);
            active.skyHandle = handle(active.skyHandle, active.sky, skySafe && fade > .002f);
            if (active.skyHandle != null) {
                skyBudget--;
            }
        }
    }

    private static void update(PointLightData light, Vec3 position, float radius, float brightness, float hot) {
        light.setPosition(position.x, position.y, position.z);
        light.setRadius(radius).setBrightness(brightness);
        light.setColor(1, Mth.lerp(hot, .43f, .91f), Mth.lerp(hot, .10f, .66f));
    }

    private static boolean safeCamera(Vec3 camera, PointLightData light, boolean registered) {
        // Retain protection against Veil's inverted light cubes, with hysteresis at their boundary.
        double extent = light.getRadius() + (registered ? 1.5 : 3);
        var pos = light.getPosition();
        return Math.abs(camera.x - pos.x()) > extent
                || Math.abs(camera.y - pos.y()) > extent
                || Math.abs(camera.z - pos.z()) > extent;
    }

    private static LightRenderHandle<?> handle(LightRenderHandle<?> current, PointLightData light, boolean enabled) {
        if (!enabled) {
            if (current != null) {
                current.free();
            }
            return null;
        }
        if (current == null || !current.isValid()) {
            if (current != null) {
                current.free();
            }
            current = VeilRenderSystem.renderer().getLightRenderer().addLight(light);
        }
        current.markDirty();
        return current;
    }

    @SubscribeEvent
    public static void onPostPre(ForgeVeilPostProcessingEvent.Pre event) {
        if (!PIPELINE.equals(event.getName())) {
            return;
        }
        var shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        if (shader == null) {
            return;
        }
        // Upload zero values too: skipping the last dark frame used to retain a stale flash.
        shader.getUniformSafe("Exposure").setFloat(FlashExposure.exposure());
        shader.getUniformSafe("AmbientGlow").setFloat(FlashExposure.glow());
        shader.getUniformSafe("Proximity").setFloat(FlashExposure.proximity());
        for (int i = 0; i < FlashExposure.MAX_SOURCES; i++) {
            var source = FlashExposure.source(i);
            shader.getUniformSafe("Source" + i).setVector(source.x, source.y, source.z, source.w);
        }
    }

    private static double priority(BombFlashClient.Flash flash) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return flash.intensity * flash.lightFade(0) / (1 + camera.distanceToSqr(flash.pos) / 1600);
    }

    private static float radiusFor(BombFlashClient.Flash flash) {
        float radius =
                switch (flash.size) {
                    case SMALL -> 16;
                    case SEA -> 20;
                    case MEDIUM -> 28;
                    case LARGE -> 38;
                    case MOAB -> 58;
                };
        return radius * Mth.clamp(flash.intensity, .55f, 1.35f);
    }

    private static float brightnessFor(BombFlashClient.Flash flash) {
        float brightness =
                switch (flash.size) {
                    case SMALL -> 13;
                    case SEA -> 17;
                    case MEDIUM -> 25;
                    case LARGE -> 34;
                    case MOAB -> 52;
                };
        return brightness * Mth.clamp(flash.intensity, .55f, 1.35f);
    }

    private static void free(ActiveLight active) {
        if (active.mainHandle != null) {
            active.mainHandle.free();
        }
        if (active.skyHandle != null) {
            active.skyHandle.free();
        }
        active.mainHandle = active.skyHandle = null;
    }

    private static final class ActiveLight {
        final BombFlashClient.Flash flash;
        final PointLightData main = new PointLightData().setOcclusionEnabled(true);
        final PointLightData sky = new PointLightData().setOcclusionEnabled(true);
        LightRenderHandle<?> mainHandle;
        LightRenderHandle<?> skyHandle;

        ActiveLight(BombFlashClient.Flash flash) {
            this.flash = flash;
        }
    }
}
