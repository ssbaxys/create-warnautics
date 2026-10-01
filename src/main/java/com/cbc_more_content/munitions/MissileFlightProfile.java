package com.cbc_more_content.munitions;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Three readable, bounded game flight plans. Guidance still owns the final target and fuse. */
public enum MissileFlightProfile {
    DIRECT(0, 1.0),
    ARC(1, 1.15),
    EVASIVE(2, 1.4);

    private final int id;
    private final double fuelPerTick;

    MissileFlightProfile(int id, double fuelPerTick) {
        this.id = id;
        this.fuelPerTick = fuelPerTick;
    }

    public int id() {
        return this.id;
    }

    public static MissileFlightProfile byId(int id) {
        for (MissileFlightProfile profile : values()) {
            if (profile.id == id) {
                return profile;
            }
        }
        return DIRECT;
    }

    public double speed(int poweredTicks, double range, long seed) {
        double spool = Mth.clamp(poweredTicks / 38.0, 0, 1);
        spool = spool * spool * (3 - 2 * spool);
        double terminal = Mth.clamp((48 - range) / 48, 0, 1);
        return switch (this) {
            case DIRECT -> (1.15 + 3.05 * spool) * (1 + .07 * terminal);
            case ARC -> (1.05 + 2.85 * spool) * (1 + .10 * terminal);
            case EVASIVE -> {
                double phase = (seed & 0xFF) * .024;
                double pulse =
                        Math.sin(poweredTicks * .115 + phase) * .34 + Math.sin(poweredTicks * .041 + phase * 1.7) * .20;
                yield Mth.clamp(1.15 + 3.0 * spool + pulse * spool + .18 * terminal, 1.0, 4.7);
            }
        };
    }

    public double fuelPerTick(double speed) {
        return this.fuelPerTick + Math.max(0, speed - 4.0) * .18;
    }

    public Vec3 shapedAim(Vec3 position, Vec3 target, int poweredTicks, long seed) {
        double range = position.distanceTo(target);
        if (range <= 18) {
            return target;
        }
        if (this == ARC) {
            // Ease the loft away before terminal guidance; dropping a fixed offset at
            // one distance made the nose snap down instead of drawing a continuous arc.
            double approach = Mth.clamp((range - 18) / 42, 0, 1);
            approach = approach * approach * (3 - 2 * approach);
            double height = Math.min(52, range * 0.28) * approach;
            return target.add(0, height, 0);
        }
        if (this == EVASIVE) {
            Vec3 toward = target.subtract(position);
            Vec3 flat = new Vec3(toward.x, 0, toward.z);
            if (flat.lengthSqr() < 1.0E-4) {
                return target;
            }
            Vec3 side = new Vec3(-flat.z, 0, flat.x).normalize();
            double phase = (seed & 0xFFFF) * 0.0003;
            double envelope = Mth.clamp((range - 28) / 45, 0, 1);
            double sway =
                    7.5 * Math.sin(poweredTicks * 0.09 + phase) + 2.5 * Math.sin(poweredTicks * 0.21 + phase * 2.3);
            // Aim at a nearby moving waypoint. Offsetting the distant target by a few
            // blocks produced almost no lateral turn until the final seconds of flight.
            return position.add(toward.normalize().scale(Math.min(30, range)))
                    .add(side.scale(sway * envelope))
                    .add(0, 4.0 * Math.sin(poweredTicks * 0.075 + phase) * envelope, 0);
        }
        return target;
    }
}
