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
import net.minecraft.core.particles.ParticleTypes;
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

    /**
     * How sharply the missile may turn, in radians per tick. Deliberately modest: it can
     * fly a route and lean around a hillside, but a hull that surfaces right in front of
     * it is inside the turn circle and cannot be avoided.
     */
    private static final double TURN_RATE = 0.055D;
    /** How far ahead it looks for something to lean around. */
    private static final double LOOKAHEAD = 14.0D;
    /** Inside this range of the aim point it stops steering and commits. */
    private static final double COMMIT_RANGE = 4.0D;

    private int fuel = FUEL_TICKS;
    private boolean detonated;
    private Guidance guidance = Guidance.NONE;
    @Nullable
    private BlockPos target;
    private int lockedSubLevel = -1;

    public CruiseMissileProjectile(EntityType<? extends CruiseMissileProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POWERED, true);
    }

    public boolean isPowered() {
        return this.entityData.get(POWERED);
    }

    /** Copied off the rack's guidance package as the missile is released. */
    public void setGuidance(Guidance guidance, @Nullable BlockPos target, int lockedSubLevel) {
        this.guidance = guidance;
        this.target = target;
        this.lockedSubLevel = lockedSubLevel;
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

        if (this.fuel > 0) {
            this.fuel--;
            if (this.fuel == 0) {
                this.entityData.set(POWERED, false);
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 2.2f, 0.6f);
            }
        }

        Vec3 motion = this.getDeltaMovement();
        if (this.isPowered()) {
            // Powered flight holds speed; the engine cancels drag and weight.
            motion = this.steer(motion.normalize()).scale(CRUISE_SPEED);
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
    }

    /**
     * One tick of guidance. The heading is turned toward the aim point, then nudged away
     * from anything solid straight ahead, and the whole correction is clamped to
     * {@link #TURN_RATE} so the missile flies rather than snaps onto its target.
     */
    private Vec3 steer(Vec3 heading) {
        Vec3 aim = this.aimPoint();
        if (aim == null) {
            return heading;
        }
        Vec3 toTarget = aim.subtract(this.position());
        if (toTarget.lengthSqr() < COMMIT_RANGE * COMMIT_RANGE) {
            // On top of it: hold the line rather than pirouette around the last metre.
            return heading;
        }
        return turnToward(heading, this.avoid(heading, toTarget.normalize()), TURN_RATE);
    }

    /** Where the missile is trying to get to this tick, or null if it was never told. */
    @Nullable
    private Vec3 aimPoint() {
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
                    BombSize.LARGE.blockBlastPower, BombSize.LARGE.entityBlastPower, BombSize.LARGE);
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
                this.level().addParticle(ModParticles.MISSILE_EXHAUST.get(),
                        nozzle.x + ox, nozzle.y + oy, nozzle.z + oz,
                        back.x * 0.10D + ox * 0.4D,
                        back.y * 0.10D + oy * 0.4D,
                        back.z * 0.10D + oz * 0.4D);
            } else {
                this.level().addParticle(ParticleTypes.SMOKE,
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
        if (this.target != null) {
            tag.putInt("TargetX", this.target.getX());
            tag.putInt("TargetY", this.target.getY());
            tag.putInt("TargetZ", this.target.getZ());
        }
    }
}
