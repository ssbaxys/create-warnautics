package com.cbc_more_content.effects;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.network.BombFlashPayload;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
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
        play(
                level,
                pos,
                size,
                blockPower,
                new BombBurstBudget.Snapshot(1, BombBurstBudget.Lod.FULL, new BombBurstBudget.DimState()));
    }

    public static void play(
            ServerLevel level, Vec3 pos, BombSize size, float blockPower, BombBurstBudget.Snapshot budget) {
        FxProfile profile = FxProfile.of(size, blockPower, level.random);
        BombBurstBudget.Lod lod = budget.lod();

        // Reliable boom — do not depend on CBC blast-wave particle reaching the client.
        level.playSound(null, pos.x, pos.y, pos.z, profile.sound, SoundSource.BLOCKS, profile.volume, profile.pitch);

        if (size == BombSize.LARGE && lod != BombBurstBudget.Lod.ESSENTIAL) {
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
        if (allowDelayedSmoke(budget)) {
            schedule(level, 5, () -> spawnDelayedSmoke(level, pos, size, profile, lod));
        }
    }

    /**
     * Anti-tank mines need a hard pressure cue, not a bomb-sized mushroom cloud.
     * This path deliberately uses one shake wave and a tiny bounded smoke charge.
     */
    public static void playCompactMine(ServerLevel level, Vec3 pos, float blockPower, BombBurstBudget.Snapshot budget) {
        float pitch = 0.88f + level.random.nextFloat() * 0.08f;
        float volume = Math.max(9.0f, blockPower * 2.1f);
        level.playSound(
                null, pos.x, pos.y, pos.z, ModSounds.BOMB_EXPLOSION_LARGE.get(), SoundSource.BLOCKS, volume, pitch);

        DustParticleOptions hot = new DustParticleOptions(new Vector3f(1.0f, 0.78f, 0.16f), 0.9f);
        DustParticleOptions white = new DustParticleOptions(new Vector3f(1.0f, 0.96f, 0.82f), 0.7f);
        int sparks =
                switch (budget.lod()) {
                    case FULL -> 6;
                    case REDUCED -> 4;
                    case MINIMAL -> 2;
                    case ESSENTIAL -> 1;
                };
        emitFar(level, hot, pos.x, pos.y + 0.25D, pos.z, sparks, 0.55D, 0.18D, 0.55D, 0.008D);
        emitFar(level, white, pos.x, pos.y + 0.32D, pos.z, Math.max(1, sparks / 3), 0.18D, 0.12D, 0.18D, 0.004D);

        float intensity = 0.82f * budget.lod().flashScale();
        BombFlashPayload payload =
                new BombFlashPayload(pos.x, pos.y, pos.z, intensity, (byte) BombSize.SMALL.ordinal());
        double reachSqr = 155.0D * 155.0D;
        Holder<SoundEvent> boomHolder =
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(ModSounds.BOMB_EXPLOSION_LARGE.get());
        ShellBlastWaveEffectParticleData wave =
                new ShellBlastWaveEffectParticleData(24.0D, boomHolder, SoundSource.BLOCKS, 0.0f, pitch, 2.0f, 12.0f);

        for (ServerPlayer player : level.players()) {
            double distSqr = player.distanceToSqr(pos);
            if (distSqr <= reachSqr) {
                PacketDistributor.sendToPlayer(player, payload);
            }
            if (distSqr <= 95.0D * 95.0D && distSqr >= 4.0D) {
                level.sendParticles(player, wave, true, pos.x, pos.y, pos.z, 0, 0.0D, 0.0D, 0.0D, 1.0D);
            }
            if (distSqr <= 32.0D * 32.0D) {
                float proximity = (float) Mth.clamp(1.0D - distSqr / (32.0D * 32.0D), 0.0D, 1.0D);
                budget.offerShake(player, 12.0f * proximity * proximity, pos);
            }
        }

        if (allowDelayedSmoke(budget)) {
            schedule(
                    level,
                    4,
                    () -> emitFar(
                            level,
                            new ShellExplosionCloudParticleData(1.45f, false),
                            pos.x,
                            pos.y + 0.4D,
                            pos.z,
                            1,
                            0.0D,
                            0.0D,
                            0.0D,
                            0.0D));
        }
    }

    /**
     * Extra plume and flash on top of the normal blast, for the breaching charge.
     * <p>
     * A C4 goes off at a fraction of a bomb's power, so the shared profile gives it a
     * puff that reads as far too thin for what it does to a wall. This layers a heavy
     * ground-hugging cloud and a second flash — {@link com.cbc_more_content.client.BombFlashClient}
     * merges flashes at the same spot, so it brightens the existing one instead of
     * stacking a second white-out.
     */
    public static void playBreachingCharge(ServerLevel level, Vec3 pos, float blockPower) {
        DustParticleOptions hot = new DustParticleOptions(new Vector3f(1.0f, 0.80f, 0.22f), 1.2f);
        DustParticleOptions white = new DustParticleOptions(new Vector3f(1.0f, 0.95f, 0.80f), 0.95f);
        emitFar(level, hot, pos.x, pos.y + 0.35D, pos.z, 26, 0.85D, 0.45D, 0.85D, 0.02D);
        emitFar(level, white, pos.x, pos.y + 0.55D, pos.z, 14, 0.55D, 0.4D, 0.55D, 0.015D);
        emitFar(level, ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.4D, pos.z, 34, 1.0D, 0.5D, 1.0D, 0.03D);
        emitFar(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 0.3D, pos.z, 10, 0.7D, 0.2D, 0.7D, 0.02D);

        BombFlashPayload payload = new BombFlashPayload(pos.x, pos.y, pos.z, 1.35f, (byte) BombSize.MEDIUM.ordinal());
        double reachSqr = 200.0D * 200.0D;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= reachSqr) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }

        // The billowing part arrives after the fireball peak, so it does not cover it.
        schedule(level, 4, () -> {
            emitFar(
                    level,
                    new ShellExplosionCloudParticleData(Math.max(2.6f, blockPower * 0.42f), true),
                    pos.x,
                    pos.y + 0.6D,
                    pos.z,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D);
            for (int i = 0; i < 14; i++) {
                double ox = (level.random.nextDouble() - 0.5D) * 2.4D;
                double oz = (level.random.nextDouble() - 0.5D) * 2.4D;
                emitFar(
                        level,
                        new ShellExplosionSmokeParticleData(90 + level.random.nextInt(50), 1.05f),
                        pos.x + ox,
                        pos.y + 0.45D + level.random.nextDouble() * 0.7D,
                        pos.z + oz,
                        0,
                        ox * 0.05D,
                        0.07D,
                        oz * 0.05D,
                        1.0D);
            }
        });
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
        double reach = viewDistanceBlocks(level);
        double reachSqr = reach * reach;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= reachSqr) {
                level.sendParticles(player, type, true, x, y, z, count, dx, dy, dz, speed);
            }
        }
    }

    /** How far the server is willing to draw at all, in blocks. */
    private static double viewDistanceBlocks(ServerLevel level) {
        var server = level.getServer();
        int chunks = server == null ? 12 : server.getPlayerList().getViewDistance();
        return Math.max(64, chunks * 16);
    }

    private static void schedule(ServerLevel level, int delayTicks, Runnable task) {
        var server = level.getServer();
        if (server == null) {
            task.run();
            return;
        }
        server.tell(new TickTask(server.getTickCount() + Math.max(1, delayTicks), task));
    }

    private static void spawnDelayedSmoke(
            ServerLevel level, Vec3 pos, BombSize size, FxProfile profile, BombBurstBudget.Lod lod) {
        for (ServerPlayer player : level.players()) {
            double distSqr = player.distanceToSqr(pos);
            if (distSqr > profile.farSyncDistSqr) {
                continue;
            }
            double dist = Math.sqrt(distSqr);
            if (dist > profile.nearSoundDist) {
                continue;
            }
            // For close players, spawn smoke slightly above ground so it renders surrounding the player without
            // clipping.
            double smokeY = dist < 2.5D ? pos.y + 0.8D : pos.y;
            ShellExplosionCloudParticleData cloud =
                    new ShellExplosionCloudParticleData(profile.smokeScale * 0.85f, profile.plume);
            if (dist >= 1.2D) {
                level.sendParticles(player, cloud, true, pos.x, smokeY, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            if (size == BombSize.LARGE && lod.allowMushroomRing()) {
                spawnMushroomForPlayer(level, player, pos, profile, lod);
            } else if (size == BombSize.LARGE && lod == BombBurstBudget.Lod.MINIMAL) {
                level.sendParticles(
                        player,
                        new ShellExplosionCloudParticleData(profile.smokeScale * 0.75f, true),
                        true,
                        pos.x,
                        pos.y + 1.0D,
                        pos.z,
                        1,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0D);
            } else if (size == BombSize.MEDIUM || size == BombSize.SMALL || size == BombSize.SEA) {
                int basePuffs = scaleFx(size, size == BombSize.SMALL ? 6 : (size == BombSize.MEDIUM ? 20 : 5));
                int puffs = lod.puffCount(basePuffs);
                float puffScale = size == BombSize.SMALL
                        ? profile.smokeScale * 0.32f
                        : (size == BombSize.MEDIUM ? profile.smokeScale * 0.48f : profile.smokeScale * 0.38f);
                for (int i = 0; i < puffs; i++) {
                    double ox = (level.random.nextDouble() - 0.5D) * profile.smokeScale;
                    double oy = level.random.nextDouble() * profile.smokeScale * 0.45D;
                    double oz = (level.random.nextDouble() - 0.5D) * profile.smokeScale;
                    level.sendParticles(
                            player,
                            new ShellExplosionSmokeParticleData(70 + level.random.nextInt(30), puffScale),
                            true,
                            pos.x + ox,
                            smokeY + 0.25D + oy,
                            pos.z + oz,
                            0,
                            ox * 0.04D,
                            0.06D + level.random.nextDouble() * 0.04D,
                            oz * 0.04D,
                            1.0D);
                }
            }
        }
    }

    private static boolean allowDelayedSmoke(BombBurstBudget.Snapshot budget) {
        return switch (budget.lod()) {
            case FULL, REDUCED -> true;
            case MINIMAL -> (budget.index() & 1) == 1;
            case ESSENTIAL -> budget.index() % 8 == 1;
        };
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

        double reach =
                switch (size) {
                    case SMALL -> 140.0D;
                    case SEA -> 160.0D;
                    case MEDIUM -> 240.0D;
                    case LARGE -> 280.0D;
                    case MOAB -> 420.0D;
                };
        double reachSqr = reach * reach;
        BombFlashPayload payload = new BombFlashPayload(pos.x, pos.y, pos.z, intensity, (byte) size.ordinal());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= reachSqr) {
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

        static FxProfile of(BombSize size, float blockPower, RandomSource random) {
            return switch (size) {
                case SMALL -> new FxProfile(
                        ModSounds.BOMB_EXPLOSION_SMALL.get(),
                        Math.max(1.55f, blockPower * 0.28f),
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
                        ModSounds.BOMB_EXPLOSION_SMALL.get(),
                        Math.max(2.0f, blockPower * 0.30f),
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
                        Math.max(2.8f, blockPower * 0.35f),
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
