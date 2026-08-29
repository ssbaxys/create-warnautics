package com.cbc_more_content.client.veil;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * A real light on the nozzle of a missile under power, so the plume throws illumination
 * onto the ground and walls it passes rather than only glowing on its own quads, plus a
 * one-shot flash for the moment a cold-launched missile's motor catches.
 * <p>
 * Only reachable when Veil is installed — {@link com.cbc_more_content.client.MissileExhaustLights}
 * calls in through {@link #follow} and {@link #ignite}, which are no-ops without it.
 */
@OnlyIn(Dist.CLIENT)
public final class VeilMissileFx {
    private static final float RADIUS = 7.5f;
    private static final Vector3f COLOR = new Vector3f(1.0f, 0.62f, 0.22f);
    /** Dropped once a missile has gone this long without reporting a nozzle. */
    private static final int STALE_TICKS = 3;
    /** Clearance kept between the camera and a light cube, for the near plane. */
    private static final float VOLUME_MARGIN = 2.0f;

    /** Ignition burst: brief, bright, and gone — sells the motor lighting off gas alone. */
    private static final float FLASH_RADIUS = 11.0f;

    private static final Vector3f FLASH_COLOR = new Vector3f(1.0f, 0.86f, 0.62f);
    private static final int FLASH_TICKS = 7;

    private static final Map<Integer, Tracked> LIGHTS = new HashMap<>();
    private static final java.util.List<Flash> FLASHES = new java.util.ArrayList<>();
    private static boolean unavailable;

    private VeilMissileFx() {}

    /**
     * True while the camera is inside a light volume of this size centred on {@code at},
     * with room to spare for the near plane.
     */
    private static boolean cameraInside(Vec3 at) {
        var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        double extent = RADIUS + VOLUME_MARGIN;
        return Math.abs(eye.x - at.x) <= extent && Math.abs(eye.y - at.y) <= extent && Math.abs(eye.z - at.z) <= extent;
    }

    /** One-shot light burst at the nozzle the instant the motor catches. */
    public static void ignite(Vec3 at) {
        if (unavailable || cameraInside(at)) {
            return;
        }
        try {
            PointLightData light = new PointLightData()
                    .setColor(FLASH_COLOR.x, FLASH_COLOR.y, FLASH_COLOR.z)
                    .setRadius(FLASH_RADIUS)
                    .setBrightness(3.0f);
            light.setPosition(at.x, at.y, at.z);
            LightRenderHandle<PointLightData> handle =
                    VeilRenderSystem.renderer().getLightRenderer().addLight(light);
            FLASHES.add(new Flash(light, handle, FLASH_TICKS));
        } catch (Throwable ignored) {
            unavailable = true;
            clear();
        }
    }

    /** Fades and frees ignition flashes; called once a client tick alongside {@link #follow}. */
    public static void tickFlashes() {
        if (FLASHES.isEmpty()) {
            return;
        }
        Iterator<Flash> it = FLASHES.iterator();
        while (it.hasNext()) {
            Flash flash = it.next();
            if (--flash.ticksLeft <= 0) {
                release(flash.handle);
                it.remove();
                continue;
            }
            flash.light.setBrightness(3.0f * (flash.ticksLeft / (float) FLASH_TICKS));
            if (flash.handle != null) {
                flash.handle.markDirty();
            }
        }
    }

    /** Called every client tick a missile draws its plume. */
    public static void follow(Entity missile, Vec3 nozzle, boolean powered) {
        if (unavailable) {
            return;
        }
        try {
            sweep();
            Tracked tracked = LIGHTS.get(missile.getId());
            // A point light is drawn as an inverted cube, and a cube the camera is
            // standing inside turns into sheets across the view and leaves the
            // first-person hand on a broken transform. VeilBombFx has guarded against
            // this from the start; a missile passing close by is the one light in the mod
            // that actually moves through the player, which is why it was the only thing
            // that ever showed the fault.
            if (!powered || missile.isRemoved() || cameraInside(nozzle)) {
                if (tracked != null) {
                    release(tracked);
                    LIGHTS.remove(missile.getId());
                }
                return;
            }

            if (tracked == null) {
                PointLightData light = new PointLightData()
                        .setColor(COLOR.x, COLOR.y, COLOR.z)
                        .setRadius(RADIUS)
                        .setBrightness(1.0f);
                light.setPosition(nozzle.x, nozzle.y, nozzle.z);
                tracked = new Tracked(
                        light, VeilRenderSystem.renderer().getLightRenderer().addLight(light));
                LIGHTS.put(missile.getId(), tracked);
            }

            tracked.light.setPosition(nozzle.x, nozzle.y, nozzle.z);
            // A little flicker, so the exhaust does not read as a lamp bolted on the back.
            tracked.light.setBrightness(0.85f + missile.level().random.nextFloat() * 0.3f);
            if (tracked.handle != null) {
                tracked.handle.markDirty();
            }
            tracked.idle = 0;
        } catch (Throwable ignored) {
            // Veil present but its light API moved: stop trying rather than log per frame.
            unavailable = true;
            clear();
        }
    }

    private static void sweep() {
        Iterator<Map.Entry<Integer, Tracked>> it = LIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next().getValue();
            if (++tracked.idle > STALE_TICKS) {
                release(tracked);
                it.remove();
            }
        }
    }

    private static void release(Tracked tracked) {
        release(tracked.handle);
    }

    private static void release(LightRenderHandle<PointLightData> handle) {
        try {
            if (handle != null) {
                handle.free();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clear() {
        LIGHTS.values().forEach(VeilMissileFx::release);
        LIGHTS.clear();
        FLASHES.forEach(flash -> release(flash.handle));
        FLASHES.clear();
    }

    private static final class Tracked {
        final PointLightData light;
        final LightRenderHandle<PointLightData> handle;
        int idle;

        Tracked(PointLightData light, LightRenderHandle<PointLightData> handle) {
            this.light = light;
            this.handle = handle;
        }
    }

    private static final class Flash {
        final PointLightData light;
        final LightRenderHandle<PointLightData> handle;
        int ticksLeft;

        Flash(PointLightData light, LightRenderHandle<PointLightData> handle, int ticksLeft) {
            this.light = light;
            this.handle = handle;
            this.ticksLeft = ticksLeft;
        }
    }
}
