package com.cbc_more_content.munitions;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * An unguided 80 mm rocket, S-8 style. It leaves the tube under thrust, accelerating along
 * the line it was fired on, then coasts and drops once the motor burns out. No seeker and
 * no fins that steer: where it goes is decided by how the pod was pointed, the carrier's
 * motion, and a little dispersion. It bursts on whatever it hits first, ground, wall,
 * creature or ship hull, with a small high-explosive warhead.
 */
public class RocketProjectile extends Entity {
    /** Ticks a rocket will hold for the ground ahead to load before giving up on it. */
    private static final int MAX_WAIT_TICKS = 200;

    private int waiting;
    private static final EntityDataAccessor<Boolean> BURNING =
            SynchedEntityData.defineId(RocketProjectile.class, EntityDataSerializers.BOOLEAN);
    /** Warhead, small-bomb class: a few kilograms of explosive. */
    private static final float BLOCK_POWER = 2.4f;

    private static final float ENTITY_POWER = 4.5f;
    /** Speed it leaves the tube at, blocks/tick, before the motor has got going. */
    private static final double LAUNCH_SPEED = 1.6D;
    /** Fraction of the gap to top speed the motor closes each tick. */
    private static final double THRUST = 0.22D;

    private static final double GRAVITY_BURNING = 0.008D;
    private static final double GRAVITY_COASTING = 0.04D;
    private static final double DRAG_COASTING = 0.992D;
    /** Ticks before the fuze is armed: a rocket clipping its own pod does not go off in it. */
    private static final int ARM_TICKS = 2;

    private Vec3 heading = new Vec3(0, 0, 1);
    private int age;
    private final ClientFlightSmoother smoother = new ClientFlightSmoother();

    public RocketProjectile(EntityType<? extends RocketProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BURNING, true);
    }

    /** Fired along {@code direction} from a carrier moving at {@code carrier} blocks per tick. */
    public void launch(Vec3 direction, Vec3 carrier, RandomSource random) {
        Vec3 dir = direction.normalize();
        double spread = WarnauticsConfig.rocketDispersion();
        if (spread > 0) {
            // A random tilt off the axis, up to the configured angle, in a random direction.
            Vec3 any = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 a = dir.cross(any).normalize();
            Vec3 b = dir.cross(a).normalize();
            double tilt = spread * Math.sqrt(random.nextDouble());
            double around = random.nextDouble() * Math.PI * 2;
            dir = dir.scale(Math.cos(tilt))
                    .add(a.scale(Math.sin(tilt) * Math.cos(around)))
                    .add(b.scale(Math.sin(tilt) * Math.sin(around)))
                    .normalize();
        }
        heading = dir;
        setDeltaMovement(dir.scale(LAUNCH_SPEED).add(carrier));
        face(getDeltaMovement());
        yRotO = getYRot();
        xRotO = getXRot();
    }

    public boolean isBurning() {
        return entityData.get(BURNING);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            smoother.seedVelocity(getDeltaMovement());
            smoother.tick(this);
            face(smoother.velocity());
            return;
        }
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        // Keeps flying, and goes off where it lands, with nobody about.
        FlightTicket.keepLoaded(server, this);
        // Ground ahead still loading: hold here, as if the moment had not come yet, rather
        // than vanish; the ticket has already asked for it. Only a very long wait gives up.
        if (!server.isPositionEntityTicking(BlockPos.containing(position().add(getDeltaMovement())))) {
            if (++waiting > MAX_WAIT_TICKS) {
                discard();
            }
            return;
        }
        waiting = 0;
        age++;
        Vec3 motion = getDeltaMovement();
        boolean burning = age <= WarnauticsConfig.rocketBurnTicks();
        if (burning) {
            // Thrust only adds along the rocket's own axis; whatever sideways speed it
            // inherited from the carrier stays with it.
            double along = motion.dot(heading);
            double gain = (WarnauticsConfig.rocketSpeed() - along) * THRUST;
            motion = motion.add(heading.scale(Math.max(0, gain))).add(0, -GRAVITY_BURNING, 0);
        } else {
            motion = motion.scale(DRAG_COASTING).add(0, -GRAVITY_COASTING, 0);
            if (motion.lengthSqr() > 1.0E-6) {
                heading = motion.normalize();
            }
        }
        if (entityData.get(BURNING) != burning) {
            entityData.set(BURNING, burning);
        }

        Vec3 from = position();
        Vec3 to = from.add(motion);
        if (age > WarnauticsConfig.rocketLifetimeTicks()) {
            discard();
            return;
        }
        Vec3 hit = firstHit(server, from, to, motion);
        if (hit != null) {
            if (age >= ARM_TICKS) {
                detonate(server, hit.subtract(motion.normalize().scale(0.25D)));
            } else {
                discard();
            }
            return;
        }
        setDeltaMovement(motion);
        setPos(to);
        face(motion);
    }

    /** The nearest of: a block, a ship's hull, or a creature, along this tick's path. */
    private Vec3 firstHit(ServerLevel server, Vec3 from, Vec3 to, Vec3 motion) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        var block = server.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (block.getType() != HitResult.Type.MISS) {
            best = block.getLocation();
            bestDistance = best.distanceToSqr(from);
        }
        // Ships only get traced when one is actually near this tick's path.
        Vec3 hull = SableDropCompat.overlapsAnySubLevel(server, from, motion.length() + 4.0D)
                ? SableDropCompat.clipSubLevels(server, from, to)
                : null;
        if (hull != null && hull.distanceToSqr(from) < bestDistance) {
            best = hull;
            bestDistance = hull.distanceToSqr(from);
        }
        var entity = ProjectileUtil.getEntityHitResult(
                server,
                this,
                from,
                to,
                getBoundingBox().expandTowards(motion).inflate(0.4D),
                e -> e.isAlive() && e.isPickable() && !e.isSpectator() && !(e instanceof RocketProjectile));
        if (entity != null && entity.getLocation().distanceToSqr(from) < bestDistance) {
            best = entity.getLocation();
        }
        return best;
    }

    private void detonate(ServerLevel server, Vec3 at) {
        discard();
        BombExplosionHandler.detonate(
                server, this, BombDamageSource.create(server), at, BLOCK_POWER, ENTITY_POWER, BombSize.SMALL);
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

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 384 * 384;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        smoother.target(this, x, y, z);
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        smoother.seedVelocity(new Vec3(x, y, z));
        setDeltaMovement(x, y, z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        age = tag.getInt("Age");
        heading = new Vec3(tag.getDouble("HeadingX"), tag.getDouble("HeadingY"), tag.getDouble("HeadingZ"));
        if (heading.lengthSqr() < 1.0E-6) {
            heading = new Vec3(0, 0, 1);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", age);
        tag.putDouble("HeadingX", heading.x);
        tag.putDouble("HeadingY", heading.y);
        tag.putDouble("HeadingZ", heading.z);
    }
}
