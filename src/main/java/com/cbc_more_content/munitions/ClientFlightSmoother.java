package com.cbc_more_content.munitions;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side flight for a fast, server-steered projectile.
 * <p>
 * The server moves a missile every tick and sends its position; snapping to each packet
 * (what these entities used to do) leaves the airframe still for a whole tick and then
 * jumps it four or five blocks, which reads as stutter at any frame rate above twenty.
 * Instead the client keeps flying the missile with its own estimate of the velocity and
 * eases onto the latest server position, so the rendered path is continuous.
 * <p>
 * Velocity is measured from successive positions rather than taken from the motion
 * packet: vanilla clamps that packet to 3.9 blocks per tick on each axis, below the
 * speed these airframes cruise at. Orientation follows the smoothed velocity in full
 * float precision instead of the byte-quantised yaw and pitch of the movement packets,
 * which stepped a three-block hull visibly through every slow turn.
 */
public final class ClientFlightSmoother {
    /** Fraction of the remaining error corrected each tick. */
    private static final double CORRECTION = 0.45D;
    /** Weight of a fresh velocity measurement against the running estimate. */
    private static final double VELOCITY_GAIN = 0.6D;
    /** Weight of the new heading against the previous one, per tick. */
    private static final double HEADING_GAIN = 0.55D;
    /** Beyond this the client is not behind, it is somewhere else: snap. */
    private static final double SNAP_DISTANCE = 12.0D;

    /** Off only in the client check, to fly a baseline the way the old snapping did. */
    public static boolean enabled = true;

    private Vec3 target;
    private Vec3 previousTarget;
    private int targetTick;
    private int previousTargetTick;
    private boolean fresh;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 heading;

    /** Called from {@code lerpTo}: the server's latest position. */
    public void target(Entity entity, double x, double y, double z) {
        Vec3 next = new Vec3(x, y, z);
        if (!enabled) {
            entity.setPos(next);
            return;
        }
        if (this.target != null) {
            this.previousTarget = this.target;
            this.previousTargetTick = this.targetTick;
        }
        this.target = next;
        this.targetTick = entity.tickCount;
        this.fresh = true;
        if (this.previousTarget != null) {
            int ticks = Math.max(1, this.targetTick - this.previousTargetTick);
            Vec3 measured = next.subtract(this.previousTarget).scale(1.0D / ticks);
            if (measured.lengthSqr() < 400) {
                this.velocity =
                        this.velocity.lengthSqr() < 1.0E-8 ? measured : this.velocity.lerp(measured, VELOCITY_GAIN);
            }
        }
    }

    /** Seeds the estimate from the spawn packet's (possibly clamped) motion. */
    public void seedVelocity(Vec3 motion) {
        if (this.velocity.lengthSqr() < 1.0E-8) {
            this.velocity = motion;
        }
    }

    public Vec3 velocity() {
        return this.velocity;
    }

    /**
     * One client tick: fly on the estimate, pull toward the server position, and face
     * along the flight path. Old position and rotation have already been stored by the
     * level, so the renderer interpolates smoothly across the move made here.
     */
    public void tick(Entity entity) {
        if (!enabled) {
            return;
        }
        Vec3 position = entity.position();
        if (this.target == null) {
            this.target = position;
            this.targetTick = entity.tickCount;
        }
        if (!this.fresh) {
            // No packet this tick: carry the server position forward on the estimate.
            this.target = this.target.add(this.velocity);
        }
        this.fresh = false;
        Vec3 predicted = position.add(this.velocity);
        Vec3 next = predicted.distanceToSqr(this.target) > SNAP_DISTANCE * SNAP_DISTANCE
                ? this.target
                : predicted.lerp(this.target, CORRECTION);
        entity.setPos(next);
        entity.setDeltaMovement(this.velocity);

        if (this.velocity.lengthSqr() > 1.0E-4) {
            Vec3 direction = this.velocity.normalize();
            this.heading = this.heading == null
                    ? direction
                    : this.heading.lerp(direction, HEADING_GAIN).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(this.heading.z, this.heading.x)) - 90.0f;
            float pitch = (float) -Math.toDegrees(Math.asin(Mth.clamp(this.heading.y, -1, 1)));
            // Keep yaw continuous so interpolation never swings the long way round.
            entity.setYRot(entity.yRotO + Mth.wrapDegrees(yaw - entity.yRotO));
            entity.setXRot(pitch);
        }
    }
}
