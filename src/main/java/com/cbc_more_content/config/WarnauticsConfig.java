package com.cbc_more_content.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side tuning. Defaults describe a usable heavy bomber: a released stick
 * never cooks off the payload still on the aircraft, and one detonation cannot
 * flood a client with more block updates than it can mesh in a tick.
 */
public final class WarnauticsConfig {
    public static final ModConfigSpec SPEC;

    /** Warnautics blasts igniting other Warnautics bombs. Off = bombers are practical. */
    public static final ModConfigSpec.BooleanValue FRIENDLY_CHAIN_DETONATION;
    /** External TNT / shells / fire still cooking off bombs. */
    public static final ModConfigSpec.BooleanValue EXTERNAL_CHAIN_DETONATION;
    /** Hard ceiling on blocks a single detonation may change, per detonation. */
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_DETONATION;

    public static final ModConfigSpec.DoubleValue GLASS_SHATTER_RADIUS_MULTIPLIER;
    /** Global multiplier on particle/flash volume sent to clients. */
    public static final ModConfigSpec.DoubleValue BLAST_FX_SCALE;
    /** Multiplier on the arc a bomb is pushed onto when it leaves the rack. */
    public static final ModConfigSpec.DoubleValue RELEASE_IMPULSE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Create Warnautics — detonation behaviour").push("detonation");
        FRIENDLY_CHAIN_DETONATION = builder.comment(
                        "Allow a Warnautics bomb blast to cook off other Warnautics bombs.",
                        "false (default): a released bomb can never destroy the payload still",
                        "carried by the aircraft, so tightly packed bomb bays are safe.",
                        "true: pre-1.0.2 behaviour — one hit detonates the whole rack.")
                .define("friendlyChainDetonation", false);
        EXTERNAL_CHAIN_DETONATION = builder.comment(
                        "Allow non-Warnautics explosions (TNT, cannon shells, creepers), fire",
                        "and lava to cook off placed bombs. This is what makes an ammunition",
                        "hit on a bomber dangerous, and is unrelated to friendly chaining.")
                .define("externalChainDetonation", true);
        builder.pop();

        builder.comment("Create Warnautics — performance limits").push("performance");
        GLASS_SHATTER_RADIUS_MULTIPLIER = builder.comment(
                        "Radius in which a bomb blast shatters exposed glass, as a multiple of",
                        "the blast's own crater radius. Glass too far below the surface or too",
                        "deep behind solid cover survives; anything in the open breaks even far",
                        "outside the crater itself.")
                .defineInRange("glassShatterRadiusMultiplier", 6.0D, 1.0D, 16.0D);
        MAX_BLOCKS_PER_DETONATION = builder.comment(
                        "Maximum blocks a single detonation may change. Carpet bombing with",
                        "unbounded craters is the main cause of client stalls and out-of-memory",
                        "crashes, because every changed block is meshed again by the renderer.")
                .defineInRange("maxBlocksPerDetonation", 2600, 128, 60000);
        BLAST_FX_SCALE = builder.comment(
                        "Multiplier on the amount of blast particles and flash packets sent to",
                        "clients. Lower this on servers where players run heavy shader setups.")
                .defineInRange("blastFxScale", 1.0D, 0.0D, 1.0D);
        RELEASE_IMPULSE = builder.comment(
                        "Multiplier on the release impulse that throws a bomb onto its arc.",
                        "The impulse is forward along the bomb's nose plus a slight downward",
                        "bias, and never has an upward component at any value.",
                        "Set to 0.0 for a pure drop that only inherits carrier velocity.")
                .defineInRange("releaseImpulse", 1.0D, 0.0D, 3.0D);
        builder.pop();

        SPEC = builder.build();
    }

    private WarnauticsConfig() {}

    public static boolean friendlyChainDetonation() {
        return SPEC.isLoaded() && FRIENDLY_CHAIN_DETONATION.get();
    }

    public static boolean externalChainDetonation() {
        return !SPEC.isLoaded() || EXTERNAL_CHAIN_DETONATION.get();
    }

    public static int maxBlocksPerDetonation() {
        return SPEC.isLoaded() ? MAX_BLOCKS_PER_DETONATION.get() : 2600;
    }

    public static float blastFxScale() {
        return SPEC.isLoaded() ? BLAST_FX_SCALE.get().floatValue() : 1.0f;
    }

    public static double glassShatterRadiusMultiplier() {
        return SPEC.isLoaded() ? GLASS_SHATTER_RADIUS_MULTIPLIER.get().doubleValue() : 6.0D;
    }

    public static double releaseImpulse() {
        return SPEC.isLoaded() ? RELEASE_IMPULSE.get() : 1.0D;
    }
}
