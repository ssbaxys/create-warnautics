package com.cbc_more_content.munitions;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a heading toward a commanded direction the way an airframe does: the turn rate
 * builds up and dies away instead of jumping, and never exceeds what the airframe can
 * pull at its current speed.
 * <p>
 * Steering used to rotate the nose straight onto its target each tick by up to a fixed
 * angle. That is a bang-bang controller: it kinks the path at every change of command
 * and chatters around the goal. Here the commanded rate is proportional to the error
 * and the actual rate follows it with a short lag, which draws continuous arcs and
 * settles onto a heading without overshoot.
 */
public final class Autopilot {
    /** Fraction of the remaining heading error commanded as rate per tick. */
    private static final double GAIN = 0.32D;

    /** Rotation vector: axis times radians per tick. */
    private Vec3 rate = Vec3.ZERO;

    /**
     * @param maxRate  turn-rate limit this tick, radians
     * @param response how quickly the actual rate follows the command, 0..1
     */
    public Vec3 steer(Vec3 heading, Vec3 desired, double maxRate, double response) {
        return this.steer(heading, desired, maxRate, response, GAIN);
    }

    /** As above, with a stiffer or softer proportional gain for this phase of flight. */
    public Vec3 steer(Vec3 heading, Vec3 desired, double maxRate, double response, double gain) {
        Vec3 from = heading.normalize();
        Vec3 to = desired.lengthSqr() < 1.0E-10 ? from : desired.normalize();
        Vec3 axis = from.cross(to);
        double sin = axis.length();
        double cos = from.dot(to);
        double angle = Math.atan2(sin, cos);
        Vec3 wanted;
        if (sin < 1.0E-6) {
            if (cos > 0) {
                wanted = Vec3.ZERO;
            } else {
                // Straight behind: pick any perpendicular, preferring a level turn.
                Vec3 side = from.cross(new Vec3(0, 1, 0));
                if (side.lengthSqr() < 1.0E-6) {
                    side = from.cross(new Vec3(1, 0, 0));
                }
                wanted = side.normalize().scale(maxRate);
            }
        } else {
            wanted = axis.scale(1.0D / sin).scale(Math.min(angle * gain, maxRate));
        }
        this.rate = this.rate.lerp(wanted, response);
        double magnitude = this.rate.length();
        if (magnitude > maxRate && magnitude > 0) {
            this.rate = this.rate.scale(maxRate / magnitude);
            magnitude = maxRate;
        }
        // Never rotate past the goal, even with rate still built up from a bigger error.
        if (magnitude > angle && sin >= 1.0E-6) {
            this.rate = this.rate.scale(angle / magnitude);
        }
        return rotate(from, this.rate);
    }

    /** Current turn rate, radians per tick. */
    public double turnRate() {
        return this.rate.length();
    }

    public void reset() {
        this.rate = Vec3.ZERO;
    }

    /** Rodrigues rotation of {@code v} by the rotation vector {@code r}. */
    static Vec3 rotate(Vec3 v, Vec3 r) {
        double angle = r.length();
        if (angle < 1.0E-9) {
            return v;
        }
        Vec3 k = r.scale(1.0D / angle);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return v.scale(cos)
                .add(k.cross(v).scale(sin))
                .add(k.scale(k.dot(v) * (1 - cos)))
                .normalize();
    }

    public void save(CompoundTag tag, String key) {
        tag.putDouble(key + "X", this.rate.x);
        tag.putDouble(key + "Y", this.rate.y);
        tag.putDouble(key + "Z", this.rate.z);
    }

    public void load(CompoundTag tag, String key) {
        this.rate = new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z"));
    }
}
