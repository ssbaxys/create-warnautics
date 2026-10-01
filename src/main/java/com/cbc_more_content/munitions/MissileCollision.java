package com.cbc_more_content.munitions;

import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** The search box only finds candidates. A fuse still requires a swept physical contact. */
public final class MissileCollision {
    private MissileCollision() {}

    public static Vec3 heading(Entity entity) {
        double yaw = (entity.getYRot() + 90) * Mth.DEG_TO_RAD;
        double pitch = -entity.getXRot() * Mth.DEG_TO_RAD;
        return new Vec3(Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
    }

    public static boolean isMissile(Entity entity) {
        return entity instanceof CruiseMissileProjectile || entity instanceof Aim9Projectile;
    }

    @Nullable
    public static Vec3 contact(Entity missile, Entity other, Vec3 from, Vec3 to) {
        if (!isMissile(other)) {
            var box = other.getBoundingBox().inflate(.18);
            return box.contains(from) ? from : box.clip(from, to).orElse(null);
        }
        // Use slender oriented airframes, not their axis-aligned entity tracking boxes.
        // Transform to the other round's translating frame so fast crossing shots cannot tunnel.
        Vec3 relative = to.subtract(from).subtract(other.getDeltaMovement());
        Vec3 a = heading(missile).scale(1.35);
        Vec3 b = heading(other).scale(1.35);
        double radius = (missile instanceof Aim9Projectile ? .11 : .18) + (other instanceof Aim9Projectile ? .11 : .18);
        int samples = Math.clamp((int) Math.ceil(relative.length() / .12), 1, 128);
        for (int step = 0; step <= samples; step++) {
            double t = (double) step / samples;
            Vec3 center = from.add(relative.scale(t));
            if (segmentDistanceSqr(
                            center.subtract(a),
                            center.add(a),
                            other.position().subtract(b),
                            other.position().add(b))
                    <= radius * radius) {
                return from.lerp(to, t);
            }
        }
        return null;
    }

    private static double segmentDistanceSqr(Vec3 p, Vec3 q, Vec3 r, Vec3 s) {
        Vec3 u = q.subtract(p), v = s.subtract(r), w = p.subtract(r);
        double a = u.dot(u), b = u.dot(v), c = v.dot(v), d = u.dot(w), e = v.dot(w);
        double denominator = a * c - b * b;
        double first = denominator > 1.0E-8 ? Mth.clamp((b * e - c * d) / denominator, 0, 1) : 0;
        double second = Mth.clamp((b * first + e) / c, 0, 1);
        first = Mth.clamp((b * second - d) / a, 0, 1);
        return p.add(u.scale(first)).distanceToSqr(r.add(v.scale(second)));
    }

    public static Vec3 turn(Vec3 from, Vec3 to, double radians) {
        if (from.lengthSqr() < 1.0E-8) {
            return to.normalize();
        }
        from = from.normalize();
        to = to.normalize();
        double dot = Mth.clamp(from.dot(to), -1, 1);
        double angle = Math.acos(dot);
        if (angle <= radians) {
            return to;
        }
        Vec3 tangent = to.subtract(from.scale(dot));
        if (tangent.lengthSqr() < 1.0E-8) {
            tangent = from.cross(Math.abs(from.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0));
        }
        return from.scale(Math.cos(radians))
                .add(tangent.normalize().scale(Math.sin(radians)))
                .normalize();
    }
}
