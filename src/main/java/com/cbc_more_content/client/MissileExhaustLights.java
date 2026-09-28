package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.config.WarnauticsClientConfig;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModParticles;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

/** Client-only exhaust lifecycle. Detached smoke stays in world space, the flame follows the nozzle. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class MissileExhaustLights {
    public static final double NOZZLE_OFFSET = 1.51;
    private static final double PARTICLE_RANGE = 240;
    private static final int MAX_DETAILED_MISSILES = 24;
    private static final Map<Integer, Trail> TRAILS = new HashMap<>();
    private static ClientLevel previousLevel;
    private static final boolean VEIL = ModList.get().isLoaded("veil");
    private static float terrainFogStart = Float.MAX_VALUE;
    private static float terrainFogEnd = Float.MAX_VALUE;

    private MissileExhaustLights() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (previousLevel != mc.level) {
            TRAILS.clear();
            previousLevel = mc.level;
            if (VEIL) {
                com.cbc_more_content.client.veil.VeilMissileFx.clear();
            }
        }
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        if (VEIL) {
            com.cbc_more_content.client.veil.VeilMissileFx.tick();
        }
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        // Follow the entities the server actually tracks. An extra sphere around the player
        // used to turn off a still-burning motor halfway through a vertical launch.
        var missiles = new ArrayList<CruiseMissileProjectile>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CruiseMissileProjectile missile && !missile.isRemoved()) {
                missiles.add(missile);
            }
        }
        missiles.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(camera)));
        Set<Integer> seen = new HashSet<>();
        int budget = 48;
        boolean volume = VEIL
                && WarnauticsClientConfig.missilePlume()
                && com.cbc_more_content.client.veil.VeilMissilePlume.available();
        int count = 0;
        for (var missile : missiles) {
            count++;
            var nozzle = nozzleOf(missile, 1);
            seen.add(missile.getId());
            var trail = TRAILS.computeIfAbsent(missile.getId(), id -> new Trail(missile, nozzle));
            if (trail.missile != missile) {
                trail = new Trail(missile, nozzle);
                TRAILS.put(missile.getId(), trail);
            }
            boolean wet = wet(missile, nozzle);
            boolean powered = missile.isPowered() && !missile.isEjecting() && !wet;
            if (powered && !trail.powered) {
                trail.ignitionTick = missile.tickCount;
            }
            if (!powered && trail.powered) {
                trail.cooldown = 10;
            }
            double distance = nozzle.distanceTo(camera);
            var setting = mc.options.particles().get();
            int stride = setting == ParticleStatus.MINIMAL ? 2 : 1;
            if (!wet
                    && budget > 0
                    && count <= MAX_DETAILED_MISSILES
                    && distance <= PARTICLE_RANGE
                    && missile.tickCount % stride == 0) {
                if (missile.isEjecting()) {
                    emit(
                            mc.level,
                            ModParticles.MISSILE_GAS.get(),
                            nozzle,
                            heading(missile, 1).scale(-.16),
                            .12);
                    budget--;
                } else if (powered || trail.cooldown > 0) {
                    // A sampled segment closes the gaps at 28 blocks/sec. Never bridge a teleport.
                    Vec3 previous = nozzle.distanceToSqr(trail.emittedNozzle) > 144 ? nozzle : trail.emittedNozzle;
                    double spacing = setting == ParticleStatus.MINIMAL ? 1.25 : distance > 120 ? .95 : .55;
                    int samples =
                            Math.min(budget, Math.clamp((int) Math.ceil(nozzle.distanceTo(previous) / spacing), 1, 8));
                    Vec3 back = heading(missile, 1).scale(-1);
                    for (int i = 0; i < samples; i++) {
                        Vec3 at = previous.lerp(nozzle, (i + 1.0) / samples);
                        // The glowing plume is continuous; cool exhaust starts beyond its bright core.
                        emit(
                                mc.level,
                                ModParticles.MISSILE_SMOKE.get(),
                                at.add(back.scale(powered ? 3.3 : .2)),
                                back.scale(.075),
                                .065);
                        if (powered && !volume) {
                            emit(mc.level, ModParticles.MISSILE_EXHAUST.get(), at, back.scale(.18), .06);
                        }
                    }
                    budget -= samples;
                    trail.emittedNozzle = nozzle;
                }
            }
            trail.cooldown = Math.max(0, trail.cooldown - 1);
            trail.nozzle = nozzle;
            trail.powered = powered;
        }
        TRAILS.keySet().retainAll(seen);
    }

    private static void emit(
            ClientLevel level,
            net.minecraft.core.particles.SimpleParticleType particle,
            Vec3 at,
            Vec3 velocity,
            double spread) {
        var r = level.random;
        level.addParticle(
                particle,
                true,
                at.x,
                at.y,
                at.z,
                velocity.x + (r.nextDouble() - .5) * spread,
                velocity.y + (r.nextDouble() - .5) * spread,
                velocity.z + (r.nextDouble() - .5) * spread);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (!VEIL) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            // Clouds, weather and post-processing can replace these globals later in the frame.
            terrainFogStart = RenderSystem.getShaderFogStart();
            terrainFogEnd = RenderSystem.getShaderFogEnd();
            return;
        }
        // Fabulous resolves its translucent particle target before this stage. Add emissive light
        // to the resolved scene, with the opaque world depth still present.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != previousLevel || TRAILS.isEmpty()) {
            return;
        }
        // Veil can leave its light/particle framebuffer bound after composing the world.
        // MAIN_TARGET is a no-op RenderType state: explicitly draw into the resolved scene.
        int drawFramebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        mc.getMainRenderTarget().bindWrite(false);
        try {
            renderTrails(event);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
    }

    private static void renderTrails(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var camera = event.getCamera().getPosition();
        for (var trail : TRAILS.values()) {
            var missile = trail.missile;
            if (missile.isRemoved() || !missile.isPowered() || missile.isEjecting()) {
                continue;
            }
            Vec3 nozzle = nozzleOf(missile, partial);
            if (wet(missile, nozzle)) {
                continue;
            }
            double distance = camera.distanceTo(nozzle);
            if (!event.getFrustum().isVisible(missile.getBoundingBox().inflate(7))) {
                continue;
            }
            float age = missile.tickCount + partial - trail.ignitionTick;
            float strength = Mth.clamp(age / 3f, 0, 1) * (1 + .22f * (float) Math.exp(-age * .16));
            if (WarnauticsClientConfig.missileLights() && distance <= PARTICLE_RANGE) {
                com.cbc_more_content.client.veil.VeilMissileFx.follow(
                        missile, nozzle, strength * (.94f + .06f * Mth.sin((missile.tickCount + partial) * 2.1f)));
            }
            if (!WarnauticsClientConfig.missilePlume()) {
                continue;
            }
            Vec3 relative = nozzle.subtract(camera);
            var matrix = new Matrix4f(event.getModelViewMatrix())
                    .translate((float) relative.x, (float) relative.y, (float) relative.z)
                    .rotateY((90 - Mth.rotLerp(partial, missile.yRotO, missile.getYRot())) * Mth.DEG_TO_RAD)
                    .rotateZ(Mth.lerp(partial, missile.xRotO, missile.getXRot()) * Mth.DEG_TO_RAD);
            com.cbc_more_content.client.veil.VeilMissilePlume.render(
                    matrix,
                    event.getProjectionMatrix(),
                    (missile.tickCount + partial) / 20f + (missile.getId() % 97) * .37f,
                    strength,
                    distance < 6 || distance > 64 || mc.options.particles().get() != ParticleStatus.ALL,
                    terrainFogStart,
                    terrainFogEnd);
        }
    }

    private static boolean wet(CruiseMissileProjectile missile, Vec3 nozzle) {
        return missile.isInWater()
                || missile.level().getFluidState(BlockPos.containing(nozzle)).is(FluidTags.WATER);
    }

    public static Vec3 heading(CruiseMissileProjectile missile, float partial) {
        float yaw = (Mth.rotLerp(partial, missile.yRotO, missile.getYRot()) + 90) * Mth.DEG_TO_RAD;
        float pitch = -Mth.lerp(partial, missile.xRotO, missile.getXRot()) * Mth.DEG_TO_RAD;
        return new Vec3(Mth.cos(yaw) * Mth.cos(pitch), Mth.sin(pitch), Mth.sin(yaw) * Mth.cos(pitch));
    }

    public static Vec3 nozzleOf(CruiseMissileProjectile missile, float partial) {
        // Match LevelRenderer.renderEntity, including interpolation altered by ship compatibility.
        var renderedPosition = new Vec3(
                Mth.lerp(partial, missile.xOld, missile.getX()),
                Mth.lerp(partial, missile.yOld, missile.getY()),
                Mth.lerp(partial, missile.zOld, missile.getZ()));
        return renderedPosition.subtract(heading(missile, partial).scale(NOZZLE_OFFSET));
    }

    private static final class Trail {
        final CruiseMissileProjectile missile;
        Vec3 nozzle;
        Vec3 emittedNozzle;
        boolean powered;
        int cooldown;
        int ignitionTick;

        Trail(CruiseMissileProjectile missile, Vec3 nozzle) {
            this.missile = missile;
            this.nozzle = nozzle;
            this.emittedNozzle = nozzle;
            this.ignitionTick = missile.tickCount;
        }
    }
}
