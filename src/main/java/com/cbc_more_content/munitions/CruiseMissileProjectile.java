package com.cbc_more_content.munitions;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.RadarCompat;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.radar.InterceptSettings;
import com.cbc_more_content.radar.InterceptSettingsStore;
import com.cbc_more_content.registry.ModSounds;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * A land-attack cruise missile flown as an aircraft rather than a rocket.
 * <p>
 * A solid booster throws it off the rail and burns out after {@link #BOOST_TICKS}, then
 * separates; a turbofan sustainer carries it the rest of the way. Airspeed is a state,
 * not a setting: the engine pulls it toward the profile's planned speed, climbing bleeds
 * it and diving adds to it. Turns are limited by a lateral acceleration budget, so the
 * same airframe turns tighter when slow, and the airframe banks into every turn. Between
 * launch and the terminal approach it holds a set height over the ground or the sea,
 * reading the heightmap ahead of the nose. Out of fuel it glides on its wings until it
 * drops below flying speed.
 */
public class CruiseMissileProjectile extends Entity {
    public static final int FUEL_TICKS = 480;
    /** Booster burn; separation and sustainer ignition follow. */
    public static final int BOOST_TICKS = 36;

    public static final byte PHASE_EJECT = 0;
    public static final byte PHASE_BOOST = 1;
    public static final byte PHASE_CRUISE = 2;
    public static final byte PHASE_TERMINAL = 3;
    public static final byte PHASE_GLIDE = 4;

    private static final double CRUISE_SPEED = 1.4D;
    private static final double GRAVITY = 0.085D;
    private static final int ARMING_TICKS = 4;

    /** Unpowered: little drag on a clean airframe, and lift fading out below flying speed. */
    private static final double GLIDE_DRAG = 0.985D;

    private static final double STALL_SPEED = 2.2D;
    private static final double MAX_GLIDE_LIFT = 0.8D;
    /** Fraction of the speed error the engine closes each tick. */
    private static final double ENGINE_RESPONSE = 0.18D;
    /** Airspeed traded per tick for a vertical climb (sin of the flight path angle). */
    private static final double CLIMB_COST = 0.05D;

    private static final double MAX_AIRSPEED = 4.9D;
    /** Lateral acceleration budgets, blocks per tick squared. Turn rate is this over airspeed. */
    private static final double CRUISE_G = 0.46D;

    private static final double MAX_TURN = 0.16D;
    private static final double CLIMB_LIMIT = 0.55D;
    private static final double DIVE_LIMIT = 0.5D;
    /** Terrain is read at these distances along the nose. */
    private static final double[] TERRAIN_SAMPLES = {0, 6, 14, 24, 36, 50};
    /** Gravity used only to turn a turn rate into a believable bank angle. */
    private static final double BANK_G = 0.12D;

    private static final float MAX_ROLL = 1.2f;

    private static final TicketType<Long> MISSILE_TICKET = TicketType.create("cruise_missile", Long::compareTo, 20);
    private static final int CHUNK_TICKET_RADIUS = 2;

    private static final EntityDataAccessor<Boolean> POWERED =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> EJECTING =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> PHASE =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> ROLL =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.FLOAT);

    public static final int EJECT_TICKS = 14;
    public static final double EJECT_SPEED = 0.62D;
    private static final double EJECT_GRAVITY = 0.03D;

    private static final double TURN_RATE = 0.11D;
    private static final double LOOKAHEAD = 36.0D;
    private static final double TERMINAL_RANGE = 44.0D;
    private static final double TERMINAL_TURN_RATE = 0.30D;
    /** Terminal manoeuvre budget: harder than cruise, still an airframe's, not a dart's. */
    private static final double TERMINAL_G = 1.0D;
    /** Stiffer autopilot for the last seconds, so the nose settles on the aim point. */
    private static final double TERMINAL_GAIN = 0.6D;

    private static final double FUSE_RANGE = 2.4D;
    private static final double JINK_DOT = 0.55D;
    private static final float JINK_CHANCE = 0.4f;
    private static final int SHAKEN_TICKS = 26;

    private static final float BLOCK_POWER = BombSize.MOAB.blockBlastPower * 0.85f;

    private static final float ENTITY_POWER = BombSize.MOAB.entityBlastPower * 0.85f;

    private int fuel = WarnauticsConfig.cruiseFuelTicks();
    private double fuelFraction;
    /** Negative until the motor first takes over, then the speed through the air. */
    private double airspeed = -1;

    private float roll;
    private boolean boosterSeparated;
    private float clientRoll;
    private float clientRollO;
    private int poweredTicks;
    private MissileFlightProfile flightProfile = MissileFlightProfile.DIRECT;
    private boolean detonated;
    private boolean waterEntered;
    private boolean trackingHull;
    private int shaken;

    @Nullable
    private Vec3 lastAim;

    @Nullable
    private Vec3 lastAimDrift;

    private int ejecting;
    private Vec3 carrierVelocity = Vec3.ZERO;
    private Vec3 blastDrift = Vec3.ZERO;

    @Nullable
    private java.util.UUID salvoId;

    private int salvoIndex;
    private int salvoCount = 1;
    private Vec3 salvoOrigin = Vec3.ZERO;
    private final java.util.Set<ChunkPos> ticketChunks = new java.util.HashSet<>();

    @Nullable
    private ChunkPos ticketCenter;

    private final MissileTargetingState targeting = new MissileTargetingState();
    private final Autopilot autopilot = new Autopilot();
    private final ClientFlightSmoother smoother = new ClientFlightSmoother();
    /** Client: wing and fin deployment, 0 folded to 1 out, with a little spring overshoot. */
    private float clientDeploy = -1;

    private float clientDeployO;
    private float clientDeployVelocity;

    public CruiseMissileProjectile(EntityType<? extends CruiseMissileProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POWERED, true);
        builder.define(EJECTING, false);
        builder.define(PHASE, PHASE_BOOST);
        builder.define(ROLL, 0.0f);
    }

    public boolean isPowered() {
        return this.entityData.get(POWERED);
    }

    /** Flight phase as the client sees it: drives booster flame, sustainer jet and glide. */
    public byte phase() {
        return this.entityData.get(PHASE);
    }

    /**
     * Client: how far the wings and fins are out. They stay folded on the rail and through
     * the boost, and spring out once the booster has dropped away, as on the real airframe.
     */
    public float wingDeploy(float partialTick) {
        return Mth.lerp(partialTick, this.clientDeployO, Math.max(0, this.clientDeploy));
    }

    /** Bank angle in radians, positive with the left wing down; smoothed for rendering. */
    public float roll(float partialTick) {
        return Mth.lerp(partialTick, this.clientRollO, this.clientRoll);
    }

    public void setController(@Nullable BlockPos controller) {
        this.targeting.setController(controller);
    }

    public void setGuidance(Guidance guidance, @Nullable BlockPos target, int lockedSubLevel) {
        this.targeting.setGuidance(guidance, target, lockedSubLevel);
    }

    public void setFlightProfile(MissileFlightProfile profile) {
        this.flightProfile = profile;
    }

    public MissileFlightProfile flightProfile() {
        return this.flightProfile;
    }

    public void setSalvo(java.util.UUID id, int index, int count) {
        this.setSalvo(id, index, count, this.position());
    }

    public void setSalvo(java.util.UUID id, int index, int count, Vec3 origin) {
        this.salvoId = id;
        this.salvoIndex = Math.clamp(index, 0, 15);
        this.salvoCount = Math.clamp(count, 1, 16);
        this.salvoOrigin = origin;
    }

    public boolean isEjecting() {
        return this.entityData.get(EJECTING);
    }

    public void ejectUpward() {
        this.eject(new Vec3(0, 1, 0), Vec3.ZERO);
    }

    public void eject(Vec3 heading, Vec3 carrierVelocity) {
        this.carrierVelocity = carrierVelocity;
        this.ejecting = EJECT_TICKS;
        this.entityData.set(EJECTING, true);
        this.entityData.set(POWERED, false);
        this.setDeltaMovement(heading.normalize().scale(EJECT_SPEED).add(carrierVelocity));
        this.faceMotion(heading);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    public void launch(Vec3 heading) {
        this.launch(heading, Vec3.ZERO);
    }

    public void launch(Vec3 heading, Vec3 carrierVelocity) {
        this.carrierVelocity = carrierVelocity;
        Vec3 dir = heading.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : heading.normalize();
        this.setDeltaMovement(dir.scale(CRUISE_SPEED).add(carrierVelocity));
        this.setYRot((float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0f);
        this.setXRot((float) (-Math.asin(dir.y) * 180.0D / Math.PI));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.smoother.seedVelocity(this.getDeltaMovement());
            this.smoother.tick(this);
            this.clientRollO = this.clientRoll;
            this.clientRoll += (this.entityData.get(ROLL) - this.clientRoll) * 0.5f;
            byte phase = this.phase();
            float deployed = phase == PHASE_EJECT || phase == PHASE_BOOST ? 0 : 1;
            if (this.clientDeploy < 0) {
                // Leaving the rail it still has the full span it stood with, and folds the
                // fins in as it goes; first seen later in flight, it is shown as it is.
                this.clientDeploy = phase == PHASE_EJECT ? 1 : deployed;
            }
            this.clientDeployO = this.clientDeploy;
            this.clientDeployVelocity += (deployed - this.clientDeploy) * 0.42f;
            this.clientDeployVelocity *= 0.55f;
            this.clientDeploy = Mth.clamp(this.clientDeploy + this.clientDeployVelocity, 0, 1.12f);
            return;
        }
        if (this.detonated) {
            return;
        }

        this.refreshChunkTickets();
        if (this.ejecting > 0) {
            this.coastOutOfRack();
            this.entityData.set(PHASE, PHASE_EJECT);
            return;
        }

        if (!this.waterEntered && this.isInWater()) {
            this.waterEntered = true;
            this.entityData.set(POWERED, false);
            this.level()
                    .playSound(
                            null,
                            this.getX(),
                            this.getY(),
                            this.getZ(),
                            SoundEvents.FIRE_EXTINGUISH,
                            SoundSource.HOSTILE,
                            2.0f,
                            0.55f);
        }

        Vec3 aim = this.aimPoint();
        if (this.fuel > 0 && !this.waterEntered) {
            this.poweredTicks++;
            double range =
                    aim == null ? Double.POSITIVE_INFINITY : this.position().distanceTo(aim);
            double plannedSpeed = this.flightProfile.speed(
                    this.poweredTicks, range, this.getUUID().getLeastSignificantBits());
            this.fuelFraction += this.flightProfile.fuelPerTick(plannedSpeed);
            int spent = (int) this.fuelFraction;
            this.fuelFraction -= spent;
            this.fuel = Math.max(0, this.fuel - spent);
            if (this.fuel == 0) {
                this.entityData.set(POWERED, false);
                this.level()
                        .playSound(
                                null,
                                this.getX(),
                                this.getY(),
                                this.getZ(),
                                SoundEvents.FIRE_EXTINGUISH,
                                SoundSource.HOSTILE,
                                2.2f,
                                0.6f);
            }
        }

        this.watchForJink(aim);

        Vec3 motion = this.getDeltaMovement().subtract(this.blastDrift);
        if (this.isPowered() && !this.waterEntered) {
            Vec3 relative = motion.subtract(this.carrierVelocity);
            Vec3 heading = relative.lengthSqr() < 1.0E-6D ? MissileCollision.heading(this) : relative.normalize();
            if (this.airspeed < 0) {
                this.airspeed = relative.length();
            }
            // The engine closes on the planned speed; the flight path angle trades height
            // for speed, so a pop-up slows the round and the dive that follows recovers it.
            double planned = this.speedFor(aim);
            this.airspeed += (planned - this.airspeed) * ENGINE_RESPONSE - CLIMB_COST * heading.y;
            this.airspeed = Mth.clamp(this.airspeed, 0.6D, Math.min(MAX_AIRSPEED, planned + 0.35D));
            Vec3 steered = this.steer(heading, aim);
            this.bank(heading, steered);
            motion = steered.scale(this.airspeed).add(this.carrierVelocity);
            // Keep the ship's momentum through rack separation, then let the motor take
            // over. A permanent carrier offset distorted every selected speed profile.
            this.carrierVelocity = this.carrierVelocity.scale(0.92D);
            if (!this.boosterSeparated && this.poweredTicks >= BOOST_TICKS) {
                this.separateBooster(heading);
            }
        } else {
            // Wings still carry it: lift fades as it slows, so a spent round glides on
            // and then drops instead of falling out of the sky like a shell.
            double speed = motion.length();
            double lift = this.waterEntered
                    ? 0
                    : Mth.clamp(speed * speed / (STALL_SPEED * STALL_SPEED), 0.0D, MAX_GLIDE_LIFT);
            motion = motion.scale(this.waterEntered ? 0.9D : GLIDE_DRAG).subtract(0.0D, GRAVITY * (1 - lift), 0.0D);
            this.roll *= 0.9f;
            this.entityData.set(ROLL, this.roll);
        }
        this.entityData.set(PHASE, this.currentPhase(aim));
        motion = motion.add(this.blastDrift);
        this.blastDrift = this.blastDrift.scale(.82);
        this.setDeltaMovement(motion);

        Vec3 from = this.position();
        Vec3 to = from.add(motion);
        if (this.tickCount > ARMING_TICKS && this.checkImpact(from, to)) {
            return;
        }

        this.setPos(to);
        this.faceMotion(motion);
        this.fuseOnTarget(from, aim);
    }

    private void coastOutOfRack() {
        this.ejecting--;
        Vec3 motion = this.getDeltaMovement().subtract(0.0D, EJECT_GRAVITY, 0.0D);
        this.setDeltaMovement(motion);
        this.setPos(this.position().add(motion));
        this.faceMotion(motion);

        if (this.ejecting > 0) {
            return;
        }

        this.entityData.set(EJECTING, false);
        this.entityData.set(POWERED, true);
        Vec3 relative = motion.subtract(this.carrierVelocity);
        Vec3 heading = relative.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 1.0D, 0.0D) : relative.normalize();
        this.setDeltaMovement(heading.scale(CRUISE_SPEED).add(this.carrierVelocity));
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        ModSounds.CRUISE_MISSILE_LAUNCH.get(),
                        SoundSource.HOSTILE,
                        5.0f,
                        0.72f);
    }

    private double speedFor(@Nullable Vec3 aim) {
        double range = aim == null ? Double.POSITIVE_INFINITY : this.position().distanceTo(aim);
        return this.flightProfile.speed(this.poweredTicks, range, this.getUUID().getLeastSignificantBits());
    }

    private void watchForJink(@Nullable Vec3 aim) {
        if (this.shaken > 0) {
            --this.shaken;
        }
        if (aim == null) {
            this.lastAim = null;
            this.lastAimDrift = null;
            return;
        }
        if (this.lastAim != null) {
            Vec3 drift = aim.subtract(this.lastAim);
            if (this.lastAimDrift != null
                    && drift.lengthSqr() > 0.0025D
                    && this.lastAimDrift.lengthSqr() > 0.0025D
                    && this.shaken == 0
                    && drift.normalize().dot(this.lastAimDrift.normalize()) < JINK_DOT
                    && this.position().distanceToSqr(aim) <= TERMINAL_RANGE * TERMINAL_RANGE
                    && this.random.nextFloat() < JINK_CHANCE) {
                this.shaken = SHAKEN_TICKS;
            }
            this.lastAimDrift = drift;
        }
        this.lastAim = aim;
    }

    /** Turn rate the airframe can pull at its current airspeed without exceeding its load limit. */
    private double cruiseTurnRate() {
        return Math.min(MAX_TURN, CRUISE_G / Math.max(0.5D, this.airspeed));
    }

    private Vec3 steer(Vec3 heading, @Nullable Vec3 aim) {
        if (aim == null) {
            // Unguided: keep the launch bearing, level off and clear the ground ahead.
            Vec3 level = this.holdAltitude(heading, this.getY());
            return this.autopilot.steer(
                    heading, limitPitch(this.separate(level)), Math.max(.12, this.cruiseTurnRate()), .45);
        }
        Vec3 toTarget = aim.subtract(this.position());
        double distance = toTarget.length();
        if (distance < 1.0E-4D) {
            return heading;
        }
        Vec3 shaped = this.flightProfile.shapedAim(
                this.position(), aim, this.poweredTicks, this.getUUID().getLeastSignificantBits());
        if (this.salvoId != null && this.salvoCount > 1) {
            Vec3 forward = aim.subtract(this.salvoOrigin).normalize();
            Vec3 side = forward.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < .01) {
                side = new Vec3(1, 0, 0);
            }
            side = side.normalize();
            Vec3 up = side.cross(forward).normalize();
            double angle = this.salvoIndex * (Math.PI * 2 / this.salvoCount);
            double radius = Math.max(3.5, this.salvoCount * .8) * Mth.clamp((distance - 5) / 24, 0, 1);
            // Rounds leave the rack a block apart and in no particular order around the
            // ring: let separation open the gaps first, then ease each into its station,
            // instead of steering neighbours straight across each other off the rail.
            double station = Mth.clamp((this.poweredTicks - 6) / 26.0, 0, 1);
            radius *= station * station * (3 - 2 * station);
            if (this.flightProfile != MissileFlightProfile.EVASIVE && distance > TERMINAL_RANGE) {
                // Hold a corridor beside the shared route, instead of a distant offset endpoint.
                double along = Mth.clamp(
                        this.position().subtract(this.salvoOrigin).dot(forward) + 30,
                        0,
                        aim.distanceTo(this.salvoOrigin));
                Vec3 profileOffset = shaped.subtract(aim).scale(Math.min(1, 30 / distance));
                shaped = this.salvoOrigin.add(forward.scale(along)).add(profileOffset);
            }
            shaped = shaped.add(side.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
        }
        Vec3 wanted = shaped.subtract(this.position()).normalize();
        if (distance > TERMINAL_RANGE) {
            // Cruise: the plan supplies the bearing and its preferred height, the terrain
            // underneath sets a floor, and the climb or descent is flown as a pitch command.
            wanted = this.holdAltitude(wanted, shaped.y);
            // Spacing from salvo neighbours stays inside the climb envelope: a round shoved
            // into a steep climb bleeds speed and is run down by the one behind it. Terrain
            // avoidance comes last and may still pull up as hard as it needs.
            Vec3 separated = this.avoid(heading, limitPitch(this.separate(wanted)));
            double rate = this.cruiseTurnRate();
            if (separated.dot(wanted) < .995) {
                // Avoiding a neighbour or the ground is urgent: a sharper, quicker response.
                return this.autopilot.steer(heading, separated, Math.max(.12, rate), .8, .6);
            }
            return this.autopilot.steer(heading, separated, rate, .45);
        }
        // Terminal: a harder but still finite turn, flown by a stiffer autopilot so the
        // nose settles onto the aim point instead of sawing across it.
        double terminalRate =
                this.shaken > 0 ? TURN_RATE : Math.min(TERMINAL_TURN_RATE, TERMINAL_G / Math.max(0.5D, this.airspeed));
        return this.autopilot.steer(
                heading, distance > 5 ? this.separate(wanted) : wanted, terminalRate, .7, TERMINAL_GAIN);
    }

    private Vec3 separate(Vec3 wanted) {
        Vec3 force = Vec3.ZERO;
        int count = 0;
        for (var other : this.level()
                .getEntitiesOfClass(
                        CruiseMissileProjectile.class,
                        this.getBoundingBox().inflate(8),
                        e -> e != this && e.isAlive())) {
            // Predict closest approach, rather than swerving only after contact is inevitable.
            Vec3 offset = this.position().subtract(other.position());
            Vec3 relative = this.getDeltaMovement().subtract(other.getDeltaMovement());
            double time =
                    relative.lengthSqr() < .001 ? 0 : Mth.clamp(-offset.dot(relative) / relative.lengthSqr(), 0, 4);
            Vec3 future = offset.add(relative.scale(time));
            double distance = Math.min(offset.length(), future.length());
            if (distance >= 6.5) {
                continue;
            }
            Vec3 away = future.lengthSqr() > .04 ? future : offset;
            if (away.lengthSqr() < .001) {
                away = new Vec3(this.getUUID().compareTo(other.getUUID()) < 0 ? 1 : -1, 0, 0);
            }
            force = force.add(away.normalize().scale((6.5 - distance) / 6.5));
            if (++count == 16) {
                break;
            }
        }
        return wanted.add(force.scale(1.5)).normalize();
    }

    private static Vec3 limitPitch(Vec3 direction) {
        double flat = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (flat < 0.05D) {
            return direction;
        }
        double pitch = Math.atan2(direction.y, flat);
        double limited = Mth.clamp(pitch, -DIVE_LIMIT, CLIMB_LIMIT);
        if (limited == pitch) {
            return direction;
        }
        double scale = Math.cos(limited) / flat;
        return new Vec3(direction.x * scale, Math.sin(limited), direction.z * scale);
    }

    /**
     * Keeps the bearing of {@code wanted} and replaces its pitch with a climb or descent toward
     * {@code preferred}, never below the profile's clearance over the terrain ahead.
     */
    private Vec3 holdAltitude(Vec3 wanted, double preferred) {
        Vec3 flat = new Vec3(wanted.x, 0, wanted.z);
        if (flat.lengthSqr() < 0.0025D) {
            // Still standing on its tail after a vertical launch with nowhere to go yet.
            return wanted;
        }
        flat = flat.normalize();
        double altitude = preferred;
        if (WarnauticsConfig.cruiseTerrainFollowing()) {
            double ground = this.terrainAhead(flat);
            if (!Double.isNaN(ground)) {
                altitude = Math.max(altitude, ground + this.flightProfile.clearance());
            }
        }
        // Aim at a point a few seconds ahead at the commanded height: a gentle, continuous
        // flare instead of pointing the nose straight at a height step.
        double lookahead = Math.max(18.0D, Math.max(0.5D, this.airspeed) * 9.0D);
        double pitch = Mth.clamp(Math.atan2(altitude - this.getY(), lookahead), -DIVE_LIMIT, CLIMB_LIMIT);
        return flat.scale(Math.cos(pitch)).add(0, Math.sin(pitch), 0);
    }

    /**
     * Highest surface on the track ahead, ground or sea. A column that is open at our own
     * height but roofed above it is an overhang or a hangar, so the floor there is the ground
     * underneath rather than the roof.
     */
    private double terrainAhead(Vec3 flat) {
        double best = Double.NaN;
        Level level = this.level();
        Vec3 at = this.position();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (double distance : TERRAIN_SAMPLES) {
            int x = Mth.floor(at.x + flat.x * distance);
            int z = Mth.floor(at.z + flat.z * distance);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            double surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (surface > at.y + 1) {
                cursor.set(x, Mth.floor(at.y), z);
                var state = level.getBlockState(cursor);
                if (state.getCollisionShape(level, cursor).isEmpty()
                        && state.getFluidState().isEmpty()) {
                    surface = this.groundBelow(level, cursor);
                }
            }
            best = Double.isNaN(best) ? surface : Math.max(best, surface);
        }
        return best;
    }

    private double groundBelow(Level level, BlockPos.MutableBlockPos cursor) {
        int top = cursor.getY();
        for (int y = top; y > top - 48 && y > level.getMinBuildHeight(); y--) {
            cursor.setY(y);
            var state = level.getBlockState(cursor);
            if (!state.getCollisionShape(level, cursor).isEmpty()
                    || !state.getFluidState().isEmpty()) {
                return y + 1;
            }
        }
        return top - 48;
    }

    /** Banks into the turn just flown, the way a winged airframe has to. */
    private void bank(Vec3 before, Vec3 after) {
        double ax = before.x, az = before.z, bx = after.x, bz = after.z;
        double lengths = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
        float target = 0;
        if (lengths > 1.0E-4D) {
            // Positive for a left turn: the y component of before x after.
            double yawRate = Math.asin(Mth.clamp((az * bx - ax * bz) / lengths, -1, 1));
            target = (float) Mth.clamp(Math.atan2(yawRate * this.airspeed, BANK_G), -MAX_ROLL, MAX_ROLL);
        }
        this.roll += (target - this.roll) * 0.3f;
        this.entityData.set(ROLL, this.roll);
    }

    private byte currentPhase(@Nullable Vec3 aim) {
        if (!this.isPowered() || this.waterEntered) {
            return PHASE_GLIDE;
        }
        if (this.poweredTicks <= BOOST_TICKS) {
            return PHASE_BOOST;
        }
        return aim != null && this.position().distanceToSqr(aim) <= TERMINAL_RANGE * TERMINAL_RANGE
                ? PHASE_TERMINAL
                : PHASE_CRUISE;
    }

    /** Burnout: the spent booster casing drops away and the sustainer takes over. */
    /**
     * Booster burnout: a report and a clank at the tail. The separation itself is drawn on
     * the client as a puff of gas; nothing is thrown off, so no stray block lands on the
     * ground where the booster let go.
     */
    private void separateBooster(Vec3 heading) {
        this.boosterSeparated = true;
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 tail = this.position().subtract(heading.scale(1.6D));
        server.playSound(
                null, tail.x, tail.y, tail.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 2.6f, .55f);
        server.playSound(null, tail.x, tail.y, tail.z, SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.2f, .6f);
    }

    private void fuseOnTarget(Vec3 from, @Nullable Vec3 aim) {
        // A locked physical hull uses the swept collision fuse. Its bounding-box
        // centre is a guidance point, not a reason to explode in empty air outside a small craft.
        if (this.detonated || aim == null || this.tickCount <= ARMING_TICKS || this.trackingHull || this.shaken > 0) {
            return;
        }
        // Coordinates and radar points still have a proximity fuse. Test the whole
        // travelled segment so a fast missile cannot step over it or fuse on a wide miss.
        Vec3 span = this.position().subtract(from);
        double lengthSqr = span.lengthSqr();
        double part = lengthSqr < 1.0E-8D ? 0 : Mth.clamp(aim.subtract(from).dot(span) / lengthSqr, 0, 1);
        Vec3 nearest = from.add(span.scale(part));
        if (nearest.distanceToSqr(aim) <= FUSE_RANGE * FUSE_RANGE) {
            this.detonate(nearest);
        }
    }

    @Nullable
    private Vec3 aimPoint() {
        this.trackingHull = false;
        if (this.targeting.guidance() == Guidance.INTERCEPT) {
            Vec3 tracked = this.radarAim();
            return tracked == null ? null : this.dispersedAim(tracked, 1.8);
        }
        if (this.targeting.guidance() == Guidance.LOCK
                && this.targeting.lockedSubLevel() >= 0
                && ModList.get().isLoaded("sable")
                && this.level() instanceof ServerLevel server) {
            Vec3 tracked = SableDropCompat.subLevelCentre(server, this.targeting.lockedSubLevel());
            if (tracked != null) {
                this.trackingHull = true;
                return this.dispersedAim(tracked, .6);
            }
        }
        return this.targeting.target() == null || this.targeting.guidance() == Guidance.NONE
                ? null
                : this.dispersedAim(Vec3.atCenterOf(this.targeting.target()), 1.8);
    }

    private Vec3 dispersedAim(Vec3 target, double radius) {
        return target.add(MissileGuidanceError.cruiseOffset(this.getUUID(), radius));
    }

    /** The motor recovers over several ticks instead of erasing a pressure impulse immediately. */
    public void applyBlastImpulse(Vec3 impulse) {
        this.blastDrift = this.blastDrift.add(impulse);
        this.setDeltaMovement(this.getDeltaMovement().add(impulse));
        this.hasImpulse = true;
        this.hurtMarked = true;
    }

    @Nullable
    private Vec3 radarAim() {
        if (this.targeting.controller() == null || !RadarCompat.loaded()) {
            return null;
        }
        if (this.targeting.contact() != null) {
            var held = RadarCompat.contactById(this.level(), this.targeting.controller(), this.targeting.contact());
            if (held != null) {
                return held.position();
            }
            this.targeting.setContact(null);
        }
        var settings = this.level() instanceof ServerLevel server
                ? InterceptSettingsStore.get(server).forController(this.targeting.controller())
                : InterceptSettings.DEFAULT;
        var fresh = RadarCompat.bestContact(this.level(), this.targeting.controller(), this.position(), settings);
        if (fresh == null) {
            return null;
        }
        this.targeting.setContact(fresh.id());
        return fresh.position();
    }

    private Vec3 avoid(Vec3 heading, Vec3 wanted) {
        Vec3 from = this.position();
        BlockHitResult hit = this.level()
                .clip(new ClipContext(
                        from,
                        from.add(heading.scale(LOOKAHEAD)),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        this));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return wanted;
        }

        double closeness = 1.0D - Math.sqrt(hit.getLocation().distanceToSqr(from)) / LOOKAHEAD;
        Vec3 lift = new Vec3(
                hit.getDirection().getStepX(),
                Math.max(0.35D, hit.getDirection().getStepY()),
                hit.getDirection().getStepZ());
        return wanted.add(lift.scale(Mth.clamp(closeness, 0.0D, 1.0D) * 1.4D)).normalize();
    }

    private boolean checkImpact(Vec3 from, Vec3 to) {
        BlockHitResult block =
                this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 stop = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;

        if (ModList.get().isLoaded("sable") && this.level() instanceof ServerLevel server) {
            Vec3 hull = SableDropCompat.clipSubLevels(server, from, to);
            if (hull != null && (stop == null || from.distanceToSqr(hull) < from.distanceToSqr(stop))) {
                stop = hull;
            }
        }

        AABB sweep = new AABB(from, to).inflate(3);
        for (Entity entity : this.level().getEntities(this, sweep, this::canHit)) {
            Vec3 at = MissileCollision.contact(this, entity, from, to);
            if (at != null && (stop == null || from.distanceToSqr(at) < from.distanceToSqr(stop))) {
                stop = at;
            }
        }

        if (stop == null) {
            return false;
        }
        this.detonate(stop);
        return true;
    }

    private boolean canHit(Entity entity) {
        return entity.isAlive() && entity.isPickable() && !entity.isSpectator();
    }

    private void refreshChunkTickets() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        ChunkPos center = new ChunkPos(this.blockPosition());
        if (center.equals(this.ticketCenter) && this.tickCount % 10 != 0) {
            return;
        }
        var previous = this.ticketChunks.iterator();
        while (previous.hasNext()) {
            ChunkPos old = previous.next();
            if (Math.abs(old.x - center.x) > CHUNK_TICKET_RADIUS || Math.abs(old.z - center.z) > CHUNK_TICKET_RADIUS) {
                server.getChunkSource()
                        .removeRegionTicket(
                                MISSILE_TICKET, old, 2, this.getUUID().getLeastSignificantBits());
                previous.remove();
            }
        }
        this.ticketCenter = center;
        for (int x = -CHUNK_TICKET_RADIUS; x <= CHUNK_TICKET_RADIUS; x++) {
            for (int z = -CHUNK_TICKET_RADIUS; z <= CHUNK_TICKET_RADIUS; z++) {
                ChunkPos active = new ChunkPos(center.x + x, center.z + z);
                this.ticketChunks.add(active);
                server.getChunkSource()
                        .addRegionTicket(
                                MISSILE_TICKET, active, 2, this.getUUID().getLeastSignificantBits());
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel server) {
            for (ChunkPos chunk : this.ticketChunks) {
                server.getChunkSource()
                        .removeRegionTicket(
                                MISSILE_TICKET, chunk, 2, this.getUUID().getLeastSignificantBits());
            }
            this.ticketChunks.clear();
            this.ticketCenter = null;
        }
        super.remove(reason);
    }

    private void detonate(Vec3 at) {
        if (this.detonated || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.detonated = true;
        try {
            detonateWarhead(server, this, at);
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.error("Cruise missile detonation failed at {}", at, t);
        } finally {
            this.discard();
        }
    }

    /** The same warhead is used when the placed airframe is destroyed by an explosion. */
    public static void detonateWarhead(ServerLevel level, @Nullable Entity source, Vec3 at) {
        BombExplosionHandler.detonate(
                level, source, BombDamageSource.create(level), at, BLOCK_POWER, ENTITY_POWER, BombSize.MOAB);
    }

    private void faceMotion(Vec3 motion) {
        if (motion.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 dir = motion.normalize();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        this.setYRot((float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0f);
        this.setXRot((float) (-Math.asin(dir.y) * 180.0D / Math.PI));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (amount <= 0 || this.detonated || this.isInvulnerableTo(source)) {
            return false;
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) && amount < 28) {
            return false;
        }
        if (!this.level().isClientSide && !this.detonated) {
            this.detonate(this.position());
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !this.detonated;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        // Server tracking reaches 32 chunks. Vanilla's size-based renderer cutoff was
        // much shorter, making the airframe disappear while its chunks were still in view.
        return distanceSqr < 512.0D * 512.0D;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        this.smoother.target(this, x, y, z);
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        this.smoother.seedVelocity(new Vec3(x, y, z));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.fuel = tag.getInt("Fuel");
        this.fuelFraction = tag.getDouble("FuelFraction");
        this.poweredTicks = tag.getInt("PoweredTicks");
        this.flightProfile = MissileFlightProfile.byId(tag.getInt("FlightProfile"));
        this.airspeed = tag.contains("Airspeed") ? tag.getDouble("Airspeed") : -1;
        this.autopilot.load(tag, "TurnRate");
        this.roll = Mth.clamp(tag.getFloat("Roll"), -MAX_ROLL, MAX_ROLL);
        this.entityData.set(ROLL, this.roll);
        // Older saves had no booster state: anything already past burnout has shed it.
        this.boosterSeparated = tag.contains("BoosterSeparated")
                ? tag.getBoolean("BoosterSeparated")
                : this.poweredTicks >= BOOST_TICKS;
        this.entityData.set(POWERED, tag.getBoolean("Powered"));
        this.waterEntered = tag.getBoolean("WaterEntered");
        this.ejecting = Math.clamp(tag.getInt("Ejecting"), 0, EJECT_TICKS);
        this.entityData.set(EJECTING, this.ejecting > 0);
        this.carrierVelocity =
                new Vec3(tag.getDouble("CarrierX"), tag.getDouble("CarrierY"), tag.getDouble("CarrierZ"));
        this.blastDrift =
                new Vec3(tag.getDouble("BlastDriftX"), tag.getDouble("BlastDriftY"), tag.getDouble("BlastDriftZ"));
        this.targeting.readFrom(tag);
        this.salvoId = tag.hasUUID("Salvo") ? tag.getUUID("Salvo") : null;
        this.salvoIndex = Math.clamp(tag.getInt("SalvoIndex"), 0, 15);
        this.salvoCount = Math.clamp(tag.getInt("SalvoCount"), 1, 16);
        this.salvoOrigin = new Vec3(tag.getDouble("SalvoX"), tag.getDouble("SalvoY"), tag.getDouble("SalvoZ"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Fuel", this.fuel);
        tag.putDouble("FuelFraction", this.fuelFraction);
        tag.putInt("PoweredTicks", this.poweredTicks);
        tag.putInt("FlightProfile", this.flightProfile.id());
        tag.putDouble("Airspeed", this.airspeed);
        this.autopilot.save(tag, "TurnRate");
        tag.putFloat("Roll", this.roll);
        tag.putBoolean("BoosterSeparated", this.boosterSeparated);
        tag.putBoolean("Powered", this.isPowered());
        tag.putBoolean("WaterEntered", this.waterEntered);
        tag.putInt("Ejecting", this.ejecting);
        tag.putDouble("CarrierX", this.carrierVelocity.x);
        tag.putDouble("CarrierY", this.carrierVelocity.y);
        tag.putDouble("CarrierZ", this.carrierVelocity.z);
        tag.putDouble("BlastDriftX", this.blastDrift.x);
        tag.putDouble("BlastDriftY", this.blastDrift.y);
        tag.putDouble("BlastDriftZ", this.blastDrift.z);
        this.targeting.writeTo(tag);
        if (this.salvoId != null) {
            tag.putUUID("Salvo", this.salvoId);
        }
        tag.putInt("SalvoIndex", this.salvoIndex);
        tag.putInt("SalvoCount", this.salvoCount);
        tag.putDouble("SalvoX", this.salvoOrigin.x);
        tag.putDouble("SalvoY", this.salvoOrigin.y);
        tag.putDouble("SalvoZ", this.salvoOrigin.z);
    }
}
