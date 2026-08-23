package com.cbc_more_content.effects;

/**
 * Fracture cost model shared by the Warnautics block-damage passes.
 * <p>
 * Vanilla charges explosion resistance linearly, which puts obsidian (1200) two hundred
 * times above stone (6) — a scale built so 4-power TNT can never touch it. Charging a
 * sub-linear curve instead leaves ordinary terrain unchanged while letting payload size
 * matter: obsidian comes within reach of the heaviest charges at close range only.
 */
public final class BlastFracture {
    private static final double RESISTANCE_EXPONENT = 0.58D;

    private BlastFracture() {
    }

    /** Energy a block absorbs before it breaks. */
    public static double cost(double explosionResistance) {
        return (Math.pow(Math.max(0.0D, explosionResistance), RESISTANCE_EXPONENT) + 0.3D) * 0.30D;
    }

    /** Energy left at {@code distance} from a blast of {@code power}. */
    public static double available(double power, double distance, double radius) {
        return radius <= 0.0D ? 0.0D : power * 1.30D * (1.0D - distance / radius);
    }
}
