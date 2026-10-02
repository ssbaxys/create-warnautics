package com.cbc_more_content.munitions;

import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Stable per-flight errors; another tick or a save/load never rerolls a committed approach. */
public final class MissileGuidanceError {
    public static final double CRUISE_MISS_CHANCE = .12;

    private MissileGuidanceError() {}

    public static Vec3 cruiseOffset(UUID id, double normalRadius) {
        long seed = id.getLeastSignificantBits();
        double angle = ((seed >>> 16) & 0xFFFF) / 65536.0 * Math.PI * 2;
        double distance = normalRadius * Math.sqrt((seed & 0xFFFF) / 65536.0);
        if (sample(id, 0x435255495345L) < CRUISE_MISS_CHANCE) {
            distance = 6 + 4 * sample(id, 0x44495354414EL);
        }
        return new Vec3(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }

    public static double interceptMissChance(Vec3 offset, Vec3 incoming, Vec3 targetVelocity, Vec3 acceleration) {
        Vec3 sight = offset.normalize();
        double crossing =
                targetVelocity.subtract(sight.scale(targetVelocity.dot(sight))).length();
        double closure = incoming.subtract(targetVelocity).dot(sight);
        double alignment = Mth.clamp(incoming.normalize().dot(sight), -1, 1);
        return Mth.clamp(
                .08
                        + .18 * Mth.clamp(crossing / 4.7, 0, 1)
                        + .15 * Mth.clamp(acceleration.length() / .7, 0, 1)
                        + .12 * Mth.clamp((1 - alignment) / .8, 0, 1)
                        + .10 * Mth.clamp((closure - 7) / 5, 0, 1),
                .08,
                .50);
    }

    public static boolean missesIntercept(UUID id, int approach, double chance) {
        return sample(id, 0x41494D394D495353L + approach * 0x9E3779B97F4A7C15L) < chance;
    }

    public static Vec3 interceptOffset(UUID id, int approach, Vec3 aimDirection) {
        Vec3 side = aimDirection.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < .01) {
            side = aimDirection.cross(new Vec3(1, 0, 0));
        }
        double roll = sample(id, 0x504153534552524FL + approach);
        double distance = 6 + 3 * sample(id, 0x5041535353495A45L + approach);
        return side.normalize().scale((roll < .5 ? -1 : 1) * distance).add(0, .5, 0);
    }

    private static double sample(UUID id, long salt) {
        long value = id.getLeastSignificantBits() ^ Long.rotateLeft(id.getMostSignificantBits(), 23) ^ salt;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
}
