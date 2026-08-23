package com.cbc_more_content.effects;

import javax.annotation.Nullable;

import com.cbc_more_content.event.WarnauticsBlockChipEvent;
import com.cbc_more_content.registry.ModParticles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.index.CBCEntityTypes;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelExplosion;

/**
 * Antipersonnel mine blast: a CBC shrapnel burst instead of an HE crater, so nearby
 * soil is chipped by fragment collisions rather than carved out in a sphere.
 */
public final class MineExplosionHandler {
    private static final int FRAGMENT_COUNT = 36;
    /** Fragment speed, blocks/tick. Matches the energy of the old upward cone. */
    private static final double FRAGMENT_SPEED_MIN = 1.15D;
    private static final double FRAGMENT_SPEED_MAX = 1.75D;
    /** Elevation band, radians. Just under level to a shallow rise — a flat rake. */
    private static final double PITCH_MIN = -0.10D;
    private static final double PITCH_MAX = 0.26D;
    /** Fragments leave from the charge body, a hand's width off the ground. */
    private static final double MUZZLE_HEIGHT = 0.22D;
    private static final double PARTICLE_RANGE_SQR = 64.0D * 64.0D;
    /** How far the blast marks the surface, and how hard it does so at the seat. */
    private static final double SCUFF_RADIUS = 4.0D;
    private static final float SCUFF_STRENGTH = 0.9f;

    /** Marks a burst as ours so {@code ShrapnelBurstMixin} only gates mine fragments. */
    public static final String MINE_BURST_TAG = "warnautics_mine_burst";

    private MineExplosionHandler() {
    }

    public static void detonateSmallShrapnel(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float entityPower) {
        ShrapnelExplosion pressure = new ShrapnelExplosion(
                level,
                source,
                damageSource,
                pos.x,
                pos.y,
                pos.z,
                0.0f,
                entityPower,
                CBCConfigs.server().munitions.damageRestriction.get().explosiveInteraction());
        CreateBigCannons.handleCustomExplosion(level, pressure);

        // CBC uses the zero block radius above for its entity lookup too, so the
        // pressure explosion itself cannot find the player standing on the mine.
        BombExplosionHandler.applySmallMinePressureDamage(
                level, damageSource, pos, entityPower);

        spawnFragmentFan(level, pos);
        // An antipersonnel charge digs nothing, but it does strip the ground it sat on.
        BlastScorch.scuff(level, pos, SCUFF_RADIUS, SCUFF_STRENGTH);

        level.playSound(null, pos.x, pos.y + 0.15D, pos.z,
                SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2.2f, 1.42f);
        level.playSound(null, pos.x, pos.y + 0.15D, pos.z,
                SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.BLOCKS, 1.15f, 1.72f);
    }

    /**
     * A flat 360° fan rather than an upward cone.
     * <p>
     * An antipersonnel charge throws its casing outward at shin height; the cone CBC's
     * {@code spawnConeBurst} builds is symmetric about its axis, so no axis it accepts
     * can produce a disc. The sub-projectiles are added directly instead, with azimuth
     * spread evenly around the charge and elevation confined to a narrow band.
     */
    private static void spawnFragmentFan(ServerLevel level, Vec3 pos) {
        ShrapnelBurst burst = CBCEntityTypes.SHRAPNEL_BURST.get().create(level);
        if (burst == null) {
            return;
        }
        Vec3 muzzle = pos.add(0.0D, MUZZLE_HEIGHT, 0.0D);
        burst.setPos(muzzle);

        RandomSource random = level.getRandom();
        double[] velocities = new double[FRAGMENT_COUNT * 3];
        double sector = Mth.TWO_PI / FRAGMENT_COUNT;

        for (int i = 0; i < FRAGMENT_COUNT; i++) {
            // One fragment per sector, jittered within it, so the fan has no gaps and
            // no clumps however the random rolls.
            double yaw = (i + random.nextDouble()) * sector;
            double pitch = Mth.lerp(random.nextDouble(), PITCH_MIN, PITCH_MAX);
            double speed = Mth.lerp(random.nextDouble(), FRAGMENT_SPEED_MIN, FRAGMENT_SPEED_MAX);

            double horizontal = Math.cos(pitch) * speed;
            double vx = Math.cos(yaw) * horizontal;
            double vy = Math.sin(pitch) * speed;
            double vz = Math.sin(yaw) * horizontal;

            velocities[i * 3] = vx;
            velocities[i * 3 + 1] = vy;
            velocities[i * 3 + 2] = vz;

            burst.addSubProjectile(
                    (random.nextDouble() - random.nextDouble()) * 0.0625D,
                    (random.nextDouble() - random.nextDouble()) * 0.0625D,
                    (random.nextDouble() - random.nextDouble()) * 0.0625D,
                    vx, vy, vz);
        }

        burst.getPersistentData().putBoolean(MINE_BURST_TAG, true);
        level.addFreshEntity(burst);
        sendFragmentTrails(level, muzzle, velocities);
    }

    /**
     * Visible slivers travelling with the fragments. The burst itself has no renderer,
     * so without these the fan reads as damage arriving out of nowhere.
     */
    private static void sendFragmentTrails(ServerLevel level, Vec3 muzzle, double[] velocities) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(muzzle) > PARTICLE_RANGE_SQR) {
                continue;
            }
            for (int i = 0; i < velocities.length; i += 3) {
                // count 0 means the offset arguments are read as a velocity instead.
                level.sendParticles(player, ModParticles.MINE_FRAGMENT.get(), true,
                        muzzle.x, muzzle.y, muzzle.z, 0,
                        velocities[i], velocities[i + 1], velocities[i + 2], 1.0D);
            }
        }
    }

    /** Lets add-ons veto the terrain chip a single mine fragment would cause. */
    public static boolean mayChipBlock(ServerLevel level, BlockPos pos, Vec3 origin) {
        WarnauticsBlockChipEvent event = new WarnauticsBlockChipEvent(level, pos, origin);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }
}
