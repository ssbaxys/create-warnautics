package com.cbc_more_content.munitions;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.registry.ModSounds;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One cold-launched interceptor with a fuse for its assigned airborne target.
 * <p>
 * Flown like the real thing: a boost-sustain motor (a short, hard boost, then a weaker
 * sustain grain), proportional navigation once the seeker has the target ahead of it,
 * and lead pursuit while it is still swinging its nose round. Every turn is limited by
 * the lateral acceleration the airframe can pull at its current speed and flown through
 * an autopilot with a short lag, and hard turns bleed speed the motor then has to win
 * back, so the path is a set of smooth arcs rather than a string of kinks.
 */
public class Aim9Projectile extends Entity {
    public static final int EJECT_TICKS = 14;
    private static final int MOTOR_TICKS = 240;
    private static final double APPROACH_RANGE = 36;
    private static final double RECOVERY_TURN_RATE = .14;
    /** Boost grain burn after ignition; the sustain grain carries the rest of the flight. */
    public static final int BOOST_TICKS = 60;

    private static final double BOOST_SPEED = 5.8;
    private static final double SUSTAIN_SPEED = 5.45;
    /** Fraction of the speed error the motor closes per tick. */
    private static final double THRUST_RESPONSE = .16;
    /** Speed lost per tick per radian-per-tick of turn, per block-per-tick of speed. */
    private static final double INDUCED_DRAG = .08;
    /** Lateral acceleration limit, blocks per tick squared. */
    private static final double MAX_ACCELERATION = 1.5;
    /** Proportional navigation constant. */
    private static final double NAVIGATION_GAIN = 4;
    /** Seeker cone inside which navigation replaces pursuit. */
    private static final double NAVIGATION_CONE = Math.cos(Math.toRadians(50));
    // Keep adjacent flight chunks entity-ticking before crossing their boundary.
    private static final int TICKET_DISTANCE = 4;
    private static final EntityDataAccessor<Boolean> POWERED =
            SynchedEntityData.defineId(Aim9Projectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> BOOSTING =
            SynchedEntityData.defineId(Aim9Projectile.class, EntityDataSerializers.BOOLEAN);
    private static final TicketType<UUID> TICKET = TicketType.create("aim9", UUID::compareTo, 20);

    @Nullable
    private UUID targetId;

    private int ejectTicks = EJECT_TICKS;
    private int motorTicks;
    private int lostTicks;
    private Vec3 carrierVelocity = Vec3.ZERO;
    private Vec3 blastDrift = Vec3.ZERO;
    private Vec3 lastTargetVelocity = Vec3.ZERO;
    private Vec3 approachOffset = Vec3.ZERO;
    private double closestApproach = Double.MAX_VALUE;
    private int approachNumber;
    private int recoveryTicks;
    private boolean approachChecked;
    private boolean recovering;
    private boolean recoveryTurning;

    @Nullable
    private ChunkPos ticketChunk;

    private boolean finished;
    /** Negative until ignition, then the speed through the air. */
    private double airspeed = -1;

    private final Autopilot autopilot = new Autopilot();
    private final ClientFlightSmoother smoother = new ClientFlightSmoother();

    public Aim9Projectile(EntityType<? extends Aim9Projectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POWERED, false);
        builder.define(BOOSTING, false);
    }

    public boolean isPowered() {
        return this.entityData.get(POWERED);
    }

    /** True while the boost grain burns: the big flame and the thick smoke. */
    public boolean isBoosting() {
        return this.entityData.get(BOOSTING);
    }

    public boolean isEjecting() {
        return !isPowered() && !finished;
    }

    @Nullable
    public UUID targetId() {
        return targetId;
    }

    public void launch(CruiseMissileProjectile target, Vec3 inheritedVelocity) {
        targetId = target.getUUID();
        lastTargetVelocity = target.getDeltaMovement();
        carrierVelocity = inheritedVelocity;
        // Ejection follows world gravity, including when the rack is on a banked carrier.
        setDeltaMovement(inheritedVelocity.add(0, .95, 0));
        face(new Vec3(0, 1, 0));
        yRotO = getYRot();
        xRotO = getXRot();
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            smoother.seedVelocity(getDeltaMovement());
            smoother.tick(this);
            return;
        }
        if (!(level() instanceof ServerLevel server) || finished) {
            return;
        }
        var chunk = new ChunkPos(blockPosition());
        if (!chunk.equals(ticketChunk)) {
            releaseTicket(server);
            ticketChunk = chunk;
        }
        server.getChunkSource().addRegionTicket(TICKET, chunk, TICKET_DISTANCE, getUUID());
        Entity target = targetId == null ? null : server.getEntity(targetId);
        Vec3 motion = getDeltaMovement().subtract(blastDrift);
        if (ejectTicks > 0) {
            ejectTicks--;
            motion = motion.subtract(0, .022, 0);
            if (ejectTicks == 0) {
                entityData.set(POWERED, true);
                server.playSound(
                        null,
                        getX(),
                        getY(),
                        getZ(),
                        SoundEvents.FIREWORK_ROCKET_BLAST,
                        SoundSource.HOSTILE,
                        2.8f,
                        .75f);
                server.sendParticles(ParticleTypes.CLOUD, getX(), getY(), getZ(), 12, .2, .2, .2, .04);
            }
        } else {
            motorTicks++;
            if (target == null || !target.isAlive()) {
                if (++lostTicks > 12) {
                    finish(null, position(), false);
                    return;
                }
            } else {
                lostTicks = 0;
            }
            if (motorTicks > MOTOR_TICKS || isInWater()) {
                finish(null, position(), false);
                return;
            }
            Vec3 relative = motion.subtract(carrierVelocity);
            Vec3 heading = relative.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : relative.normalize();
            if (airspeed < 0) {
                airspeed = Math.max(.65, relative.length());
            }
            boolean boosting = motorTicks <= BOOST_TICKS;
            entityData.set(BOOSTING, boosting);
            // The motor pulls toward its grain's speed; every turn costs speed it must win back.
            airspeed += ((boosting ? BOOST_SPEED : SUSTAIN_SPEED) - airspeed) * THRUST_RESPONSE;
            airspeed -= INDUCED_DRAG * autopilot.turnRate() * airspeed;
            airspeed = Mth.clamp(airspeed, .65, BOOST_SPEED);
            if (target != null && target.isAlive()) {
                updateApproach(target, heading, motion, airspeed);
                double maxRate = recovering
                        ? RECOVERY_TURN_RATE
                        : Math.min(motorTicks < 10 ? .16 : .32, MAX_ACCELERATION / Math.max(.65, airspeed));
                // A wider, slower return arc instead of snapping onto a target behind us.
                Vec3 desired = recovering && !recoveryTurning ? heading : guidance(target, heading, airspeed);
                heading = autopilot.steer(heading, desired, maxRate, recovering ? .3 : .55);
            } else {
                heading = autopilot.steer(heading, heading, .1, .5);
            }
            motion = heading.scale(airspeed).add(carrierVelocity);
            carrierVelocity = carrierVelocity.scale(.9);
            if (tickCount % 8 == 0) {
                server.playSound(
                        null,
                        getX(),
                        getY(),
                        getZ(),
                        ModSounds.CRUISE_MISSILE_ENGINE.get(),
                        SoundSource.HOSTILE,
                        1.8f,
                        1.4f);
            }
        }
        motion = motion.add(blastDrift);
        blastDrift = blastDrift.scale(.82);
        setDeltaMovement(motion);
        Vec3 from = position(), to = from.add(motion);
        var block = server.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 stop = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;
        Vec3 hull = SableDropCompat.clipSubLevels(server, from, to);
        if (hull != null && (stop == null || from.distanceToSqr(hull) < from.distanceToSqr(stop))) {
            stop = hull;
        }
        CruiseMissileProjectile struck = null;
        // This proximity fuse belongs only to the assigned target and only after ignition.
        // Ordinary nearby missiles do not trip it.
        if (isPowered() && target instanceof CruiseMissileProjectile cruise && target.isAlive()) {
            Vec3 relative = motion.subtract(target.getDeltaMovement());
            Vec3 offset = target.position().subtract(from);
            double t = relative.lengthSqr() < 1.0E-8 ? 0 : Mth.clamp(offset.dot(relative) / relative.lengthSqr(), 0, 1);
            if (offset.subtract(relative.scale(t)).lengthSqr() <= 1.3 * 1.3) {
                Vec3 proximity = from.lerp(to, t);
                if (stop == null || from.distanceToSqr(proximity) < from.distanceToSqr(stop)) {
                    stop = proximity;
                    struck = cruise;
                }
            }
        }
        for (Entity other : server.getEntities(
                this, new AABB(from, to).inflate(3), e -> e.isAlive() && e.isPickable() && !e.isSpectator())) {
            Vec3 contact = MissileCollision.contact(this, other, from, to);
            if (contact != null && (stop == null || from.distanceToSqr(contact) < from.distanceToSqr(stop))) {
                stop = contact;
                struck = other instanceof CruiseMissileProjectile cruise ? cruise : null;
            }
        }
        if (stop != null) {
            finish(struck, stop, true);
            return;
        }
        setPos(to);
        face(motion);
    }

    private void updateApproach(Entity target, Vec3 heading, Vec3 motion, double speed) {
        Vec3 offset = target.position().subtract(position());
        Vec3 velocity = target.getDeltaMovement();
        Vec3 acceleration = velocity.subtract(lastTargetVelocity);
        lastTargetVelocity = velocity;
        double distance = offset.length();
        boolean closing = offset.dot(motion.subtract(velocity)) > 0;
        if (recovering) {
            recoveryTicks++;
            if (!recoveryTurning) {
                // Turning immediately after a near miss leaves the target inside the minimum
                // turning circle. Extend beyond it before starting the return, including a fast tail chase.
                recoveryTurning = recoveryTicks >= 6 && distance >= 44;
                return;
            }
            if (recoveryTicks < 8 || !closing || distance < 10 || heading.dot(lead(offset, velocity, speed)) < .94) {
                return;
            }
            recovering = false;
            recoveryTurning = false;
            approachChecked = false;
            closestApproach = Double.MAX_VALUE;
        }
        if (!approachChecked && closing && distance <= APPROACH_RANGE) {
            approachChecked = true;
            closestApproach = distance;
            double chance = MissileGuidanceError.interceptMissChance(offset, motion, velocity, acceleration);
            if (MissileGuidanceError.missesIntercept(getUUID(), approachNumber, chance)) {
                approachOffset =
                        MissileGuidanceError.interceptOffset(getUUID(), approachNumber, lead(offset, velocity, speed));
            }
            approachNumber++;
        }
        if (approachChecked) {
            closestApproach = Math.min(closestApproach, distance);
            if (!closing && (distance > closestApproach + 2 || heading.dot(offset.normalize()) < -.1)) {
                recovering = true;
                recoveryTurning = false;
                recoveryTicks = 0;
                approachOffset = Vec3.ZERO;
            }
        }
    }

    /**
     * Commanded direction. Proportional navigation once the target sits inside the seeker
     * cone and is closing: it turns the line-of-sight rate into a turn rate, which flies a
     * collision course instead of a curving tail chase. Outside the cone, or opening, it
     * points at the lead point first.
     */
    private Vec3 guidance(Entity target, Vec3 heading, double speed) {
        Vec3 offset = target.position().subtract(position()).add(approachOffset);
        Vec3 targetVelocity = target.getDeltaMovement();
        Vec3 leadDirection = lead(offset, targetVelocity, speed);
        double range = offset.length();
        if (range < 1.0E-4) {
            return heading;
        }
        Vec3 lineOfSight = offset.scale(1 / range);
        Vec3 relative = targetVelocity.subtract(heading.scale(speed));
        double closing = -lineOfSight.dot(relative);
        if (motorTicks < 12 || closing <= 0 || heading.dot(lineOfSight) < NAVIGATION_CONE) {
            return leadDirection;
        }
        // Line-of-sight rotation rate, radians per tick. Command N times that rate; the
        // autopilot turns a heading error into rate at its own gain, so the command is
        // expressed as the heading it would be steering toward.
        Vec3 losRate = offset.cross(relative).scale(1 / (range * range));
        return Autopilot.rotate(heading, losRate.scale(NAVIGATION_GAIN / .32));
    }

    private static Vec3 lead(Vec3 offset, Vec3 velocity, double speed) {
        double a = velocity.lengthSqr() - speed * speed;
        double b = 2 * offset.dot(velocity), c = offset.lengthSqr();
        double time = Math.sqrt(c) / Math.max(.5, speed);
        double discriminant = b * b - 4 * a * c;
        if (Math.abs(a) > 1.0E-6 && discriminant >= 0) {
            double first = (-b - Math.sqrt(discriminant)) / (2 * a);
            double second = (-b + Math.sqrt(discriminant)) / (2 * a);
            if (first > 0 || second > 0) {
                time = first > 0 && second > 0 ? Math.min(first, second) : Math.max(first, second);
            }
        }
        return offset.add(velocity.scale(Mth.clamp(time, 0, 45))).normalize();
    }

    private void finish(@Nullable CruiseMissileProjectile target, Vec3 at, boolean burst) {
        if (finished || !(level() instanceof ServerLevel server)) {
            return;
        }
        finished = true;
        entityData.set(POWERED, false);
        entityData.set(BOOSTING, false);
        if (burst) {
            if (target != null && target.isAlive()) {
                target.hurt(server.damageSources().explosion(this, this), 40);
            }
            // Fragmentation burst in the air; not a second MOAB-sized terrain warhead.
            server.explode(this, at.x, at.y, at.z, 2, Level.ExplosionInteraction.NONE);
            server.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 22, .8, .8, .8, .1);
        } else {
            server.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 6, .2, .2, .2, .02);
        }
        discard();
    }

    private void face(Vec3 motion) {
        if (motion.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 dir = motion.normalize();
        yRotO = getYRot();
        xRotO = getXRot();
        setYRot((float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90);
        setXRot((float) -Math.toDegrees(Math.asin(Mth.clamp(dir.y, -1, 1))));
    }

    public void applyBlastImpulse(Vec3 impulse) {
        blastDrift = blastDrift.add(impulse);
        setDeltaMovement(getDeltaMovement().add(impulse));
        hasImpulse = true;
        hurtMarked = true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (amount <= 0 || finished || isInvulnerableTo(source)) {
            return false;
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) && amount < 22) {
            return false;
        }
        if (!level().isClientSide) {
            finish(null, position(), true);
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !finished;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512 * 512;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        smoother.target(this, x, y, z);
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        smoother.seedVelocity(new Vec3(x, y, z));
    }

    private void releaseTicket(ServerLevel server) {
        if (ticketChunk != null) {
            server.getChunkSource().removeRegionTicket(TICKET, ticketChunk, TICKET_DISTANCE, getUUID());
            ticketChunk = null;
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (level() instanceof ServerLevel server) {
            releaseTicket(server);
        }
        super.remove(reason);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        targetId = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
        ejectTicks = Mth.clamp(tag.getInt("EjectTicks"), 0, EJECT_TICKS);
        motorTicks = Mth.clamp(tag.getInt("MotorTicks"), 0, MOTOR_TICKS + 1);
        lostTicks = tag.getInt("LostTicks");
        carrierVelocity = new Vec3(tag.getDouble("CarrierX"), tag.getDouble("CarrierY"), tag.getDouble("CarrierZ"));
        blastDrift = new Vec3(tag.getDouble("BlastDriftX"), tag.getDouble("BlastDriftY"), tag.getDouble("BlastDriftZ"));
        lastTargetVelocity = new Vec3(
                tag.getDouble("TargetVelocityX"), tag.getDouble("TargetVelocityY"), tag.getDouble("TargetVelocityZ"));
        approachOffset = new Vec3(
                tag.getDouble("ApproachOffsetX"), tag.getDouble("ApproachOffsetY"), tag.getDouble("ApproachOffsetZ"));
        approachChecked = tag.getBoolean("ApproachChecked");
        recovering = tag.getBoolean("Recovering");
        recoveryTurning = tag.getBoolean("RecoveryTurning");
        approachNumber = Math.max(0, tag.getInt("ApproachNumber"));
        recoveryTicks = Math.max(0, tag.getInt("RecoveryTicks"));
        closestApproach =
                tag.contains("ClosestApproach") ? Math.max(0, tag.getDouble("ClosestApproach")) : Double.MAX_VALUE;
        airspeed = tag.contains("Airspeed") ? tag.getDouble("Airspeed") : -1;
        autopilot.load(tag, "TurnRate");
        entityData.set(POWERED, ejectTicks == 0 && motorTicks <= MOTOR_TICKS);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (targetId != null) {
            tag.putUUID("Target", targetId);
        }
        tag.putInt("EjectTicks", ejectTicks);
        tag.putInt("MotorTicks", motorTicks);
        tag.putInt("LostTicks", lostTicks);
        tag.putDouble("CarrierX", carrierVelocity.x);
        tag.putDouble("CarrierY", carrierVelocity.y);
        tag.putDouble("CarrierZ", carrierVelocity.z);
        tag.putDouble("BlastDriftX", blastDrift.x);
        tag.putDouble("BlastDriftY", blastDrift.y);
        tag.putDouble("BlastDriftZ", blastDrift.z);
        tag.putDouble("TargetVelocityX", lastTargetVelocity.x);
        tag.putDouble("TargetVelocityY", lastTargetVelocity.y);
        tag.putDouble("TargetVelocityZ", lastTargetVelocity.z);
        tag.putDouble("ApproachOffsetX", approachOffset.x);
        tag.putDouble("ApproachOffsetY", approachOffset.y);
        tag.putDouble("ApproachOffsetZ", approachOffset.z);
        tag.putBoolean("ApproachChecked", approachChecked);
        tag.putBoolean("Recovering", recovering);
        tag.putBoolean("RecoveryTurning", recoveryTurning);
        tag.putInt("ApproachNumber", approachNumber);
        tag.putInt("RecoveryTicks", recoveryTicks);
        tag.putDouble("ClosestApproach", closestApproach);
        tag.putDouble("Airspeed", airspeed);
        autopilot.save(tag, "TurnRate");
    }
}
