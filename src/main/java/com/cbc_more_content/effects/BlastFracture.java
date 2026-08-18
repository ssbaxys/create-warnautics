package com.cbc_more_content.effects;

/**
 * Shared fracture model for the Warnautics block-damage passes.
 * <p>
 * Vanilla charges a block's explosion resistance linearly, which makes obsidian (1200)
 * two hundred times the cost of stone (6). That scale exists so a four-power TNT charge
 * can never touch it, and it means a heavy aerial bomb behaves exactly like TNT against
 * anything hard: a large bomb has roughly twenty units of energy at its core against a
 * linear cost of three hundred and sixty, so hardened structures came out untouched no
 * matter the payload.
 * <p>
 * Real overpressure scales against material strength far more gently than that. Charging
 * a sub-linear curve keeps ordinary terrain behaving as before while letting payload size
 * actually matter: stone stays trivial, obsidian and reinforced deepslate come within
 * reach of the largest charges at close range only, and small bombs still cannot dent
 * them at any distance.
 */
public final class BlastFracture {
    /**
     * Compression exponent. Chosen so that a large bomb's core energy sits just above
     * obsidian's cost while a medium bomb's sits below it.
     */
    private static final double RESISTANCE_EXPONENT = 0.58D;

    private BlastFracture() {
    }

    /** Energy a block absorbs before it breaks. */
    public static double cost(double explosionResistance) {
        double resistance = Math.max(0.0D, explosionResistance);
        return (Math.pow(resistance, RESISTANCE_EXPONENT) + 0.3D) * 0.30D;
    }

    /** Energy available at {@code distance} from a blast of {@code power}. */
    public static double available(double power, double distance, double radius) {
        if (radius <= 0.0D) {
            return 0.0D;
        }
        return power * 1.30D * (1.0D - distance / radius);
    }
}
