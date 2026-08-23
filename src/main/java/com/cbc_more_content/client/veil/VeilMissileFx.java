package com.cbc_more_content.client.veil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.joml.Vector3f;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A real light on the nozzle of a missile under power, so the plume throws illumination
 * onto the ground and walls it passes rather than only glowing on its own quads.
 * <p>
 * Only reachable when Veil is installed — {@link com.cbc_more_content.munitions.CruiseMissileProjectile}
 * calls in through {@link VeilMissileFx#onExhaust}, which is a no-op without it.
 */
@OnlyIn(Dist.CLIENT)
public final class VeilMissileFx {
    private static final float RADIUS = 7.5f;
    private static final Vector3f COLOR = new Vector3f(1.0f, 0.62f, 0.22f);
    /** Dropped once a missile has gone this long without reporting a nozzle. */
    private static final int STALE_TICKS = 3;

    private static final Map<Integer, Tracked> LIGHTS = new HashMap<>();
    private static boolean unavailable;

    private VeilMissileFx() {
    }

    /** Called every client tick a missile draws its plume. */
    public static void follow(Entity missile, Vec3 nozzle, boolean powered) {
        if (unavailable) {
            return;
        }
        try {
            sweep();
            Tracked tracked = LIGHTS.get(missile.getId());
            if (!powered || missile.isRemoved()) {
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
                tracked = new Tracked(light,
                        VeilRenderSystem.renderer().getLightRenderer().addLight(light));
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
        try {
            if (tracked.handle != null) {
                tracked.handle.free();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clear() {
        LIGHTS.values().forEach(VeilMissileFx::release);
        LIGHTS.clear();
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
}
