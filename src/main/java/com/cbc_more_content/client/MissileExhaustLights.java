package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.config.WarnauticsClientConfig;
import com.cbc_more_content.munitions.Aim9Projectile;
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
import net.minecraft.world.entity.Entity;
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
    private static final double MAX_EFFECT_RANGE = 512;
    private static final int MAX_DETAILED_MISSILES = 24;
    /** Contrails form this far above sea level, where the air is cold enough. */
    private static final double CONTRAIL_ABOVE_SEA = 70;

    private static final Map<Integer, Trail> TRAILS = new HashMap<>();
    private static ClientLevel previousLevel;
    private static final boolean VEIL = ModList.get().isLoaded("veil");
    private static float terrainFogStart = Float.MAX_VALUE;
    private static float terrainFogEnd = Float.MAX_VALUE;
    private static float pixelsAtOneBlock = 500;

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
        double effectRange =
                Math.min(MAX_EFFECT_RANGE, mc.options.renderDistance().get() * 16.0 + 16.0);
        // Follow the entities the server actually tracks. An extra sphere around the player
        // used to turn off a still-burning motor halfway through a vertical launch.
        var missiles = new ArrayList<Entity>();
        for (var entity : mc.level.entitiesForRendering()) {
            if ((entity instanceof CruiseMissileProjectile || entity instanceof Aim9Projectile)
                    && !entity.isRemoved()) {
                missiles.add(entity);
            }
        }
        missiles.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(camera)));
        Set<Integer> seen = new HashSet<>();
        int budget = 96;
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
            boolean powered = powered(missile) && !ejecting(missile) && !wet;
            if (powered && !trail.powered) {
                trail.ignitionTick = missile.tickCount;
            }
            if (!powered && trail.powered) {
                trail.cooldown = 10;
            }
            double distance = nozzle.distanceTo(camera);
            var setting = mc.options.particles().get();
            double pixels = pixelsAtOneBlock / Math.max(1, distance);
            int stride = setting == ParticleStatus.MINIMAL ? 3 : pixels < 3 ? 3 : pixels < 8 ? 2 : 1;
            boolean detailedVolume = volume && pixels >= 2.5 && distance < effectRange;
            if (!wet
                    && budget > 0
                    && count <= MAX_DETAILED_MISSILES
                    && distance <= effectRange
                    && missile.tickCount % stride == 0) {
                if (ejecting(missile)) {
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
                    // The booster lays a thick white column; the turbofan sustainer leaves
                    // little more than a faint thread of exhaust behind it.
                    // Close enough together that the trail reads as one continuous ribbon.
                    double spacing = sustainer(missile)
                            ? (setting == ParticleStatus.MINIMAL ? 3.0 : 1.1)
                            : sustainGrain(missile)
                                    ? (setting == ParticleStatus.MINIMAL ? 2.0 : .8)
                                    : setting == ParticleStatus.MINIMAL ? 1.0 : pixels < 4 ? .8 : .45;
                    int samples =
                            Math.min(budget, Math.clamp((int) Math.ceil(nozzle.distanceTo(previous) / spacing), 1, 16));
                    Vec3 back = heading(missile, 1).scale(-1);
                    for (int i = 0; i < samples; i++) {
                        Vec3 at = previous.lerp(nozzle, (i + 1.0) / samples);
                        // The glowing plume is continuous; cool exhaust starts beyond its bright core.
                        emit(
                                mc.level,
                                ModParticles.MISSILE_SMOKE.get(),
                                at.add(back.scale(
                                        powered
                                                ? missile instanceof Aim9Projectile
                                                        ? 2.5
                                                        : sustainer(missile) ? 1.4 : 3.3
                                                : .2)),
                                back.scale(.075),
                                .065);
                        if (powered && !detailedVolume && !sustainer(missile)) {
                            emit(mc.level, ModParticles.MISSILE_EXHAUST.get(), at, back.scale(.18), .06);
                        }
                    }
                    budget -= samples;
                    trail.emittedNozzle = nozzle;
                }
                if (missile instanceof CruiseMissileProjectile cruise && setting != ParticleStatus.MINIMAL) {
                    budget -= airframeEffects(mc.level, cruise, trail, nozzle, distance);
                }
                if (powered && !sustainer(missile) && !sustainGrain(missile) && setting != ParticleStatus.MINIMAL) {
                    budget -= jetImpingement(mc.level, missile, nozzle);
                }
            }
            if (missile instanceof CruiseMissileProjectile cruise
                    && !wet
                    && distance <= effectRange
                    && setting != ParticleStatus.MINIMAL) {
                if (powered && !trail.powered && trail.phase == CruiseMissileProjectile.PHASE_EJECT) {
                    launchBlast(mc.level, cruise, nozzle);
                }
                if (powered && sustainer(cruise)) {
                    contrail(mc.level, cruise, trail, nozzle);
                }
                vapourRings(mc.level, cruise, trail, nozzle, distance);
            }
            if (missile instanceof CruiseMissileProjectile cruise) {
                if (trail.phase == CruiseMissileProjectile.PHASE_BOOST
                        && cruise.phase() != CruiseMissileProjectile.PHASE_BOOST
                        && !wet
                        && distance <= effectRange) {
                    separationPuff(mc.level, cruise, nozzle);
                }
                trail.phase = cruise.phase();
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
            pixelsAtOneBlock = Math.abs(event.getProjectionMatrix().m11())
                    * Minecraft.getInstance().getMainRenderTarget().height
                    * .5f;
            return;
        }
        // Clouds are drawn immediately after particles. Drawing the plume later puts it
        // over their composited colour even when the missile is physically behind them.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != previousLevel || TRAILS.isEmpty()) {
            return;
        }
        // Fabulous can leave the particle target bound here. Use the main scene depth;
        // its later cloud pass can then compose in front of a more distant plume.
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
        int detailed = 0;
        for (var trail : TRAILS.values()) {
            var missile = trail.missile;
            if (missile.isRemoved() || !powered(missile) || ejecting(missile)) {
                continue;
            }
            Vec3 nozzle = nozzleOf(missile, partial);
            if (wet(missile, nozzle)) {
                continue;
            }
            double distance = camera.distanceTo(nozzle);
            // The plume runs nine blocks behind the airframe: keep drawing it while only
            // the jet, not the missile itself, is on screen.
            if (!event.getFrustum().isVisible(missile.getBoundingBox().inflate(10))) {
                continue;
            }
            float age = missile.tickCount + partial - trail.ignitionTick;
            float strength = Mth.clamp(age / 3f, 0, 1) * (1 + .22f * (float) Math.exp(-age * .16));
            boolean turbofan = sustainer(missile);
            if (turbofan) {
                // A turbofan, not a rocket: the shader draws only a faint shimmer.
                strength *= .55f;
            } else if (sustainGrain(missile)) {
                // The interceptor's sustain grain: still a rocket, but half the boost flame.
                strength *= .6f;
            }
            if (WarnauticsClientConfig.missileLights() && distance <= 64) {
                com.cbc_more_content.client.veil.VeilMissileFx.follow(
                        missile, nozzle, strength * (.94f + .06f * Mth.sin((missile.tickCount + partial) * 2.1f)));
            }
            float pixels = pixelsAtOneBlock / (float) Math.max(1, distance);
            // Keep a tiny, low-sample incandescent core visible beyond the point
            // where detached smoke particles and the full volume stop paying off.
            if (!WarnauticsClientConfig.missilePlume()) {
                continue;
            }
            // Screen size selects cost rather than hiding a still tracked source.
            // Beyond the detail budget, other missiles retain the cheap core.
            int samples = mc.options.particles().get() == ParticleStatus.MINIMAL || detailed++ >= MAX_DETAILED_MISSILES
                    ? 8
                    : pixels < 2.5f ? 10 : pixels > 18 ? 40 : pixels > 7 ? 28 : 16;
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
                    samples,
                    terrainFogStart,
                    terrainFogEnd,
                    missile instanceof Aim9Projectile
                            ? com.cbc_more_content.client.veil.VeilMissilePlume.INTERCEPTOR
                            : turbofan
                                    ? com.cbc_more_content.client.veil.VeilMissilePlume.TURBOFAN
                                    : com.cbc_more_content.client.veil.VeilMissilePlume.BOOSTER,
                    WarnauticsClientConfig.plasmaPlume() ? 1 : 0);
        }
    }

    /** A cruise missile past booster burnout, running on its turbofan. */
    static boolean sustainer(Entity missile) {
        return missile instanceof CruiseMissileProjectile cruise
                && (cruise.phase() == CruiseMissileProjectile.PHASE_CRUISE
                        || cruise.phase() == CruiseMissileProjectile.PHASE_TERMINAL);
    }

    /**
     * Where the rocket jet strikes the ground or a wall: smoke boils out across the surface
     * and the flame splashes back off it, as on a launch pad or a test stand.
     *
     * @return particles spent from the shared budget
     */
    private static int jetImpingement(ClientLevel level, Entity missile, Vec3 nozzle) {
        Vec3 back = heading(missile, 1).scale(-1);
        double reach = missile instanceof Aim9Projectile ? 5.0 : 8.5;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(
                nozzle,
                nozzle.add(back.scale(reach)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                missile));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return 0;
        }
        Vec3 at = hit.getLocation();
        double strength = 1 - at.distanceTo(nozzle) / reach;
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        // Two directions across the struck surface, for the outward roll of smoke.
        Vec3 across = Math.abs(normal.y) > .5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = normal.cross(across).normalize();
        Vec3 w = normal.cross(u).normalize();
        Vec3 surface = at.add(normal.scale(.3));
        int puffs = 2 + (int) Math.round(strength * 3);
        for (int i = 0; i < puffs; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            Vec3 out = u.scale(Math.cos(angle)).add(w.scale(Math.sin(angle)));
            double speed = .18 + level.random.nextDouble() * .25 * (.4 + strength);
            Vec3 velocity = out.scale(speed).add(normal.scale(.03 + level.random.nextDouble() * .05));
            level.addParticle(
                    ModParticles.MISSILE_SMOKE.get(),
                    true,
                    surface.x,
                    surface.y,
                    surface.z,
                    velocity.x,
                    velocity.y,
                    velocity.z);
        }
        if (level.random.nextFloat() < .35f + strength * .4f) {
            level.addParticle(
                    new com.cbc_more_content.effects.BlastSmokeData((float) (1.1 + strength * 1.3)),
                    true,
                    surface.x,
                    surface.y,
                    surface.z,
                    normal.x * .04,
                    normal.y * .04,
                    normal.z * .04);
        }
        if (strength > .45) {
            // The flame splashing back off the surface.
            emit(level, ModParticles.MISSILE_EXHAUST.get(), surface, normal.scale(.06), .25);
        }
        return puffs + 1;
    }

    /** An interceptor past its boost grain, on the weaker sustain burn. */
    static boolean sustainGrain(Entity missile) {
        return missile instanceof Aim9Projectile aim9 && aim9.isPowered() && !aim9.isBoosting();
    }

    /**
     * Ignition of the booster just off the rail. Near the ground the blast hits it: a ring
     * of dust and smoke rolls out across the surface, with the soil itself thrown up.
     */
    private static void launchBlast(ClientLevel level, CruiseMissileProjectile missile, Vec3 nozzle) {
        Vec3 back = heading(missile, 1).scale(-1);
        for (int i = 0; i < 10; i++) {
            emit(level, ModParticles.MISSILE_GAS.get(), nozzle.add(back.scale(.8)), back.scale(.12), .3);
        }
        int x = Mth.floor(nozzle.x), z = Mth.floor(nozzle.z);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        double height = nozzle.y - surface;
        if (height < -1 || height > 12) {
            return;
        }
        float strength = (float) Mth.clamp(1 - height / 12, .25, 1);
        var ground = level.getBlockState(new BlockPos(x, surface - 1, z));
        int puffs = 10 + Math.round(strength * 8);
        for (int i = 0; i < puffs; i++) {
            double angle = i * Math.PI * 2 / puffs + level.random.nextDouble() * .3;
            double reach = .9 + level.random.nextDouble() * .6;
            Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            Vec3 at = new Vec3(nozzle.x, surface + .4, nozzle.z).add(out.scale(.8));
            level.addParticle(
                    new com.cbc_more_content.effects.BlastSmokeData(1.2f + strength * 1.4f),
                    true,
                    at.x,
                    at.y,
                    at.z,
                    out.x * reach * .45 * strength,
                    .02 + level.random.nextDouble() * .04,
                    out.z * reach * .45 * strength);
            if (!ground.isAir()) {
                level.addParticle(
                        new net.minecraft.core.particles.BlockParticleOption(
                                net.minecraft.core.particles.ParticleTypes.BLOCK, ground),
                        at.x,
                        at.y,
                        at.z,
                        out.x * reach * .6,
                        .2 + level.random.nextDouble() * .3 * strength,
                        out.z * reach * .6);
            }
        }
    }

    /**
     * A turbofan high up leaves a condensation trail: white, thin at first, spreading and
     * hanging in the sky long after the missile has passed.
     */
    private static void contrail(ClientLevel level, CruiseMissileProjectile missile, Trail trail, Vec3 nozzle) {
        double contrailFloor = level.getSeaLevel() + CONTRAIL_ABOVE_SEA;
        if (nozzle.y < contrailFloor) {
            trail.contrailFrom = nozzle;
            return;
        }
        // Fades in over the first blocks of altitude instead of switching on at a line.
        double thickness = Mth.clamp((nozzle.y - contrailFloor) / 12, .15, 1);
        Vec3 from = trail.contrailFrom == null || trail.contrailFrom.distanceToSqr(nozzle) > 144
                ? nozzle
                : trail.contrailFrom;
        Vec3 back = heading(missile, 1).scale(-1);
        int samples = Math.clamp((int) Math.ceil(from.distanceTo(nozzle) / 1.6), 1, 6);
        for (int i = 0; i < samples; i++) {
            if (level.random.nextDouble() > thickness) {
                continue;
            }
            Vec3 at = from.lerp(nozzle, (i + 1.0) / samples).add(back.scale(2.2));
            emit(level, ModParticles.MISSILE_CONTRAIL.get(), at, back.scale(.02), .02);
        }
        trail.contrailFrom = nozzle;
    }

    /**
     * Condensation rings around the body: a vapour collar when the missile tips over into
     * its terminal dive, and in hard, high-speed turns, a ring every few ticks.
     */
    private static void vapourRings(
            ClientLevel level, CruiseMissileProjectile missile, Trail trail, Vec3 nozzle, double distance) {
        if (distance > 140 || !missile.isPowered()) {
            return;
        }
        boolean diving = missile.phase() == CruiseMissileProjectile.PHASE_TERMINAL
                && trail.phase == CruiseMissileProjectile.PHASE_CRUISE;
        boolean pulling = sustainer(missile)
                && Math.abs(missile.roll(1)) > .95f
                && missile.getDeltaMovement().length() > 3.6
                && missile.tickCount % 3 == 0;
        if (!diving && !pulling) {
            return;
        }
        Vec3 forward = heading(missile, 1);
        Vec3 side = forward.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-4) {
            side = forward.cross(new Vec3(1, 0, 0));
        }
        side = side.normalize();
        Vec3 up = side.cross(forward).normalize();
        Vec3 collar = nozzle.add(forward.scale(NOZZLE_OFFSET + .3));
        int count = diving ? 16 : 7;
        double radius = diving ? .75 : .55;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            Vec3 at = collar.add(side.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
            Vec3 streak = forward.scale(-.7);
            level.addParticle(ModParticles.MISSILE_VAPOR.get(), true, at.x, at.y, at.z, streak.x, streak.y, streak.z);
        }
    }

    /** Burnout: a cloud of booster gas and a few smoke puffs where the casing let go. */
    private static void separationPuff(ClientLevel level, CruiseMissileProjectile missile, Vec3 nozzle) {
        Vec3 back = heading(missile, 1).scale(-1);
        for (int i = 0; i < 12; i++) {
            emit(level, ModParticles.MISSILE_GAS.get(), nozzle.add(back.scale(.6)), back.scale(.05), .22);
        }
        for (int i = 0; i < 5; i++) {
            emit(level, ModParticles.MISSILE_SMOKE.get(), nozzle.add(back.scale(1.2 + i * .5)), back.scale(.03), .1);
        }
        emit(level, ModParticles.MISSILE_EXHAUST.get(), nozzle, back.scale(.05), .05);
    }

    /**
     * Effects of the airframe moving through the air rather than of its motor: vapour from
     * the wingtips in a hard turn, and the sea or ground torn up when it skims low.
     *
     * @return particles spent from the shared budget
     */
    private static int airframeEffects(
            ClientLevel level, CruiseMissileProjectile missile, Trail trail, Vec3 nozzle, double distance) {
        if (!missile.isPowered() || missile.isEjecting() || distance > 160) {
            return 0;
        }
        int spent = 0;
        Vec3 forward = heading(missile, 1);
        Vec3 centre = nozzle.add(forward.scale(NOZZLE_OFFSET));
        float roll = missile.roll(1);
        if (Math.abs(roll) > .55f && sustainer(missile)) {
            // Wings sit across the middle cell. Tilt the span with the bank.
            Vec3 side = forward.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() > 1.0E-4) {
                side = side.normalize();
                Vec3 up = side.cross(forward).normalize();
                // side is the right wing; a positive (left) bank raises it.
                Vec3 span =
                        side.scale(Mth.cos(roll)).add(up.scale(Mth.sin(roll))).scale(.95);
                Vec3 streak = forward.scale(-.9);
                for (int sign = -1; sign <= 1; sign += 2) {
                    Vec3 tip = centre.add(span.scale(sign));
                    level.addParticle(
                            ModParticles.MISSILE_VAPOR.get(), true, tip.x, tip.y, tip.z, streak.x, streak.y, streak.z);
                    spent++;
                }
            }
        }
        // Skimming: read the surface straight below and throw up spray or dust.
        int x = Mth.floor(centre.x), z = Mth.floor(centre.z);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return spent;
        }
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        double height = centre.y - surface;
        if (height < 0 || height > 7) {
            return spent;
        }
        float strength = (float) (1 - height / 7);
        BlockPos below = new BlockPos(x, surface - 1, z);
        var fluid = level.getFluidState(below);
        int count = 1 + Math.round(strength * 3);
        for (int i = 0; i < count; i++) {
            double ox = (level.random.nextDouble() - .5) * 1.6, oz = (level.random.nextDouble() - .5) * 1.6;
            if (fluid.is(FluidTags.WATER)) {
                level.addParticle(
                        net.minecraft.core.particles.ParticleTypes.SPLASH,
                        centre.x + ox,
                        surface + .1,
                        centre.z + oz,
                        -forward.x * .2,
                        .15 + strength * .25,
                        -forward.z * .2);
                if (i == 0) {
                    level.addParticle(
                            net.minecraft.core.particles.ParticleTypes.CLOUD,
                            centre.x + ox,
                            surface + .3,
                            centre.z + oz,
                            -forward.x * .05,
                            .02,
                            -forward.z * .05);
                }
            } else {
                var state = level.getBlockState(below);
                if (state.isAir()) {
                    break;
                }
                level.addParticle(
                        new net.minecraft.core.particles.BlockParticleOption(
                                net.minecraft.core.particles.ParticleTypes.BLOCK, state),
                        centre.x + ox,
                        surface + .1,
                        centre.z + oz,
                        ox * .2 - forward.x * .1,
                        .12 + strength * .2,
                        oz * .2 - forward.z * .1);
            }
            spent++;
        }
        return spent;
    }

    private static boolean powered(Entity missile) {
        return missile instanceof CruiseMissileProjectile cruise
                ? cruise.isPowered()
                : ((Aim9Projectile) missile).isPowered();
    }

    private static boolean ejecting(Entity missile) {
        return missile instanceof CruiseMissileProjectile cruise
                ? cruise.isEjecting()
                : ((Aim9Projectile) missile).isEjecting();
    }

    private static boolean wet(Entity missile, Vec3 nozzle) {
        return missile.isInWater()
                || missile.level().getFluidState(BlockPos.containing(nozzle)).is(FluidTags.WATER);
    }

    public static Vec3 heading(Entity missile, float partial) {
        float yaw = (Mth.rotLerp(partial, missile.yRotO, missile.getYRot()) + 90) * Mth.DEG_TO_RAD;
        float pitch = -Mth.lerp(partial, missile.xRotO, missile.getXRot()) * Mth.DEG_TO_RAD;
        return new Vec3(Mth.cos(yaw) * Mth.cos(pitch), Mth.sin(pitch), Mth.sin(yaw) * Mth.cos(pitch));
    }

    public static Vec3 nozzleOf(Entity missile, float partial) {
        // Match LevelRenderer.renderEntity, including interpolation altered by ship compatibility.
        var renderedPosition = new Vec3(
                Mth.lerp(partial, missile.xOld, missile.getX()),
                Mth.lerp(partial, missile.yOld, missile.getY()),
                Mth.lerp(partial, missile.zOld, missile.getZ()));
        return renderedPosition.subtract(heading(missile, partial).scale(NOZZLE_OFFSET));
    }

    private static final class Trail {
        final Entity missile;
        Vec3 nozzle;
        Vec3 emittedNozzle;
        boolean powered;
        int cooldown;
        int ignitionTick;
        byte phase = -1;
        Vec3 contrailFrom;

        Trail(Entity missile, Vec3 nozzle) {
            this.missile = missile;
            this.nozzle = nozzle;
            this.emittedNozzle = nozzle;
            this.ignitionTick = missile.tickCount;
        }
    }
}
