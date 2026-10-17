package com.cbc_more_content.effects;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.network.BombFlashPayload;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;
import rbasamoyai.createbigcannons.effects.particles.explosions.ShellBlastWaveEffectParticleData;
import rbasamoyai.createbigcannons.effects.particles.explosions.ShellExplosionCloudParticleData;
import rbasamoyai.createbigcannons.effects.particles.smoke.ShellExplosionSmokeParticleData;

/**
 * CBC-style blast FX. Boom audio is always played via {@link ServerLevel#playSound}
 * (CBC blast-wave particles alone can miss the client). Particles keep cloud / shake.
 */
public final class BombBlastFx {
    private BombBlastFx() {}

    public static void play(ServerLevel level, Vec3 pos, BombSize size, float blockPower) {
        // Prefer BombExplosionHandler path which supplies a shared burst snapshot.
        play(level, pos, size, blockPower, BombBurstBudget.begin(level));
    }

    public static void play(
            ServerLevel level, Vec3 pos, BombSize size, float blockPower, BombBurstBudget.Snapshot budget) {
        play(level, pos, size, blockPower, budget, false);
    }

    public static void play(
            ServerLevel level,
            Vec3 pos,
            BombSize size,
            float blockPower,
            BombBurstBudget.Snapshot budget,
            boolean torpedo) {
        play(level, pos, size, blockPower, budget, torpedo, false);
    }

    private static void play(
            ServerLevel level,
            Vec3 pos,
            BombSize size,
            float blockPower,
            BombBurstBudget.Snapshot budget,
            boolean torpedo,
            boolean breaching) {
        waterBurst(level, pos, blockPower);
        FxProfile profile = FxProfile.of(size, blockPower, level.random, torpedo);
        BombBurstBudget.Lod lod = budget.lod();

        // Reliable boom — do not depend on CBC blast-wave particle reaching the client.
        level.playSound(null, pos.x, pos.y, pos.z, profile.sound, SoundSource.BLOCKS, profile.volume, profile.pitch);

        if (size == BombSize.LARGE) {
            level.playSound(
                    null,
                    pos.x,
                    pos.y,
                    pos.z,
                    ModSounds.BOMB_EXPLOSION_LARGE_RUMBLE.get(),
                    SoundSource.BLOCKS,
                    profile.volume * 0.85f,
                    0.55f);
        }

        if (lod.allowWarRumble(level)) {
            level.playSound(
                    null,
                    pos.x,
                    pos.y,
                    pos.z,
                    ModSounds.BOMB_WAR_RUMBLE.get(),
                    SoundSource.WEATHER,
                    profile.warVolume * 0.55f,
                    profile.warPitch);
        }

        spawnHotFlashParticles(level, pos, size, lod);
        dispatchLookFlash(level, pos, size, budget);

        Holder<SoundEvent> boomHolder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(profile.sound);

        // Volume 0: shake wave only — audio already fired via playSound above.
        ShellBlastWaveEffectParticleData nearBlast = new ShellBlastWaveEffectParticleData(
                profile.blastRadius,
                boomHolder,
                SoundSource.BLOCKS,
                0.0f,
                profile.pitch,
                profile.airAbsorption,
                profile.shakePower);

        for (ServerPlayer player : level.players()) {
            double distSqr = player.distanceToSqr(pos);
            if (distSqr > profile.farSyncDistSqr) {
                continue;
            }

            double dist = Math.sqrt(distSqr);
            if (dist <= profile.nearSoundDist && dist >= 2.0D) {
                level.sendParticles(player, nearBlast, true, pos.x, pos.y, pos.z, 0, 0.0D, 0.0D, 0.0D, 1.0D);
            }

            if (dist <= profile.shakeFalloff) {
                float proximity =
                        (float) Mth.clamp(1.0D - distSqr / (profile.shakeFalloff * profile.shakeFalloff), 0.0D, 1.0D);
                float shake = Math.min(profile.shakeLimit, profile.shakePower * proximity * proximity);
                budget.offerShake(player, shake, pos);
            }
        }

        // Smoke after the flash peak so clouds don't cover the fireball. Under a
        // detonation burst only a bounded number of delayed tasks are necessary.

        float smokeScale = breaching ? Math.max(3.7f, blockPower * .49f) : profile.smokeScale;
        schedule(level, 5, () -> spawnDelayedSmoke(level, pos, size, profile, smokeScale, lod, torpedo, breaching));
    }

    /**
     * A Claymore going off. Unlike every other charge here the blast has a direction: a
     * pound and a half of explosive behind a curved plate of steel balls throws almost all
     * of it forward. What you see is a hard white-orange flash, a jet of grey smoke punched
     * out along the fan, and clods jumping up from the ground across a sixty degree arc as
     * the balls arrive (near ground first, then farther out) with the rattle of them
     * striking whatever is there. Behind it only a short puff of smoke.
     */
    public static void playClaymore(ServerLevel level, Vec3 pos, Vec3 forward, double halfAngle, double range) {
        RandomSource random = level.random;
        Vec3 flatForward = new Vec3(forward.x, 0.0D, forward.z);
        Vec3 ahead = flatForward.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : flatForward.normalize();
        Vec3 side = new Vec3(-ahead.z, 0.0D, ahead.x);
        BombBurstBudget.Snapshot budget = BombBurstBudget.begin(level);
        int detail =
                switch (budget.lod()) {
                    case FULL -> 4;
                    case REDUCED -> 3;
                    case MINIMAL -> 2;
                    case ESSENTIAL -> 1;
                };

        // A sharp, high crack rather than the deep roll of a buried charge.
        level.playSound(
                null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 5.0f, 1.25f);
        level.playSound(
                null, pos.x, pos.y, pos.z, ModSounds.BOMB_EXPLOSION_LARGE.get(), SoundSource.BLOCKS, 8.0f, 1.35f);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2.4f, 0.8f);

        // The flash: hot, short and thrown forward off the face of the plate.
        DustParticleOptions hot = new DustParticleOptions(new Vector3f(1.0f, 0.78f, 0.35f), 1.3f);
        emitFar(level, hot, pos.x, pos.y, pos.z, 4 * detail, 0.25D, 0.2D, 0.25D, 0.0D);
        for (int i = 0; i < 2 * detail; i++) {
            Vec3 dir = fan(ahead, side, halfAngle * 0.8D, random);
            double speed = 0.3D + random.nextDouble() * 0.3D;
            directed(level, hot, pos.x, pos.y, pos.z, dir.x * speed, dir.y * speed, dir.z * speed);
        }
        BombFlashPayload flash = new BombFlashPayload(
                pos.x + ahead.x, pos.y, pos.z + ahead.z, budget.lod().flashScale() * 0.55f, (byte)
                        BombSize.SMALL.ordinal());

        // The smoke jet along the fan: fast puffs that slow and spread as they go.
        for (int i = 0; i < 3 * detail; i++) {
            Vec3 dir = fan(ahead, side, halfAngle * 0.7D, random);
            double speed = 0.25D + random.nextDouble() * 0.55D;
            directed(
                    level,
                    new BlastSmokeData(0.9f + random.nextFloat() * 0.8f),
                    pos.x + dir.x * 0.4D,
                    pos.y,
                    pos.z + dir.z * 0.4D,
                    dir.x * speed,
                    0.02D + random.nextDouble() * 0.04D,
                    dir.z * speed);
        }
        // The back-blast: a short grey puff out of the rear and the sides.
        for (int i = 0; i < detail; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            directed(
                    level,
                    new BlastSmokeData(0.8f + random.nextFloat() * 0.5f),
                    pos.x,
                    pos.y,
                    pos.z,
                    Math.cos(angle) * 0.12D - ahead.x * 0.08D,
                    0.03D,
                    Math.sin(angle) * 0.12D - ahead.z * 0.08D);
        }

        for (ServerPlayer player : level.players()) {
            double distSqr = player.distanceToSqr(pos);
            if (distSqr <= 96.0D * 96.0D) {
                PacketDistributor.sendToPlayer(player, flash);
            }
            if (distSqr <= 24.0D * 24.0D) {
                float proximity = (float) Mth.clamp(1.0D - Math.sqrt(distSqr) / 24.0D, 0.0D, 1.0D);
                budget.offerShake(player, 9.0f * proximity * proximity, pos);
            }
        }

        // The balls reach the ground in front over the next few ticks: clods jump up in
        // bands, nearest first, and steel rattles off whatever stands out there.
        double groundY = Math.floor(pos.y) + 0.05D;
        int bands = 5;
        double bandWidth = (range - 2.5D) / bands;
        for (int band = 0; band < bands; band++) {
            double near = 2.5D + band * bandWidth;
            double far = near + bandWidth;
            int delay = (int) Math.max(1, Math.round(near / 2.4D));
            int count = (2 + band) * detail / 2;
            boolean rattle = budget.lod() == BombBurstBudget.Lod.FULL;
            schedule(level, delay, () -> {
                for (int i = 0; i < count; i++) {
                    Vec3 dir = fan(ahead, side, halfAngle, random);
                    double distance = Mth.lerp(random.nextDouble(), near, far);
                    double x = pos.x + dir.x * distance;
                    double z = pos.z + dir.z * distance;
                    // Settle on whatever surface is there, a block either way.
                    var at = net.minecraft.core.BlockPos.containing(x, groundY, z);
                    double y = groundY;
                    if (!level.getBlockState(at).isAir()) {
                        y += 1.0D;
                    } else if (level.getBlockState(at.below()).isAir()) {
                        y -= 1.0D;
                    }
                    var ground = level.getBlockState(net.minecraft.core.BlockPos.containing(x, y - 0.5D, z));
                    if (ground.isAir() || !ground.getFluidState().isEmpty()) {
                        continue;
                    }
                    var soil = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground);
                    directed(
                            level,
                            soil,
                            x,
                            y + 0.1D,
                            z,
                            dir.x * 0.08D,
                            0.18D + random.nextDouble() * 0.2D,
                            dir.z * 0.08D);
                }
                if (!rattle) {
                    return;
                }
                Vec3 middle = pos.add(ahead.scale((near + far) * 0.5D));
                level.playSound(
                        null,
                        middle.x,
                        middle.y,
                        middle.z,
                        SoundEvents.IRON_GOLEM_DAMAGE,
                        SoundSource.BLOCKS,
                        0.7f,
                        1.6f + random.nextFloat() * 0.3f);
            });
        }
    }

    /** A direction inside the claymore's horizontal fan, level to a few degrees up. */
    private static Vec3 fan(Vec3 ahead, Vec3 side, double halfAngle, RandomSource random) {
        double yaw = (random.nextDouble() * 2.0D - 1.0D) * halfAngle;
        double pitch = random.nextDouble() * 0.12D;
        Vec3 flat = ahead.scale(Math.cos(yaw)).add(side.scale(Math.sin(yaw)));
        return flat.scale(Math.cos(pitch)).add(0.0D, Math.sin(pitch), 0.0D);
    }

    /**
     * An anti-tank mine: a heavy charge going off under whatever drove onto it. The ground
     * around it throws the blast upward, so it reads as a column: a fountain of the very
     * soil it was buried in, a dark smoke stalk that mushrooms out, a fireball at the base
     * and a ring of dust running out across the ground, then earth raining back down and
     * smoke hanging over the crater.
     */
    public static void playAntiTankMine(
            ServerLevel level, Vec3 pos, float blockPower, BombBurstBudget.Snapshot budget) {
        waterBurst(level, pos, blockPower);
        RandomSource random = level.random;
        float pitch = 0.7f + random.nextFloat() * 0.08f;
        float volume = Math.max(14.0f, blockPower * 3.0f);
        level.playSound(
                null, pos.x, pos.y, pos.z, ModSounds.BOMB_EXPLOSION_LARGE.get(), SoundSource.BLOCKS, volume, pitch);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 6.0f, .55f);

        int detail =
                switch (budget.lod()) {
                    case FULL -> 4;
                    case REDUCED -> 3;
                    case MINIMAL -> 2;
                    case ESSENTIAL -> 1;
                };
        var groundPos = net.minecraft.core.BlockPos.containing(pos).below();
        var ground = level.getBlockState(groundPos);
        if (ground.isAir() || !ground.getFluidState().isEmpty()) {
            ground = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        }
        var soil = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground);

        // Fireball at the base.
        DustParticleOptions hot = new DustParticleOptions(new Vector3f(1.0f, 0.62f, 0.12f), 1.6f);
        emitFar(level, hot, pos.x, pos.y + 0.4D, pos.z, 10 * detail, 0.9D, 0.5D, 0.9D, 0.02D);
        emitFar(
                level,
                new ShellExplosionCloudParticleData(2.4f, false),
                pos.x,
                pos.y + 0.6D,
                pos.z,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D);

        // The soil fountain: clods of the ground itself, flung up in a narrow cone.
        for (int i = 0; i < 18 * detail; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double spread = random.nextDouble() * .35;
            directed(
                    level,
                    soil,
                    pos.x + Math.cos(angle) * .4,
                    pos.y + .2,
                    pos.z + Math.sin(angle) * .4,
                    Math.cos(angle) * spread,
                    .7 + random.nextDouble() * .9,
                    Math.sin(angle) * spread);
        }
        // The smoke stalk: dark puffs rising at different speeds, so it stands as a column.
        for (int i = 0; i < 4 * detail; i++) {
            directed(
                    level,
                    new BlastSmokeData(2.0f + random.nextFloat() * 1.6f),
                    pos.x + (random.nextDouble() - .5) * .8,
                    pos.y + .5 + i * .45,
                    pos.z + (random.nextDouble() - .5) * .8,
                    (random.nextDouble() - .5) * .04,
                    .12 + random.nextDouble() * .22,
                    (random.nextDouble() - .5) * .04);
        }
        // The ground ring: dust and soil racing out across the surface.
        int ring = 8 + 4 * detail;
        for (int i = 0; i < ring; i++) {
            double angle = i * Math.PI * 2 / ring + random.nextDouble() * .2;
            double speed = .45 + random.nextDouble() * .2;
            directed(
                    level,
                    new BlastSmokeData(1.4f + random.nextFloat() * .6f),
                    pos.x + Math.cos(angle) * .8,
                    pos.y + .15,
                    pos.z + Math.sin(angle) * .8,
                    Math.cos(angle) * speed,
                    .02,
                    Math.sin(angle) * speed);
            directed(
                    level,
                    soil,
                    pos.x + Math.cos(angle) * .6,
                    pos.y + .2,
                    pos.z + Math.sin(angle) * .6,
                    Math.cos(angle) * (speed + .1),
                    .25 + random.nextDouble() * .25,
                    Math.sin(angle) * (speed + .1));
        }

        // Flash, shock wave and a hard shake for anyone close.
        float intensity = budget.lod().flashScale();
        BombFlashPayload payload =
                new BombFlashPayload(pos.x, pos.y, pos.z, intensity, (byte) BombSize.MEDIUM.ordinal());
        Holder<SoundEvent> boomHolder =
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(ModSounds.BOMB_EXPLOSION_LARGE.get());
        ShellBlastWaveEffectParticleData wave =
                new ShellBlastWaveEffectParticleData(32.0D, boomHolder, SoundSource.BLOCKS, 0.0f, pitch, 2.4f, 16.0f);
        for (ServerPlayer player : level.players()) {
            double distSqr = player.distanceToSqr(pos);
            if (distSqr <= 190.0D * 190.0D) {
                PacketDistributor.sendToPlayer(player, payload);
            }
            if (distSqr <= 120.0D * 120.0D && distSqr >= 4.0D) {
                level.sendParticles(player, wave, true, pos.x, pos.y, pos.z, 0, 0.0D, 0.0D, 0.0D, 1.0D);
            }
            if (distSqr <= 40.0D * 40.0D) {
                float proximity = (float) Mth.clamp(1.0D - distSqr / (40.0D * 40.0D), 0.0D, 1.0D);
                budget.offerShake(player, 20.0f * proximity * proximity, pos);
            }
        }

        // The column mushrooms out at the top.
        schedule(level, 8, () -> {
            for (int i = 0; i < 3 * detail; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                directed(
                        level,
                        new BlastSmokeData(2.6f + random.nextFloat() * 1.4f),
                        pos.x,
                        pos.y + 4.5 + random.nextDouble(),
                        pos.z,
                        Math.cos(angle) * .12,
                        .05,
                        Math.sin(angle) * .12);
            }
        });
        // Earth rains back down out of the cloud.
        for (int shower = 0; shower < 3; shower++) {
            schedule(level, 12 + shower * 6, () -> {
                for (int i = 0; i < 8 * detail; i++) {
                    directed(
                            level,
                            soil,
                            pos.x + (random.nextDouble() - .5) * 5,
                            pos.y + 5 + random.nextDouble() * 3,
                            pos.z + (random.nextDouble() - .5) * 5,
                            0,
                            -.1,
                            0);
                }
            });
        }
        schedule(
                level,
                14,
                () -> level.playSound(
                        null, pos.x, pos.y, pos.z, SoundEvents.GRAVEL_FALL, SoundSource.BLOCKS, 2.5f, .6f));
        // Smoke hangs over the crater.
        schedule(
                level,
                24,
                () -> emitFar(
                        level,
                        new ShellExplosionSmokeParticleData(180 + level.random.nextInt(40), 2.0f),
                        pos.x,
                        pos.y + 1.0D,
                        pos.z,
                        1,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0D));
    }

    /** One particle with an exact velocity, to every player who can see the spot. */
    private static void directed(
            ServerLevel level,
            net.minecraft.core.particles.ParticleOptions type,
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz) {
        emitFar(level, type, x, y, z, 0, vx, vy, vz, 1.0D);
    }

    /** Water reacts only where water exists, including a nearby shore; hulls and roofs stop the spray. */
    public static void waterBurst(ServerLevel level, Vec3 pos, float power) {
        BlastWater.Contact water = BlastWater.find(level, pos, Math.clamp(power * 0.35, 1.0, 3.0));
        if (water == null) {
            return;
        }
        Vec3 wet = water.water();
        var payload = new com.cbc_more_content.network.WaterBlastPayload(wet, water.surface(), power);
        for (ServerPlayer player : level.players()) {
            if (withinRenderedChunks(level, player, wet)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
        int foam = (int) Math.clamp(power * 3, 8, 32);
        if (BlastWater.contains(level, pos)) {
            level.playSound(
                    null,
                    wet.x,
                    wet.y,
                    wet.z,
                    SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE,
                    SoundSource.BLOCKS,
                    2.0F,
                    0.65F);
        }
        emitFar(level, ParticleTypes.BUBBLE, wet.x, wet.y, wet.z, foam, 0.1, 0.1, 0.1, 0.04);
        Vec3 surface = water.surface();
        if (surface == null) {
            return;
        }
        int delay = 2 + (int) Math.clamp((surface.y - wet.y) * 0.3, 0, 10);
        schedule(level, delay, () -> {
            if (!BlastWater.contains(level, surface.add(0, -0.05, 0))) {
                return;
            }
            BlastWater.Contact current = BlastWater.find(level, surface.add(0, -0.05, 0), 0.1);
            if (current == null
                    || current.surface() == null
                    || current.surface().distanceToSqr(surface) > 0.04) {
                return;
            }
            float strength = (float) Math.clamp(power / 8.0, 0.3, 2.0);
            level.playSound(
                    null,
                    surface.x,
                    surface.y,
                    surface.z,
                    SoundEvents.GENERIC_SPLASH,
                    SoundSource.BLOCKS,
                    strength * 2,
                    0.75F);
            emitFar(
                    level,
                    ParticleTypes.SPLASH,
                    surface.x,
                    surface.y,
                    surface.z,
                    foam * 2,
                    0.2,
                    0.05,
                    0.2,
                    0.2 * strength);
            emitFar(
                    level,
                    ParticleTypes.FALLING_WATER,
                    surface.x,
                    surface.y + 0.25,
                    surface.z,
                    foam,
                    0.25,
                    0.1,
                    0.25,
                    0.08 * strength);
            emitFar(
                    level,
                    ParticleTypes.CLOUD,
                    surface.x,
                    surface.y + 0.2,
                    surface.z,
                    Math.max(2, foam / 6),
                    0.25,
                    0.1,
                    0.25,
                    0.01);
        });
    }

    /** A dense, lower dust bloom for placed C4 and the vest, sharing the actual detonation budget. */
    public static void playBreachingCharge(
            ServerLevel level, Vec3 pos, float blockPower, BombBurstBudget.Snapshot budget) {
        play(level, pos, BombSize.MEDIUM, blockPower, budget, false, true);
    }

    /**
     * Sends particles so they survive as far as they are drawn.
     * <p>
     * {@code ServerLevel#sendParticles} without a player argument hardcodes
     * {@code longDistance = false}, which drops the packet for anyone past 32 blocks —
     * which is why a blast that fills the sky up close vanished into nothing a short walk
     * away. Sending per player with the flag set pushes the limit out to view distance,
     * where the fog takes over instead.
     */
    private static <T extends net.minecraft.core.particles.ParticleOptions> void emitFar(
            ServerLevel level,
            T type,
            double x,
            double y,
            double z,
            int count,
            double dx,
            double dy,
            double dz,
            double speed) {

        for (ServerPlayer player : level.players()) {
            if (withinRenderedChunks(level, player, new Vec3(x, y, z))) {
                level.sendParticles(player, type, true, x, y, z, count, dx, dy, dz, speed);
            }
        }
    }

    private static boolean withinRenderedChunks(ServerLevel level, ServerPlayer player, Vec3 position) {
        int chunks = Math.min(level.getServer().getPlayerList().getViewDistance(), player.requestedViewDistance());
        var source = new net.minecraft.world.level.ChunkPos(net.minecraft.core.BlockPos.containing(position));
        var camera = player.chunkPosition();
        return Math.abs(source.x - camera.x) <= chunks + 1 && Math.abs(source.z - camera.z) <= chunks + 1;
    }

    private static void schedule(ServerLevel level, int delayTicks, Runnable task) {
        var server = level.getServer();
        if (server == null) {
            task.run();
            return;
        }
        BlastFxScheduler.schedule(level, delayTicks, task);
    }

    private static void spawnDelayedSmoke(
            ServerLevel level,
            Vec3 pos,
            BombSize size,
            FxProfile profile,
            float smokeScale,
            BombBurstBudget.Lod lod,
            boolean torpedo,
            boolean breaching) {
        for (ServerPlayer player : level.players()) {
            if (!withinRenderedChunks(level, player, pos)) {
                continue;
            }
            double smokeY = player.distanceToSqr(pos) < 6.25D ? pos.y + .8D : pos.y;
            Vec3 smokeOrigin = new Vec3(pos.x, smokeY, pos.z);
            if (size == BombSize.SMALL || size == BombSize.MEDIUM || size == BombSize.SEA) {
                // CBC provides animated hot smoke and expanding trails; the soft body adds volume.
                // Neither layer replaces the other, even in a busy chain reaction.
                spawnCbcSmokeForPlayer(level, player, smokeOrigin, smokeScale, !breaching, lod);
                spawnSoftSmokeForPlayer(level, player, smokeOrigin, size, smokeScale, lod, torpedo, breaching);
                continue;
            }
            if (lod != BombBurstBudget.Lod.ESSENTIAL) {
                level.sendParticles(
                        player,
                        new ShellExplosionCloudParticleData(smokeScale * .85f, profile.plume),
                        true,
                        pos.x,
                        smokeY,
                        pos.z,
                        1,
                        0,
                        0,
                        0,
                        0);
            } else {
                level.sendParticles(
                        player,
                        new BlastSmokeData(smokeScale * .55f),
                        true,
                        pos.x,
                        smokeY + 1,
                        pos.z,
                        3,
                        smokeScale * .25,
                        1,
                        smokeScale * .25,
                        .08);
            }
            if (size == BombSize.LARGE && lod.allowMushroomRing()) {
                spawnMushroomForPlayer(level, player, pos, profile, lod);
            } else if (size == BombSize.LARGE && lod == BombBurstBudget.Lod.MINIMAL) {
                level.sendParticles(
                        player,
                        new ShellExplosionCloudParticleData(smokeScale * .75f, true),
                        true,
                        pos.x,
                        smokeY + 1,
                        pos.z,
                        1,
                        0,
                        0,
                        0,
                        0);
            }
        }
    }

    private static void spawnCbcSmokeForPlayer(
            ServerLevel level, ServerPlayer player, Vec3 pos, float scale, boolean plume, BombBurstBudget.Lod lod) {
        if (lod.fullFx()) {
            // A CBC cloud is a short-lived emitter which generates smoke and outward trails over several ticks.
            level.sendParticles(
                    player,
                    new ShellExplosionCloudParticleData(scale * .85f, plume),
                    true,
                    pos.x,
                    pos.y + .35,
                    pos.z,
                    1,
                    0,
                    0,
                    0,
                    0);
        } else {
            // Bound the number of children under load instead of starting dozens of full CBC emitters.
            // Keep their scale, animated texture and lifetime so distant chains still read as explosions.
            int count = Math.max(5, lod.puffCount(42));
            for (int i = 0; i < count; i++) {
                double angle = i * 2.399963229728653;
                double radius = scale * (.2 + level.random.nextDouble() * .45);
                double dx = Math.cos(angle), dz = Math.sin(angle);
                double height = scale * (.1 + level.random.nextDouble() * (plume ? .9 : .35));
                double speed = scale * (.2 + level.random.nextDouble() * .22);
                level.sendParticles(
                        player,
                        new ShellExplosionSmokeParticleData(140 + level.random.nextInt(60), scale * 1.35f),
                        true,
                        pos.x + dx * radius,
                        pos.y + height,
                        pos.z + dz * radius,
                        0,
                        dx * speed,
                        speed * (plume ? 1.2 : .45),
                        dz * speed,
                        1);
            }
        }
        // A broad cap and short rising stem, proportionally smaller than the large bomb's mushroom.
        int sectors = Math.max(3, lod.puffCount(12));
        double capHeight = scale * (plume ? 1.65 : .65);
        double radius = scale * .65;
        for (int i = 0; i < sectors; i++) {
            double angle = Math.PI * 2 * i / sectors;
            double dx = Math.cos(angle), dz = Math.sin(angle);
            level.sendParticles(
                    player,
                    new ShellExplosionSmokeParticleData(150 + level.random.nextInt(50), scale * .85f),
                    true,
                    pos.x + dx * radius,
                    pos.y + capHeight,
                    pos.z + dz * radius,
                    0,
                    dx * .16,
                    plume ? .12 : .04,
                    dz * .16,
                    1);
        }
    }

    private static void spawnSoftSmokeForPlayer(
            ServerLevel level,
            ServerPlayer player,
            Vec3 pos,
            BombSize size,
            float scale,
            BombBurstBudget.Lod lod,
            boolean torpedo,
            boolean breaching) {
        int basePuffs = breaching ? 14 : (size == BombSize.SMALL ? 10 : (size == BombSize.MEDIUM || torpedo ? 18 : 14));
        int puffs = Math.max(3, lod.puffCount(basePuffs));
        float puffScale = scale * (size == BombSize.SMALL ? .85f : .75f);
        for (int i = 0; i < puffs; i++) {
            // Overlapping root, rising stem and wider cap preserve a volume even at the lowest LOD.
            double height = (double) i / Math.max(1, puffs - 1);
            double width = scale * (breaching ? 1.3 : .55 + .65 * height);
            double ox = (level.random.nextDouble() - .5) * width;
            double oy = scale * (.12 + (breaching ? .9 : 2.1) * height) + level.random.nextDouble() * .3;
            double oz = (level.random.nextDouble() - .5) * width;
            level.sendParticles(
                    player,
                    new BlastSmokeData(puffScale),
                    true,
                    pos.x + ox,
                    pos.y + .25 + oy,
                    pos.z + oz,
                    0,
                    ox * (breaching ? .06 : .025),
                    (breaching ? .07 : .12) + height * .08 + level.random.nextDouble() * .04,
                    oz * (breaching ? .06 : .025),
                    1);
        }
    }

    private static void dispatchLookFlash(ServerLevel level, Vec3 pos, BombSize size, BombBurstBudget.Snapshot budget) {
        float intensity = (switch (size) {
                    case SMALL -> 0.95f;
                    case SEA -> 1.05f;
                    case MEDIUM -> 1.8f;
                    case LARGE -> 1.55f;
                    case MOAB -> 2.4f;
                })
                * budget.lod().flashScale();

        BombFlashPayload payload = new BombFlashPayload(pos.x, pos.y, pos.z, intensity, (byte) size.ordinal());
        for (ServerPlayer player : level.players()) {
            if (withinRenderedChunks(level, player, pos)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static void spawnHotFlashParticles(ServerLevel level, Vec3 pos, BombSize size, BombBurstBudget.Lod lod) {
        // No ParticleTypes.FLASH — those billboards read as pink/black sheets when close.
        // Embers stay small and offset so they don't fill the camera.
        DustParticleOptions hot = new DustParticleOptions(
                new Vector3f(1.0f, 0.82f, 0.18f),
                size == BombSize.LARGE ? 1.35f : (size == BombSize.MEDIUM ? 1.15f : 0.95f));
        DustParticleOptions white = new DustParticleOptions(
                new Vector3f(1.0f, 0.97f, 0.85f),
                size == BombSize.LARGE ? 1.1f : (size == BombSize.MEDIUM ? 0.95f : 0.75f));
        int baseSparks = scaleFx(
                size,
                switch (size) {
                    case SMALL -> 12;
                    case SEA -> 7;
                    case MEDIUM -> 32;
                    case LARGE -> 16;
                    case MOAB -> 64;
                });
        int sparks =
                switch (lod) {
                    case FULL -> baseSparks;
                    case REDUCED -> Math.max(3, baseSparks / 2);
                    case MINIMAL -> Math.max(2, baseSparks / 3);
                    case ESSENTIAL -> 1;
                };
        double spread =
                switch (size) {
                    case SMALL -> 0.55D;
                    case SEA -> 0.7D;
                    case MEDIUM -> 0.9D;
                    case LARGE -> 1.4D;
                    case MOAB -> 2.6D;
                };
        emitFar(level, hot, pos.x, pos.y + 0.35D, pos.z, sparks, spread, spread * 0.4D, spread, 0.01D);
        emitFar(
                level,
                white,
                pos.x,
                pos.y + 0.5D,
                pos.z,
                Math.max(1, sparks / 2),
                spread * 0.4D,
                spread * 0.3D,
                spread * 0.4D,
                0.008D);

        spawnEmissiveCore(level, pos, size, lod, spread, sparks);

        if (size == BombSize.LARGE && lod.fullFx()) {
            emitFar(level, ParticleTypes.EXPLOSION, pos.x, pos.y + 0.5D, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * Fireball built from particle types shader packs treat as light emitters.
     * <p>
     * Dust particles carry an arbitrary tint and shader packs render them flat, so on a
     * Sodium + Iris client the blast core had no glow at all. Flame, soul-fire flame and
     * lava are all on the standard emissive lists, which means these read as an actual
     * light source under shaders while still looking like embers on vanilla lighting.
     */
    private static void spawnEmissiveCore(
            ServerLevel level, Vec3 pos, BombSize size, BombBurstBudget.Lod lod, double spread, int sparks) {
        if (lod == BombBurstBudget.Lod.ESSENTIAL) {
            return;
        }

        int flames = Math.max(2, sparks / 2);
        emitFar(
                level,
                ParticleTypes.FLAME,
                pos.x,
                pos.y + 0.4D,
                pos.z,
                flames,
                spread * 0.55D,
                spread * 0.3D,
                spread * 0.55D,
                0.035D);
        emitFar(
                level,
                ParticleTypes.SMALL_FLAME,
                pos.x,
                pos.y + 0.55D,
                pos.z,
                flames,
                spread * 0.75D,
                spread * 0.35D,
                spread * 0.75D,
                0.05D);

        if (size == BombSize.SMALL || lod == BombBurstBudget.Lod.MINIMAL) {
            return;
        }

        // Heavier charges throw burning debris that arcs out of the crater.
        emitFar(
                level,
                ParticleTypes.LAVA,
                pos.x,
                pos.y + 0.3D,
                pos.z,
                Math.max(2, flames / 2),
                spread * 0.4D,
                0.1D,
                spread * 0.4D,
                0.0D);

        if (size == BombSize.LARGE && lod.fullFx()) {
            emitFar(
                    level,
                    ParticleTypes.SOUL_FIRE_FLAME,
                    pos.x,
                    pos.y + 1.1D,
                    pos.z,
                    6,
                    spread * 0.5D,
                    spread * 0.4D,
                    spread * 0.5D,
                    0.08D);
        }
    }

    private static void spawnMushroomForPlayer(
            ServerLevel level, ServerPlayer player, Vec3 pos, FxProfile profile, BombBurstBudget.Lod lod) {
        float stem = profile.smokeScale * 0.55f;
        float cap = profile.smokeScale * 1.15f;
        double stemHeight = profile.smokeScale * 2.2D;
        double capY = pos.y + stemHeight;
        level.sendParticles(
                player,
                new ShellExplosionCloudParticleData(stem, true),
                true,
                pos.x,
                pos.y + 0.5D,
                pos.z,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D);
        level.sendParticles(
                player,
                new ShellExplosionCloudParticleData(cap, false),
                true,
                pos.x,
                capY,
                pos.z,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D);

        int sectors = lod.mushroomSectors();
        if (sectors <= 0) {
            return;
        }

        double ringR = profile.smokeScale * 1.8D;
        boolean doubleRing = lod.fullFx();
        for (int i = 0; i < sectors; i++) {
            double ang = (Math.PI * 2.0D * i) / sectors;
            double cx = pos.x + Math.cos(ang) * ringR;
            double cz = pos.z + Math.sin(ang) * ringR;
            double ox = Math.cos(ang) * 0.12D;
            double oz = Math.sin(ang) * 0.12D;
            level.sendParticles(
                    player,
                    new ShellExplosionSmokeParticleData(200 + level.random.nextInt(40), cap * 0.7f),
                    true,
                    cx,
                    capY,
                    cz,
                    0,
                    ox,
                    0.08D,
                    oz,
                    1.0D);
            if (doubleRing) {
                level.sendParticles(
                        player,
                        new ShellExplosionSmokeParticleData(160 + level.random.nextInt(30), cap * 0.45f),
                        true,
                        cx + ox,
                        capY + 0.55D,
                        cz + oz,
                        0,
                        ox * 0.7D,
                        0.06D,
                        oz * 0.7D,
                        1.0D);
            }
        }

        if (doubleRing) {
            level.sendParticles(
                    player,
                    new ShellExplosionCloudParticleData(cap * 0.35f, false),
                    true,
                    pos.x,
                    pos.y + 0.35D,
                    pos.z,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D);
        }
    }

    /**
     * Per-tier multiplier on how much visual there is, independent of blast power.
     * <p>
     * The light calibres were reading far weaker than they hit: a small bomb does real
     * damage but produced a puff, and a medium one was barely distinguishable from it.
     * Applied to emitter counts only — craters, damage and knockback are untouched, and
     * the LOD budget still trims all of this during carpet bombing.
     */
    private static int scaleFx(BombSize size, int base) {
        float factor =
                switch (size) {
                    case SMALL -> 2.0f;
                    case MEDIUM -> 3.0f;
                    case SEA, LARGE -> 1.0f;
                    case MOAB -> 1.0f;
                };
        return Math.max(1, Math.round(base * factor));
    }

    private record FxProfile(
            SoundEvent sound,
            float smokeScale,
            boolean plume,
            double blastRadius,
            float volume,
            float pitch,
            float airAbsorption,
            float shakePower,
            float shakeLimit,
            double nearSoundDist,
            double warStartDist,
            double warEndDist,
            double farSyncDistSqr,
            float warVolume,
            float warPitch,
            float warAirAbsorption,
            double shakeFalloff) {

        static FxProfile of(BombSize size, float blockPower, RandomSource random, boolean torpedo) {
            return switch (size) {
                case SMALL -> new FxProfile(
                        ModSounds.BOMB_EXPLOSION_SMALL.get(),
                        Math.max(3.1f, blockPower * 0.56f),
                        true,
                        Math.max(16.0D, blockPower * 5.5D),
                        Math.max(13.0f, blockPower * 2.0f),
                        1.02f + random.nextFloat() * 0.1f,
                        1.85f,
                        Math.max(13.0f, blockPower * 3.4f),
                        22.0f,
                        110.0D,
                        75.0D,
                        340.0D,
                        340.0D * 340.0D,
                        24.0f,
                        0.64f,
                        6.2f,
                        34.0D);
                case SEA -> new FxProfile(
                        // A step up from the small bomb's report: an underwater charge is a
                        // ship-killer, and it should sound like one through the water.
                        ModSounds.BOMB_EXPLOSION_MEDIUM.get(),
                        torpedo ? Math.max(3.9f, blockPower * 0.48f) : Math.max(3.7f, blockPower * 0.58f),
                        true,
                        Math.max(18.0D, blockPower * 6.0D),
                        Math.max(13.5f, blockPower * 1.9f),
                        0.98f + random.nextFloat() * 0.08f,
                        1.9f,
                        Math.max(14.5f, blockPower * 3.5f),
                        24.0f,
                        120.0D,
                        80.0D,
                        360.0D,
                        360.0D * 360.0D,
                        25.0f,
                        0.60f,
                        6.3f,
                        38.0D);
                case MEDIUM -> new FxProfile(
                        ModSounds.BOMB_EXPLOSION_MEDIUM.get(),
                        Math.max(5.0f, blockPower * 0.48f),
                        true,
                        Math.max(22.0D, blockPower * 7.0D),
                        Math.max(14.0f, blockPower * 1.8f),
                        0.92f + random.nextFloat() * 0.08f,
                        1.8f,
                        Math.max(18.0f, blockPower * 3.6f),
                        30.0f,
                        140.0D,
                        100.0D,
                        420.0D,
                        420.0D * 420.0D,
                        28.0f,
                        0.58f,
                        7.0f,
                        48.0D);
                case LARGE -> new FxProfile(
                        ModSounds.BOMB_EXPLOSION_LARGE.get(),
                        Math.max(6.5f, blockPower * 0.55f),
                        true,
                        Math.max(40.0D, blockPower * 10.0D),
                        Math.max(18.0f, blockPower * 2.0f),
                        0.78f + random.nextFloat() * 0.08f,
                        1.5f,
                        Math.max(28.0f, blockPower * 4.5f),
                        42.0f,
                        180.0D,
                        120.0D,
                        520.0D,
                        520.0D * 520.0D,
                        34.0f,
                        0.52f,
                        7.5f,
                        72.0D);
                case MOAB -> new FxProfile(
                        ModSounds.BOMB_EXPLOSION_LARGE.get(),
                        Math.max(12.0f, blockPower * 0.65f),
                        true,
                        Math.max(70.0D, blockPower * 12.0D),
                        Math.max(24.0f, blockPower * 2.3f),
                        0.62f + random.nextFloat() * 0.06f,
                        1.25f,
                        Math.max(40.0f, blockPower * 5.0f),
                        60.0f,
                        260.0D,
                        180.0D,
                        760.0D,
                        760.0D * 760.0D,
                        48.0f,
                        0.48f,
                        9.0f,
                        110.0D);
            };
        }
    }
}
