package com.cbc_more_content.client.veil;

import com.cbc_more_content.CBCMoreContent;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Frame-interpolated nozzle lighting. Expiration is advanced once per tick, never per missile. */
public final class VeilMissileFx {
    private static final float RADIUS = 7.5f;
    private static final Map<Integer, Tracked> LIGHTS = new HashMap<>();
    private static boolean unavailable;

    private VeilMissileFx() {}

    public static void tick() {
        var it = LIGHTS.values().iterator();
        while (it.hasNext()) {
            var light = it.next();
            if (++light.idle > 2 || light.entity.isRemoved()) {
                light.handle.free();
                it.remove();
            }
        }
    }

    public static void follow(Entity missile, Vec3 nozzle, float strength) {
        if (unavailable) {
            return;
        }
        try {
            Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            var tracked = LIGHTS.get(missile.getId());
            double margin = RADIUS + 2;
            boolean inside = Math.abs(eye.x - nozzle.x) < margin
                    && Math.abs(eye.y - nozzle.y) < margin
                    && Math.abs(eye.z - nozzle.z) < margin;
            // Preserve the near-camera guard for Veil's inverted light volumes.
            if (inside || strength <= 0 || missile.isRemoved()) {
                if (tracked != null) {
                    tracked.handle.free();
                    LIGHTS.remove(missile.getId());
                }
                return;
            }
            if (tracked != null && (tracked.entity != missile || !tracked.handle.isValid())) {
                tracked.handle.free();
                LIGHTS.remove(missile.getId());
                tracked = null;
            }
            if (tracked == null) {
                var light = new PointLightData()
                        .setColor(1f, .66f, .29f)
                        .setRadius(RADIUS)
                        .setBrightness(strength);
                light.setPosition(nozzle.x, nozzle.y, nozzle.z);
                tracked = new Tracked(
                        missile,
                        light,
                        VeilRenderSystem.renderer().getLightRenderer().addLight(light));
                LIGHTS.put(missile.getId(), tracked);
            }
            tracked.light.setPosition(nozzle.x, nozzle.y, nozzle.z);
            tracked.light.setBrightness(strength * 1.3f);
            tracked.handle.markDirty();
            tracked.idle = 0;
        } catch (LinkageError | RuntimeException failure) {
            unavailable = true;
            clear();
            CBCMoreContent.LOGGER.warn("Missile dynamic lighting unavailable; plume remains enabled", failure);
        }
    }

    public static void clear() {
        LIGHTS.values().forEach(light -> light.handle.free());
        LIGHTS.clear();
    }

    private static final class Tracked {
        final Entity entity;
        final PointLightData light;
        final LightRenderHandle<PointLightData> handle;
        int idle;

        Tracked(Entity entity, PointLightData light, LightRenderHandle<PointLightData> handle) {
            this.entity = entity;
            this.light = light;
            this.handle = handle;
        }
    }
}
