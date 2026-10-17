package com.cbc_more_content.effects;

import com.cbc_more_content.event.WarnauticsBlockChipEvent;
import com.cbc_more_content.registry.ModParticles;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
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
    private static final double SCUFF_RADIUS = 5.0D;

    /**
     * Most the pressure takes off somebody standing directly on the charge, before armour
     * and before a fragment or two finds them on top of it.
     * <p>
     * Deliberately its own number rather than the shell curve the bombs share. That curve
     * is {@code 7 x radius + 1}, and an antipersonnel charge has to reach a few blocks to
     * be worth laying at all — which came out at eighty-odd damage at the seat, four times
     * over what it takes to kill anyone. A mine is meant to take a man out of the fight,
     * not to be unsurvivable at any range inside its own burst.
     */
    private static final float PEAK_DAMAGE = 13.0f;
    /** Above one, so the drop is steep near the seat and long in the tail. */
    private static final double FALLOFF = 1.6D;
    /** Not worth rolling armour and a hurt tick for. */
    private static final float MIN_DAMAGE = 1.0f;

    /** Marks a burst as ours so {@code ShrapnelBurstMixin} only gates mine fragments. */
    public static final String MINE_BURST_TAG = "warnautics_mine_burst";

    private MineExplosionHandler() {}

    public static void detonateSmallShrapnel(
            ServerLevel level, @Nullable Entity source, DamageSource damageSource, Vec3 pos, float entityPower) {
        var target = com.cbc_more_content.compat.SableDropCompat.resolveWorldBlastChecked(level, pos);
        level = target.level();
        pos = target.pos();
        ShrapnelExplosion pressure = new MinePressureExplosion(
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
        applyPressure(level, damageSource, pos, entityPower);

        spawnFragmentFan(level, pos);
        BombBlastFx.waterBurst(level, pos, entityPower);
        // An antipersonnel charge digs nothing, but it does strip the ground it sat on.
        BlastScorch.scuff(level, pos, SCUFF_RADIUS, BlastScorch.SCAR_STRENGTH);

        level.playSound(null, pos.x, pos.y + 0.15D, pos.z, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2.2f, 1.42f);
        level.playSound(
                null, pos.x, pos.y + 0.15D, pos.z, SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.BLOCKS, 1.15f, 1.72f);
    }

    /**
     * Steel balls a Claymore throws. The real one carries seven hundred; each of these stands
     * for about six. Every one is traced for its whole flight, so a row of claymores fired at
     * once is paid for here.
     */
    public static final int CLAYMORE_BALLS = 120;
    /**
     * Hit size of one ball, blocks. CBC sizes its fragments for shell splinters at 0.8, which
     * let the nearest body soak up half the fan; a pellet this size leaves the rest of the
     * field to the balls that miss it.
     */
    public static final double CLAYMORE_BALL_SIZE = 0.3D;
    /** Marks the claymore's own burst, for the ball size. */
    public static final String CLAYMORE_BURST_TAG = "warnautics_claymore_burst";
    /** Half the fan: sixty degrees in all, as on the real one. */
    public static final double CLAYMORE_HALF_ANGLE = Math.toRadians(30.0D);
    /** Blocks/tick. With CBC's twenty-tick fragment life this carries about sixty blocks. */
    private static final double CLAYMORE_SPEED_MIN = 2.8D;

    private static final double CLAYMORE_SPEED_MAX = 3.2D;
    /** Level to a few degrees up: the fan stands about a man high out to its lethal range. */
    private static final double CLAYMORE_PITCH_MIN = Math.toRadians(-0.5D);

    private static final double CLAYMORE_PITCH_MAX = Math.toRadians(3.5D);
    /** The balls leave from the middle of the plate, at shin height. */
    private static final double CLAYMORE_MUZZLE_HEIGHT = 0.3D;
    /** How far in front the effects draw the strike of the balls. */
    public static final double CLAYMORE_EFFECT_RANGE = 30.0D;

    /**
     * An M18A1 Claymore: almost everything goes forward.
     * <p>
     * The steel balls leave the curved face in a sixty degree horizontal fan rising only a
     * few degrees, fast enough to stay lethal for tens of blocks; anyone in front and in
     * the open is hit by several. Behind the plate and to the sides there is only the
     * back-blast of the charge itself: dangerous right next to it, nothing a few blocks
     * away. It digs no crater.
     */
    public static void detonateClaymore(
            ServerLevel level, @Nullable Entity source, DamageSource damageSource, Vec3 pos, Vec3 forward) {
        detonateClaymore(level, source, damageSource, pos, forward, Vec3.ZERO);
    }

    /** As above, from a carrier moving at {@code carrierVelocity} blocks per tick: the balls keep its speed. */
    public static void detonateClaymore(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            Vec3 forward,
            Vec3 carrierVelocity) {
        Vec3 ahead = forward.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : forward.normalize();
        float backBlast = com.cbc_more_content.mine.MineType.CLAYMORE.entityBlastPower;
        // CBC's explosion hurts out to twice its power on the vanilla curve, which made a
        // back-blast as dangerous five blocks behind as a grenade. It is kept small, for the
        // shove and the ship push; the damage comes from the mine's own pressure curve.
        ShrapnelExplosion pressure = new MinePressureExplosion(
                level,
                source,
                damageSource,
                pos.x,
                pos.y,
                pos.z,
                0.0f,
                1.0f,
                CBCConfigs.server().munitions.damageRestriction.get().explosiveInteraction());
        CreateBigCannons.handleCustomExplosion(level, pressure);
        applyPressure(level, damageSource, pos, backBlast);

        spawnClaymoreFan(level, pos.add(0.0D, CLAYMORE_MUZZLE_HEIGHT - 0.5D, 0.0D), ahead, carrierVelocity);
        // It stands above the ground and digs nothing, so there is no scorch to leave.
        BombBlastFx.playClaymore(level, pos, ahead, CLAYMORE_HALF_ANGLE, CLAYMORE_EFFECT_RANGE);
    }

    private static void spawnClaymoreFan(ServerLevel level, Vec3 muzzle, Vec3 ahead, Vec3 carrier) {
        ShrapnelBurst burst = CBCEntityTypes.SHRAPNEL_BURST.get().create(level);
        if (burst == null) {
            return;
        }
        burst.setPos(muzzle);
        // Yaw of the face, and how far the face itself is tilted (on a listing deck).
        double heading = Math.atan2(ahead.z, ahead.x);
        double tilt = Math.asin(Mth.clamp(ahead.y, -1.0D, 1.0D));

        RandomSource random = level.getRandom();
        double[] velocities = new double[CLAYMORE_BALLS * 3];
        double sector = 2.0D * CLAYMORE_HALF_ANGLE / CLAYMORE_BALLS;
        for (int i = 0; i < CLAYMORE_BALLS; i++) {
            // Even across the fan, jittered within each slice, so there are no lanes to
            // walk through and no clumps.
            double yaw = heading - CLAYMORE_HALF_ANGLE + (i + random.nextDouble()) * sector;
            double pitch = tilt + Mth.lerp(random.nextDouble(), CLAYMORE_PITCH_MIN, CLAYMORE_PITCH_MAX);
            double speed = Mth.lerp(random.nextDouble(), CLAYMORE_SPEED_MIN, CLAYMORE_SPEED_MAX);
            double horizontal = Math.cos(pitch) * speed;
            double vx = Math.cos(yaw) * horizontal + carrier.x;
            double vy = Math.sin(pitch) * speed + carrier.y;
            double vz = Math.sin(yaw) * horizontal + carrier.z;
            velocities[i * 3] = vx;
            velocities[i * 3 + 1] = vy;
            velocities[i * 3 + 2] = vz;
            // They leave from across the whole face, not from one point.
            double across = (random.nextDouble() - 0.5D) * 0.5D;
            burst.addSubProjectile(
                    -ahead.z * across + ahead.x * 0.2D,
                    (random.nextDouble() - 0.5D) * 0.2D,
                    ahead.x * across + ahead.z * 0.2D,
                    vx,
                    vy,
                    vz);
        }
        burst.getPersistentData().putBoolean(MINE_BURST_TAG, true);
        burst.getPersistentData().putBoolean(CLAYMORE_BURST_TAG, true);
        // Steel balls leave the plate at some twelve hundred metres a second and drop a
        // centimetre or two over fifty: no arc worth having. CBC's fragment gravity, made for
        // shell splinters, put every ball in the dirt twenty blocks out.
        burst.setNoGravity(true);
        level.addFreshEntity(burst);
        // One ball in four as a visible streak is enough to read the fan.
        double[] tracers = new double[(CLAYMORE_BALLS / 4) * 3];
        for (int i = 0; i < CLAYMORE_BALLS / 4; i++) {
            System.arraycopy(velocities, i * 12, tracers, i * 3, 3);
        }
        sendFragmentTrails(level, muzzle, tracers);
    }

    private static final class MinePressureExplosion extends ShrapnelExplosion {
        private MinePressureExplosion(
                ServerLevel level,
                @Nullable Entity source,
                DamageSource damageSource,
                double x,
                double y,
                double z,
                float blockPower,
                float entityPower,
                BlockInteraction interaction) {
            super(level, source, damageSource, x, y, z, blockPower, entityPower, interaction);
        }

        @Override
        public void explode() {
            super.explode();
            if (canDamageTerrain() && getBlockInteraction() != BlockInteraction.KEEP) {
                BlastPropagation.pushSubLevels((ServerLevel) level, this, Math.min(3.0f, getEntityRadius() * 0.35f));
            }
        }

        /**
         * No packet: the client would rebuild this as a shrapnel shell burst and fill the spot
         * with CBC's big white shrapnel cloud, which reads as steam over a mine. The mines draw
         * their own smoke. The only other thing the packet carries is the shove, applied here.
         */
        @Override
        public void sendExplosionToClient(net.minecraft.server.level.ServerPlayer player) {
            Vec3 shove = getHitPlayers().get(player);
            if (shove != null && shove.lengthSqr() > 1.0E-8D) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(
                        player.getId(), player.getDeltaMovement().add(shove)));
            }
        }
    }

    /**
     * The pressure wave, on its own curve.
     * <p>
     * Where you are standing decides most of it: distance sets the shape, and whatever is
     * between you and the charge cuts it again, so a wall or a corner is worth taking.
     */
    private static void applyPressure(ServerLevel level, DamageSource damageSource, Vec3 center, float entityPower) {
        if (level == null
                || damageSource == null
                || center == null
                || !Float.isFinite(entityPower)
                || entityPower <= 0.0f) {
            return;
        }
        double radius = entityPower;
        AABB area = new AABB(center, center).inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (entity.isSpectator()) {
                continue;
            }
            Vec3 body = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
            double distance = body.distanceTo(center);
            if (distance > radius) {
                continue;
            }

            BlastCover.Result cover = BlastCover.evaluate(level, center, entity);
            double falloff = 1.0D - distance / radius;
            if (entity instanceof ServerPlayer player && player.isAlive()) {
                ConcussionHandler.offer(player, entityPower, falloff, cover.transmission(), cover.hasLineOfSight());
            }

            float damage = (float) (PEAK_DAMAGE * Math.pow(falloff, FALLOFF) * cover.transmission());
            if (damage >= MIN_DAMAGE) {
                entity.hurt(damageSource, damage);
            }
        }
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
                    vx,
                    vy,
                    vz);
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
                level.sendParticles(
                        player,
                        ModParticles.MINE_FRAGMENT.get(),
                        true,
                        muzzle.x,
                        muzzle.y,
                        muzzle.z,
                        0,
                        velocities[i],
                        velocities[i + 1],
                        velocities[i + 2],
                        1.0D);
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
