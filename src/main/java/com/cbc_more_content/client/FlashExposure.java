package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector4f;

/** Shared camera exposure for Veil and fallback. No visibility ray is cast from a render callback. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class FlashExposure {
    public static final int MAX_SOURCES = 8;
    public static final int RAYS_PER_TICK = 16;
    private static final Map<BombFlashClient.Flash, Visibility> VISIBILITY = new IdentityHashMap<>();
    private static final Vector4f[] SOURCES = {
        new Vector4f(),
        new Vector4f(),
        new Vector4f(),
        new Vector4f(),
        new Vector4f(),
        new Vector4f(),
        new Vector4f(),
        new Vector4f()
    };
    private static ClientLevel level;
    private static int cursor;
    private static double previousTime = Double.NaN;
    private static float exposure;
    private static float proximity;
    private static float glow;

    private FlashExposure() {}

    public static void tick() {
        var mc = Minecraft.getInstance();
        if (level != mc.level) {
            level = mc.level;
            VISIBILITY.clear();
            previousTime = Double.NaN;
            exposure = proximity = glow = 0;
            for (var source : SOURCES) {
                source.zero();
            }
        }
        var flashes = BombFlashClient.flashes();
        VISIBILITY.keySet().retainAll(flashes);
        if (mc.level == null || mc.player == null || mc.isPaused() || flashes.isEmpty()) {
            return;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        int visits = Math.min(flashes.size(), RAYS_PER_TICK / 2);
        for (int i = 0; i < visits; i++) {
            var flash = flashes.get(Math.floorMod(cursor++, flashes.size()));
            var visibility = VISIBILITY.computeIfAbsent(flash, ignored -> new Visibility());
            if (eye.distanceToSqr(flash.pos) > 512 * 512) {
                visibility.direct = visibility.sky = 0;
                continue;
            }
            visibility.direct = lineOfSight(mc, eye, flash.pos.add(0, 1, 0));
            visibility.sky = lineOfSight(mc, eye, flash.pos.add(0, skyHeight(flash.size), 0));
        }
    }

    private static float lineOfSight(Minecraft mc, Vec3 eye, Vec3 target) {
        double distance = eye.distanceTo(target);
        if (distance < 2) {
            return 1;
        }
        var hit = mc.level.clip(
                new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.getCameraEntity()));
        if (hit.getType() == HitResult.Type.MISS) {
            return 1;
        }
        // Only the final fireball-sized sliver may obscure its own origin. A nearby wall is real cover.
        return hit.getLocation().distanceTo(target) < 1.5 ? .7f : 0;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != level) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now = mc.level.getGameTime() + partial;
        float dt = Double.isNaN(previousTime) ? 1f / 60 : (float) Math.clamp((now - previousTime) / 20, 0, .1);
        previousTime = now;
        for (var source : SOURCES) {
            source.zero();
        }
        float targetExposure = 0;
        float targetGlow = 0;
        float targetProximity = 0;
        Vec3 camera = event.getCamera().getPosition();
        for (var flash : BombFlashClient.flashes()) {
            if (flash.level != mc.level || flash.fade(partial) < .001f) {
                continue;
            }
            var visible = VISIBILITY.get(flash);
            if (visible == null) {
                continue;
            }
            visible.smoothDirect = approach(visible.smoothDirect, visible.direct, dt, .035f, .10f);
            visible.smoothSky = approach(visible.smoothSky, visible.sky, dt, .06f, .14f);
            boolean skyOnly = visible.smoothDirect < .04f && visible.smoothSky > .01f;
            Vec3 position = skyOnly ? flash.pos.add(0, skyHeight(flash.size), 0) : flash.pos;
            Vec3 delta = position.subtract(camera);
            double distance = delta.length();
            float visibility = skyOnly ? visible.smoothSky * .25f : visible.smoothDirect;
            float falloff = Mth.clamp(1 - (float) distance / reach(flash.size), 0, 1);
            float energy = flash.intensity * flash.fade(partial) * visibility * falloff * falloff;
            // A distant visible fireball keeps a small world hotspot, without extending retinal glare.
            float hotspot =
                    flash.intensity * flash.fade(partial) * visibility / (1 + (float) (distance * distance) / 3600);
            float close = Mth.clamp(1 - (float) distance / 16, 0, 1);
            var view = event.getModelViewMatrix()
                    .transform(new Vector4f((float) delta.x, (float) delta.y, (float) delta.z, 1));
            float facing = distance < .01 ? 1 : Mth.clamp(-view.z / (float) distance, -1, 1);
            float front = smooth(Mth.clamp((facing + .12f) / 1.12f, 0, 1));
            float response = energy * (.035f + .72f * front + .25f * close);
            targetExposure = 1 - (1 - targetExposure) * (1 - Math.min(.8f, response));
            targetGlow = Math.max(targetGlow, energy * (.035f + .04f * close));
            targetProximity = Math.max(targetProximity, close * visibility);
            if (distance < .15) {
                offer(.5f, .5f, energy, .6f);
                continue;
            }
            var clip = event.getProjectionMatrix().transform(view);
            if (clip.w <= .05f) {
                continue;
            } // Never project a source behind the camera onto an edge.
            float u = clip.x / clip.w * .5f + .5f;
            float v = clip.y / clip.w * .5f + .5f;
            float edge = Math.max(Math.abs(u - .5f), Math.abs(v - .5f));
            float inFrame = 1 - smooth(Mth.clamp((edge - .45f) / .28f, 0, 1));
            float radius = Mth.clamp(
                    (float) (fireballRadius(flash.size) / distance)
                            * Math.abs(event.getProjectionMatrix().m11())
                            * .5f,
                    .016f,
                    .6f);
            offer(u, v, Math.max(energy, hotspot) * front * inFrame, skyOnly ? radius * 1.6f : radius);
        }
        // A short retinal response persists when turning away; the world hotspot itself never trails the camera.
        exposure = approach(exposure, targetExposure, dt, .018f, .22f);
        glow = approach(glow, targetGlow, dt, .025f, .13f);
        proximity = approach(proximity, targetProximity, dt, .06f, .16f);
    }

    private static void offer(float u, float v, float strength, float radius) {
        if (strength <= .001f) {
            return;
        }
        for (int i = 0; i < MAX_SOURCES; i++) {
            if (strength <= SOURCES[i].z) {
                continue;
            }
            for (int j = MAX_SOURCES - 1; j > i; j--) {
                SOURCES[j].set(SOURCES[j - 1]);
            }
            SOURCES[i].set(u, v, strength, radius);
            break;
        }
    }

    public static float approach(float current, float target, float seconds, float rise, float fall) {
        float tau = target > current ? rise : fall;
        return target + (current - target) * (float) Math.exp(-Math.max(0, seconds) / tau);
    }

    public static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    public static float exposure() {
        return exposure;
    }

    public static float glow() {
        return glow;
    }

    public static float proximity() {
        return proximity;
    }

    public static Vector4f source(int index) {
        return SOURCES[index];
    }

    public static boolean visible() {
        return exposure > .001f || SOURCES[0].z > .001f;
    }

    public static float reach(BombSize size) {
        return switch (size) {
            case SMALL -> 140;
            case SEA -> 160;
            case MEDIUM -> 240;
            case LARGE -> 280;
            case MOAB -> 420;
        };
    }

    public static double skyHeight(BombSize size) {
        return switch (size) {
            case SMALL -> 5;
            case SEA -> 7;
            case MEDIUM -> 10;
            case LARGE -> 14;
            case MOAB -> 22;
        };
    }

    private static float fireballRadius(BombSize size) {
        return switch (size) {
            case SMALL -> 4;
            case SEA -> 2.5f;
            case MEDIUM -> 7;
            case LARGE -> 10;
            case MOAB -> 9;
        };
    }

    private static final class Visibility {
        float direct;
        float sky;
        float smoothDirect;
        float smoothSky;
    }
}
