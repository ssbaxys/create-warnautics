package com.cbc_more_content.effects;

import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.damage.MineDamageSource;
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
        entityPower = com.cbc_more_content.config.WarnauticsConfig.entityPower(
                entityPower, com.cbc_more_content.config.WarnauticsConfig.Munition.MINE);
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
     * Hit size of one ball, blocks. CBC sizes its fragments for shell splinters at 0.8, which
     * let the nearest body soak up half the fan; a pellet this size leaves the rest of the
     * field to the balls that miss it.
     */
    public static final double CLAYMORE_BALL_SIZE = 0.3D;
    /** Marks the claymore's own burst, for the ball size. */
    public static final String CLAYMORE_BURST_TAG = "warnautics_claymore_burst";
    /** Spread of ball speeds, blocks/tick, around the configured speed. */
    private static final double CLAYMORE_SPEED_SPREAD = 0.2D;
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
        float backBlast = com.cbc_more_content.config.WarnauticsConfig.entityPower(
                com.cbc_more_content.mine.MineType.CLAYMORE.entityBlastPower,
                com.cbc_more_content.config.WarnauticsConfig.Munition.MINE);
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

        Vec3 muzzle = pos.add(0.0D, CLAYMORE_MUZZLE_HEIGHT - 0.5D, 0.0D);
        spawnClaymoreFan(level, muzzle, ahead, carrierVelocity);
        strikeFan(level, muzzle, ahead);
        // It stands above the ground and digs nothing, so there is no scorch to leave.
        BombBlastFx.playClaymore(
                level,
                pos,
                ahead,
                com.cbc_more_content.config.WarnauticsConfig.claymoreHalfAngle(),
                Math.min(
                        CLAYMORE_EFFECT_RANGE,
                        com.cbc_more_content.config.WarnauticsConfig.claymoreBallSpeed() * 12.0D));
    }

    /**
     * Who the fan strikes, and how hard, worked out for each body in front instead of left to
     * individual balls finding a two-pixel-wide player.
     * <p>
     * For each living thing within the balls' reach: it has to be inside the sixty degrees
     * (allowing for its own width) and the fan, which rises a few degrees, has to reach its
     * height there. The balls that strike it are the share of the fan its body fills: dozens
     * point-blank, a handful at twenty blocks, one or two at the edge of reach. Five lines are
     * traced from the plate to points across the body; each one a wall, a hull or the ground
     * stops takes its share away, so cover works and half cover half works. The total lands
     * as a single blow when the balls arrive, so armour reduces it once rather than shrugging
     * off a stream of small hits.
     */
    private static void strikeFan(ServerLevel level, Vec3 muzzle, Vec3 ahead) {
        double perBall = WarnauticsConfig.claymoreBallDamage();
        if (perBall <= 0) {
            return;
        }
        Vec3 flat = new Vec3(ahead.x, 0, ahead.z);
        flat = flat.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : flat.normalize();
        int balls = WarnauticsConfig.claymoreBalls();
        double halfAngle = WarnauticsConfig.claymoreHalfAngle();
        double speed = WarnauticsConfig.claymoreBallSpeed();
        double reach = speed * 20.0D;
        double tilt = Math.asin(Mth.clamp(ahead.y, -1.0D, 1.0D));
        double low = Math.tan(tilt + CLAYMORE_PITCH_MIN);
        double high = Math.tan(tilt + CLAYMORE_PITCH_MAX);
        var server = level.getServer();
        RandomSource random = level.getRandom();
        DamageSource source = MineDamageSource.claymoreBalls(level);
        AABB area = new AABB(muzzle, muzzle).inflate(reach);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (target.isSpectator()) {
                continue;
            }
            AABB box = target.getBoundingBox();
            Vec3 centre = box.getCenter();
            Vec3 toward = new Vec3(centre.x - muzzle.x, 0, centre.z - muzzle.z);
            double range = toward.length();
            if (range < 0.3D || range > reach || toward.dot(flat) <= 0) {
                continue;
            }
            // Inside the fan, counting the body's own width.
            double off = Math.acos(Mth.clamp(toward.dot(flat) / range, -1.0D, 1.0D));
            double halfWidth = Math.atan((box.getXsize() * 0.5D + CLAYMORE_BALL_SIZE * 0.5D) / range);
            if (off > halfAngle + halfWidth) {
                continue;
            }
            // How much of the fan's height the body covers out there.
            double bandBottom = muzzle.y + range * low - CLAYMORE_BALL_SIZE * 0.5D;
            double bandTop = muzzle.y + range * high + CLAYMORE_BALL_SIZE * 0.5D;
            double vertical = Math.max(0.0D, Math.min(bandTop, box.maxY) - Math.max(bandBottom, box.minY))
                    / Math.max(0.05D, bandTop - bandBottom);
            if (vertical <= 0) {
                continue;
            }
            // How much of the fan's width: the body's angular width over the fan's, only the
            // part of it that is actually inside the fan.
            double inside =
                    Math.max(0.0D, Math.min(halfAngle, off + halfWidth) - Math.max(-halfAngle, off - halfWidth));
            double horizontal = Math.min(1.0D, inside / (2.0D * halfAngle));
            double exposure = exposure(level, muzzle, box);
            if (exposure <= 0) {
                continue;
            }
            double expected = balls * horizontal * vertical * exposure;
            // Whole balls: the fractional part is a chance of one more.
            int hits = (int) Math.floor(expected + random.nextDouble());
            if (hits <= 0) {
                continue;
            }
            float damage = (float) (hits * perBall);
            int delay = (int) Math.max(1, Math.ceil(range / Math.max(0.1D, speed)));
            Runnable blow = () -> {
                if (target.isAlive()) {
                    target.invulnerableTime = 0;
                    target.hurt(source, damage);
                    target.invulnerableTime = 0;
                }
            };
            if (server == null) {
                blow.run();
            } else {
                server.tell(new net.minecraft.server.TickTask(server.getTickCount() + delay, blow));
            }
        }
    }

    /** Share of five lines from the plate to points across the body that nothing stops. */
    private static double exposure(ServerLevel level, Vec3 muzzle, AABB box) {
        Vec3 c = box.getCenter();
        double dx = box.getXsize() * 0.3D;
        double dz = box.getZsize() * 0.3D;
        double dy = box.getYsize() * 0.3D;
        Vec3[] points = {c, c.add(dx, -dy, dz), c.add(-dx, -dy, -dz), c.add(dx, dy, -dz), c.add(-dx, dy, dz)};
        int open = 0;
        for (Vec3 point : points) {
            if (com.cbc_more_content.compat.SableDropCompat.clearRay(level, muzzle, point, java.util.Set.of())) {
                open++;
            }
        }
        return open / (double) points.length;
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
        int balls = com.cbc_more_content.config.WarnauticsConfig.claymoreBalls();
        double halfAngle = com.cbc_more_content.config.WarnauticsConfig.claymoreHalfAngle();
        double speedMin = com.cbc_more_content.config.WarnauticsConfig.claymoreBallSpeed() - CLAYMORE_SPEED_SPREAD;
        double speedMax = com.cbc_more_content.config.WarnauticsConfig.claymoreBallSpeed() + CLAYMORE_SPEED_SPREAD;
        double[] velocities = new double[balls * 3];
        double sector = 2.0D * halfAngle / balls;
        for (int i = 0; i < balls; i++) {
            // Even across the fan, jittered within each slice, so there are no lanes to
            // walk through and no clumps.
            double yaw = heading - halfAngle + (i + random.nextDouble()) * sector;
            double pitch = tilt + Mth.lerp(random.nextDouble(), CLAYMORE_PITCH_MIN, CLAYMORE_PITCH_MAX);
            double speed = Mth.lerp(random.nextDouble(), speedMin, speedMax);
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
        double[] tracers = new double[(balls / 4) * 3];
        for (int i = 0; i < balls / 4; i++) {
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
