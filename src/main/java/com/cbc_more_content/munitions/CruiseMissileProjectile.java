package com.cbc_more_content.munitions;

import javax.annotation.Nullable;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.registry.ModParticles;
import com.cbc_more_content.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.minecraft.world.phys.AABB;

/**
 * A cruise missile in flight.
 * <p>
 * Two phases. Under power it holds its heading and altitude at a constant cruise speed,
 * because that is what makes it useful as a delivery weapon rather than a lobbed shell.
 * When the fuel runs out the engine cuts, drag takes over and it noses down under
 * gravity, so a missile that overshoots comes down somewhere rather than flying forever.
 */
public class CruiseMissileProjectile extends Entity {
    /**
     * Powered flight, in ticks. At the cruise speed below this is roughly 130 blocks of
     * range — far enough to be a standoff weapon, short enough that the missile comes
     * down while the launch site is still on screen.
     */
    public static final int FUEL_TICKS = 115;
    private static final double CRUISE_SPEED = 1.15D;
    /** Unpowered descent. Steeper than a shell so burnout reads as the engine dying. */
    private static final double GRAVITY = 0.085D;
    /** Horizontal speed bleeds off quickly once there is no thrust holding it up. */
    private static final double DRAG = 0.95D;
    /** Ignore the launcher for a moment so it cannot detonate on its own rack. */
    private static final int ARMING_TICKS = 4;

    private static final EntityDataAccessor<Boolean> POWERED =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BOOLEAN);
    /** Ejected from the silo, engine not yet lit. */
    private static final EntityDataAccessor<Boolean> EJECTING =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BOOLEAN);

    /** Cold launch: thrown clear of the rack, then lit in the air. */
    public static final int EJECT_TICKS = 14;
    /** How hard a vertical rack throws the airframe before ignition. */
    public static final double EJECT_SPEED = 0.62D;
    /** Weight during the coast; the missile is meant to slow, not stop. */
    private static final double EJECT_GRAVITY = 0.03D;

    /**
     * How sharply the missile may turn, in radians per tick. Deliberately modest: it can
     * fly a route and lean around a hillside, but a hull that surfaces right in front of
     * it is inside the turn circle and cannot be avoided.
     */
    private static final double TURN_RATE = 0.055D;
    /** How far ahead it looks for something to lean around. */
    private static final double LOOKAHEAD = 14.0D;
    /**
     * Inside this range the missile is on its terminal run: obstacle avoidance is
     * dropped and it turns much harder. Avoidance is what used to make it miss — the
     * ground under a target reads as an obstruction, so the missile climbed over the
     * very thing it was aimed at and flew on.
     */
    private static final double TERMINAL_RANGE = 32.0D;
    /** Terminal turn rate, enough to pull a dive onto a point it is nearly on top of. */
    private static final double TERMINAL_TURN_RATE = 0.2D;
    /** Close enough to burst. The warhead is far wider than this. */
    private static final double FUSE_RANGE = 2.0D;
    /** Terminal run-in: the last stretch is flown faster than the cruise. */
    private static final double TERMINAL_BOOST = 1.45D;
    /** Cosine of the sharpest course change a target can make without being noticed. */
    private static final double JINK_DOT = 0.55D;
    /** Odds that a hard break at close range actually throws the missile off. */
    private static final float JINK_CHANCE = 0.4f;
    /** How long a thrown-off missile sails past before it can pull round again. */
    private static final int SHAKEN_TICKS = 26;

    /**
     * Warhead, relative to the heaviest bomb. The crater keeps the large-bomb shape,
     * which is the interesting one, but a missile is a delivery vehicle for something
     * bigger than anything a rack carries.
     */
    private static final float BLOCK_POWER = BombSize.LARGE.blockBlastPower * 1.3f;
    private static final float ENTITY_POWER = BombSize.LARGE.entityBlastPower * 1.6f;
    /** Scorched, churned ground well past the hole itself. */
    private static final double SCUFF_RADIUS = BLOCK_POWER * 2.1D;

    private int fuel = FUEL_TICKS;
    private boolean detonated;
    /** Nearest the missile has come to its aim point on this terminal run. */
    private double closestApproach = Double.MAX_VALUE;
    /** True once the range has actually started falling, so the fuse can arm. */
    private boolean closing;
    private double lastRange = Double.MAX_VALUE;
    /** Ticks left of being thrown off by a target that broke hard. */
    private int shaken;
    @Nullable
    private Vec3 lastAim;
    @Nullable
    private Vec3 lastAimDrift;
    private int ejecting;
    private Guidance guidance = Guidance.NONE;
    @Nullable
    private BlockPos target;
    private int lockedSubLevel = -1;
    @Nullable
    private BlockPos controller;
    /** The track being chased, so the seeker does not swap targets every tick. */
    @Nullable
    private String contact;

    public CruiseMissileProjectile(EntityType<? extends CruiseMissileProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POWERED, true);
        builder.define(EJECTING, false);
    }

    public boolean isPowered() {
        return this.entityData.get(POWERED);
    }

    /** The radar set the missile listens to while intercepting. */
    public void setController(@Nullable BlockPos controller) {
        this.controller = controller;
    }

    /** Copied off the rack's guidance package as the missile is released. */
    public void setGuidance(Guidance guidance, @Nullable BlockPos target, int lockedSubLevel) {
        this.guidance = guidance;
        this.target = target;
        this.lockedSubLevel = lockedSubLevel;
    }

    /** True while the missile is coasting up out of a rack with its engine cold. */
    public boolean isEjecting() {
        return this.entityData.get(EJECTING);
    }

    /**
     * Cold launch out of a vertical rack: thrown clear on gas alone, engine dark,
     * and lit only once it is well above whatever it was standing in.
     */
    public void ejectUpward() {
        this.ejecting = EJECT_TICKS;
        this.entityData.set(EJECTING, true);
        this.entityData.set(POWERED, false);
        this.setDeltaMovement(0.0D, EJECT_SPEED, 0.0D);
        this.setYRot(0.0f);
        this.setXRot(-90.0f);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    /** Sets the launch heading and starts the engine. */
    public void launch(Vec3 heading) {
        Vec3 dir = heading.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : heading.normalize();
        this.setDeltaMovement(dir.scale(CRUISE_SPEED));
        this.setYRot((float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0f);
        this.setXRot((float) (-Math.asin(dir.y) * 180.0D / Math.PI));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.spawnExhaust();
            return;
        }
        if (this.detonated) {
            return;
        }

        if (this.ejecting > 0) {
            this.coastOutOfRack();
            return;
        }

        if (this.fuel > 0) {
            this.fuel--;
            if (this.fuel == 0) {
                this.entityData.set(POWERED, false);
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 2.2f, 0.6f);
            }
        }

        // Resolved once: a locked hull is looked up through Sable, and both the steering
        // and the fuse want the same answer within a tick.
        Vec3 aim = this.aimPoint();
        this.watchForJink(aim);

        Vec3 motion = this.getDeltaMovement();
        if (this.isPowered()) {
            // Powered flight holds speed; the engine cancels drag and weight.
            motion = this.steer(motion.normalize(), aim).scale(this.speedFor(aim));
            if (this.tickCount % 4 == 0) {
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        ModSounds.CRUISE_MISSILE_ENGINE.get(), SoundSource.HOSTILE, 3.0f,
                        0.9f + this.random.nextFloat() * 0.1f);
            }
        } else {
            motion = motion.scale(DRAG).subtract(0.0D, GRAVITY, 0.0D);
        }
        this.setDeltaMovement(motion);

        Vec3 from = this.position();
        Vec3 to = from.add(motion);
        if (this.tickCount > ARMING_TICKS && this.checkImpact(from, to)) {
            return;
        }

        this.setPos(to);
        this.faceMotion(motion);
        this.fuseOnTarget(aim);
    }

    /**
     * The coast out of the rack. No thrust, barely any weight, and a great deal of
     * gas, then the motor lights and the missile takes over from the launcher.
     */
    private void coastOutOfRack() {
        this.ejecting--;
        Vec3 motion = this.getDeltaMovement().subtract(0.0D, EJECT_GRAVITY, 0.0D);
        this.setDeltaMovement(motion);
        this.setPos(this.position().add(motion));
        this.faceMotion(motion);

        if (this.ejecting > 0) {
            return;
        }

        // Ignition. The report is what sells a two-stage launch, so it is loud and it
        // happens exactly once. The flash itself is drawn client-side, off the same
        // EJECTING flag flipping here — see MissileExhaustLights, which fires a Veil
        // light burst and the mod's own particles rather than a vanilla flash and puff.
        this.entityData.set(EJECTING, false);
        this.entityData.set(POWERED, true);
        Vec3 heading = motion.lengthSqr() < 1.0E-4D
                ? new Vec3(0.0D, 1.0D, 0.0D)
                : motion.normalize();
        this.setDeltaMovement(heading.scale(CRUISE_SPEED));
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                ModSounds.CRUISE_MISSILE_LAUNCH.get(), SoundSource.HOSTILE, 5.0f, 0.72f);
    }

    /** Cruise speed, or the terminal run-in, which is flown harder. */
    private double speedFor(@Nullable Vec3 aim) {
        if (aim == null) {
            return CRUISE_SPEED;
        }
        return this.position().distanceToSqr(aim) <= TERMINAL_RANGE * TERMINAL_RANGE
                ? CRUISE_SPEED * TERMINAL_BOOST
                : CRUISE_SPEED;
    }

    /**
     * Watches the aim point for a hard break.
     * <p>
     * A hull that turns sharply while the missile is already committed can throw it:
     * the seeker holds the old solution for a moment, the missile sails past, and only
     * then pulls round for another run. It does not always work, which is the point of
     * the roll, and it costs fuel either way.
     */
    private void watchForJink(@Nullable Vec3 aim) {
        if (this.shaken > 0 && --this.shaken == 0) {
            // Coming back round: the fuse has to watch the range fall again first.
            this.resetApproach();
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
                this.resetApproach();
            }
            this.lastAimDrift = drift;
        }
        this.lastAim = aim;
    }

    private void resetApproach() {
        this.closestApproach = Double.MAX_VALUE;
        this.lastRange = Double.MAX_VALUE;
        this.closing = false;
    }

    /**
     * One tick of guidance. Beyond the terminal range the heading is turned gently
     * toward the aim point and leaned around anything solid ahead; inside it, the
     * missile stops avoiding and pulls onto the target as hard as it can.
     */
    private Vec3 steer(Vec3 heading, @Nullable Vec3 aim) {
        if (aim == null) {
            return heading;
        }
        Vec3 toTarget = aim.subtract(this.position());
        double distance = toTarget.length();
        if (distance < 1.0E-4D) {
            return heading;
        }
        Vec3 wanted = toTarget.scale(1.0D / distance);
        if (distance > TERMINAL_RANGE) {
            return turnToward(heading, this.avoid(heading, wanted), TURN_RATE);
        }
        // Thrown off: it still wants the target, it simply cannot pull the corner in
        // time, so it goes wide and comes back round.
        return turnToward(heading, wanted, this.shaken > 0 ? TURN_RATE : TERMINAL_TURN_RATE);
    }

    /**
     * Proximity fuse. A guided missile that sails past its aim point and carries on is
     * no use, so once inside the terminal envelope it bursts at the nearest point it
     * actually manages to reach — either close enough outright, or the moment the range
     * starts opening again, which is the tick after closest approach.
     */
    private void fuseOnTarget(@Nullable Vec3 aim) {
        if (this.detonated || aim == null || this.tickCount <= ARMING_TICKS) {
            return;
        }
        double distance = this.position().distanceTo(aim);
        if (distance > TERMINAL_RANGE) {
            this.resetApproach();
            return;
        }
        if (distance < this.lastRange - 0.01D) {
            this.closing = true;
        }
        this.lastRange = distance;
        if (this.shaken > 0) {
            // Sailing past on a spoiled solution; it is not bursting out here.
            return;
        }
        if (distance <= FUSE_RANGE
                || (this.closing && distance > this.closestApproach + 0.05D)) {
            this.detonate(this.position());
            return;
        }
        this.closestApproach = Math.min(this.closestApproach, distance);
    }

    /** Where the missile is trying to get to this tick, or null if it was never told. */
    @Nullable
    private Vec3 aimPoint() {
        if (this.guidance == Guidance.INTERCEPT) {
            return this.radarAim();
        }
        if (this.guidance == Guidance.LOCK && this.lockedSubLevel >= 0
                && ModList.get().isLoaded("sable")
                && this.level() instanceof ServerLevel server) {
            Vec3 tracked = SableDropCompat.subLevelCentre(server, this.lockedSubLevel);
            if (tracked != null) {
                return tracked;
            }
            // Lost the hull mid-flight; carry on to where it last was.
        }
        return this.target == null || this.guidance == Guidance.NONE
                ? null
                : Vec3.atCenterOf(this.target);
    }

    /**
     * Where the bound radar set says to go.
     * <p>
     * A track is held by id once taken, so the missile commits to one contact rather
     * than jumping between whatever happens to be nearest that tick; it only reacquires
     * when its own track drops off the picture.
     */
    @Nullable
    private Vec3 radarAim() {
        if (this.controller == null || !com.cbc_more_content.compat.RadarCompat.loaded()) {
            return null;
        }
        if (this.contact != null) {
            var held = com.cbc_more_content.compat.RadarCompat.contactById(
                    this.level(), this.controller, this.contact);
            if (held != null) {
                return held.position();
            }
            this.contact = null;
        }
        // Conditions come off the network rather than off the missile: retuning the
        // controller has to change what the next launch will chase.
        var settings = this.level() instanceof ServerLevel server
                ? com.cbc_more_content.radar.InterceptSettingsStore.get(server)
                        .forController(this.controller)
                : com.cbc_more_content.radar.InterceptSettings.DEFAULT;
        var fresh = com.cbc_more_content.compat.RadarCompat.bestContact(
                this.level(), this.controller, this.position(), settings);
        if (fresh == null) {
            return null;
        }
        this.contact = fresh.id();
        return fresh.position();
    }

    /**
     * Leans the wanted heading away from terrain dead ahead. Only ever a nudge: enough to
     * skim a ridge on the way to a target, nowhere near enough to dodge.
     */
    private Vec3 avoid(Vec3 heading, Vec3 wanted) {
        Vec3 from = this.position();
        BlockHitResult hit = this.level().clip(new ClipContext(
                from, from.add(heading.scale(LOOKAHEAD)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return wanted;
        }

        // Climb over rather than around: the ground is the usual obstruction, and a
        // missile that sidesteps a hill just flies into its shoulder instead.
        double closeness = 1.0D - Math.sqrt(hit.getLocation().distanceToSqr(from)) / LOOKAHEAD;
        Vec3 lift = new Vec3(hit.getDirection().getStepX(),
                Math.max(0.35D, hit.getDirection().getStepY()),
                hit.getDirection().getStepZ());
        return wanted.add(lift.scale(Mth.clamp(closeness, 0.0D, 1.0D) * 1.4D)).normalize();
    }

    /** Rotates {@code from} toward {@code to} by at most {@code maxRadians}. */
    private static Vec3 turnToward(Vec3 from, Vec3 to, double maxRadians) {
        double angle = Math.acos(Mth.clamp(from.dot(to), -1.0D, 1.0D));
        if (angle <= maxRadians || angle < 1.0E-4D) {
            return to;
        }
        double t = maxRadians / angle;
        double sin = Math.sin(angle);
        return from.scale(Math.sin((1.0D - t) * angle) / sin)
                .add(to.scale(Math.sin(t * angle) / sin))
                .normalize();
    }

    /** Detonates on the first block or entity in the path this tick. */
    private boolean checkImpact(Vec3 from, Vec3 to) {
        BlockHitResult block = this.level().clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 stop = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;

        // A physics hull is not made of blocks that stand where the hull looks like it
        // stands, so the sweep above passes clean through one. Sable is asked separately,
        // in the hull's own space, and answers in world space.
        if (ModList.get().isLoaded("sable") && this.level() instanceof ServerLevel server) {
            Vec3 hull = SableDropCompat.clipSubLevels(server, from, to);
            if (hull != null && (stop == null || from.distanceToSqr(hull) < from.distanceToSqr(stop))) {
                stop = hull;
            }
        }

        AABB sweep = this.getBoundingBox().expandTowards(to.subtract(from)).inflate(0.5D);
        for (Entity entity : this.level().getEntities(this, sweep, this::canHit)) {
            EntityHitResult hit = new EntityHitResult(entity);
            Vec3 at = hit.getLocation();
            if (stop == null || from.distanceToSqr(at) < from.distanceToSqr(stop)) {
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

    /**
     * The missile always leaves the world, even if the blast itself fails: an escaping
     * exception would be rethrown every tick while it is still alive and colliding.
     */
    private void detonate(Vec3 at) {
        if (this.detonated || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.detonated = true;
        try {
            BombExplosionHandler.detonate(
                    server, this, BombDamageSource.create(server), at,
                    BLOCK_POWER, ENTITY_POWER, BombSize.LARGE);
            // The crater alone reads as a big hole in a field. Tearing up the ground
            // around it is what makes it read as a strike.
            com.cbc_more_content.effects.BlastScorch.scuff(server, at, SCUFF_RADIUS, 1.0f);
        } catch (Throwable t) {
            com.cbc_more_content.CBCMoreContent.LOGGER.error(
                    "Cruise missile detonation failed at {}", at, t);
        } finally {
            this.discard();
        }
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

    /**
     * Exhaust plume, anchored at the nozzle. The direction comes from the synced
     * rotation rather than {@code getDeltaMovement}, which is only replicated on its
     * own schedule and reads as zero in between.
     */
    private void spawnExhaust() {
        float yaw = (this.getYRot() + 90.0f) * Mth.DEG_TO_RAD;
        float pitch = -this.getXRot() * Mth.DEG_TO_RAD;
        double cos = Mth.cos(pitch);
        Vec3 heading = new Vec3(Mth.cos(yaw) * cos, Mth.sin(pitch), Mth.sin(yaw) * cos);
        if (heading.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 back = heading.normalize().scale(-1.6D);
        Vec3 nozzle = this.position().add(back);

        if (this.isEjecting()) {
            // The gas charge, not the motor: our own cold-gas particle dumped out fast
            // and left behind, so the ignition that follows reads as a separate event.
            // Deliberately not the exhaust particle — that one runs white-hot, and this
            // is the one moment in the flight where nothing has actually ignited yet.
            for (int i = 0; i < 10; i++) {
                double ox = (this.random.nextDouble() - 0.5D) * 0.9D;
                double oy = (this.random.nextDouble() - 0.5D) * 0.5D;
                double oz = (this.random.nextDouble() - 0.5D) * 0.9D;
                this.level().addParticle(ModParticles.MISSILE_GAS.get(), true,
                        nozzle.x + ox, nozzle.y + oy, nozzle.z + oz,
                        ox * 0.35D, -0.12D + oy * 0.2D, oz * 0.35D);
            }
            return;
        }

        boolean powered = this.isPowered();

        int puffs = powered ? 5 : 1;
        for (int i = 0; i < puffs; i++) {
            double jitter = 0.12D;
            double ox = (this.random.nextDouble() - 0.5D) * jitter;
            double oy = (this.random.nextDouble() - 0.5D) * jitter;
            double oz = (this.random.nextDouble() - 0.5D) * jitter;
            if (powered) {
                // Our own plume rather than vanilla flame and cloud: it is drawn
                // full-bright and cools through its own colour ramp, so the trail keeps
                // reading as efflux at night and under shader packs alike.
                this.level().addParticle(ModParticles.MISSILE_EXHAUST.get(), true,
                        nozzle.x + ox, nozzle.y + oy, nozzle.z + oz,
                        back.x * 0.10D + ox * 0.4D,
                        back.y * 0.10D + oy * 0.4D,
                        back.z * 0.10D + oz * 0.4D);
            } else {
                // Burnout: the same cold-gas particle as the ejection charge, since a
                // dead motor trailing off is the same kind of nothing-left-to-burn cloud.
                this.level().addParticle(ModParticles.MISSILE_GAS.get(), true,
                        nozzle.x + ox * 2.0D, nozzle.y + oy * 2.0D, nozzle.z + oz * 2.0D,
                        back.x * 0.02D, 0.01D, back.z * 0.02D);
            }
        }
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!this.level().isClientSide && !this.detonated) {
            this.detonate(this.position());
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !this.detonated;
    }

    /**
     * Snap to the server position instead of easing toward it. At better than a block
     * per tick vanilla smoothing leaves the rendered missile well behind the server,
     * so the blast reads as arriving late and displaced.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        this.setPos(x, y, z);
        this.setRot(yaw, pitch);
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        this.setDeltaMovement(x, y, z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.fuel = tag.getInt("Fuel");
        this.entityData.set(POWERED, tag.getBoolean("Powered"));
        this.guidance = Guidance.byId(tag.getInt("Guidance"));
        this.lockedSubLevel = tag.contains("Lock") ? tag.getInt("Lock") : -1;
        this.controller = tag.contains("RadarX")
                ? new BlockPos(tag.getInt("RadarX"), tag.getInt("RadarY"), tag.getInt("RadarZ"))
                : null;
        this.target = tag.contains("TargetX")
                ? new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ"))
                : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Fuel", this.fuel);
        tag.putBoolean("Powered", this.isPowered());
        tag.putInt("Guidance", this.guidance.ordinal());
        tag.putInt("Lock", this.lockedSubLevel);
        if (this.controller != null) {
            tag.putInt("RadarX", this.controller.getX());
            tag.putInt("RadarY", this.controller.getY());
            tag.putInt("RadarZ", this.controller.getZ());
        }
        if (this.target != null) {
            tag.putInt("TargetX", this.target.getX());
            tag.putInt("TargetY", this.target.getY());
            tag.putInt("TargetZ", this.target.getZ());
        }
    }
}
