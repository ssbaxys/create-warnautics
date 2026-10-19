package com.cbc_more_content.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side tuning: explosion strength, chain detonation, C4 keypad, detonator, mines,
 * tripwires, cruise missiles and per-blast work limits. Lives in the world's serverconfig,
 * so every world and server can set its own. Explosive blocks physically destroyed by any
 * explosion always detonate.
 */
public final class WarnauticsConfig {
    public static final ModConfigSpec SPEC;

    /** Warnautics blasts igniting other airborne bombs. */
    public static final ModConfigSpec.BooleanValue FRIENDLY_CHAIN_DETONATION;
    /** External damage igniting airborne bombs, and fire/projectile hits cooking off placed bombs. */
    public static final ModConfigSpec.BooleanValue EXTERNAL_CHAIN_DETONATION;
    /** Hard ceiling on blocks a single detonation may change, per detonation. */
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_DETONATION;

    public static final ModConfigSpec.DoubleValue GLASS_SHATTER_RADIUS_MULTIPLIER;
    /** Global multiplier on particle/flash volume sent to clients. */
    public static final ModConfigSpec.DoubleValue BLAST_FX_SCALE;
    /** Multiplier on the arc a bomb is pushed onto when it leaves the rack. */
    public static final ModConfigSpec.DoubleValue RELEASE_IMPULSE;
    /** Powered flight time of a cruise missile, in seconds. */
    public static final ModConfigSpec.IntValue CRUISE_FUEL_SECONDS;
    /** Whether cruise missiles hug the terrain between launch and terminal approach. */
    public static final ModConfigSpec.BooleanValue CRUISE_TERRAIN_FOLLOWING;

    // —— explosions ——
    public static final ModConfigSpec.DoubleValue BLOCK_POWER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue ENTITY_POWER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue BOMB_POWER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MISSILE_POWER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue C4_POWER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MINE_POWER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue ROCKET_POWER_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue DESTROY_TERRAIN;
    public static final ModConfigSpec.BooleanValue SCORCH_GROUND;

    // —— C4 keypad ——
    public static final ModConfigSpec.IntValue C4_CODE_ATTEMPTS;
    public static final ModConfigSpec.DoubleValue C4_LOCKOUT_SECONDS;
    public static final ModConfigSpec.DoubleValue C4_LOCKOUT_GROWTH;
    public static final ModConfigSpec.DoubleValue C4_MAX_LOCKOUT_SECONDS;
    public static final ModConfigSpec.DoubleValue C4_FORGET_FAILURES_SECONDS;
    public static final ModConfigSpec.BooleanValue C4_LOCKOUT_PER_PLAYER;
    public static final ModConfigSpec.IntValue C4_DETONATE_AFTER_LOCKOUTS;
    public static final ModConfigSpec.BooleanValue C4_PRESETS;
    public static final ModConfigSpec.BooleanValue C4_ARM_ON_THROW;
    public static final ModConfigSpec.BooleanValue C4_PRESET_AUTO_PAIR;

    // —— detonator ——
    public static final ModConfigSpec.DoubleValue DETONATOR_RANGE;
    public static final ModConfigSpec.IntValue DETONATOR_MAX_CHARGES;

    // —— mines ——
    public static final ModConfigSpec.DoubleValue MINE_ARM_SECONDS;
    public static final ModConfigSpec.DoubleValue CLAYMORE_ARM_SECONDS;
    public static final ModConfigSpec.IntValue CLAYMORE_BALLS;
    public static final ModConfigSpec.DoubleValue CLAYMORE_FAN_DEGREES;
    public static final ModConfigSpec.DoubleValue CLAYMORE_BALL_SPEED;
    public static final ModConfigSpec.BooleanValue CLAYMORE_ONLY_WHEN_ARMED;
    public static final ModConfigSpec.DoubleValue CLAYMORE_BALL_DAMAGE;

    // —— tripwire ——
    public static final ModConfigSpec.IntValue TRIPWIRES_PER_POST;

    // —— rocket pod ——
    public static final ModConfigSpec.IntValue ROCKET_POD_CAPACITY;
    public static final ModConfigSpec.IntValue ROCKETS_PER_SALVO;
    public static final ModConfigSpec.IntValue ROCKET_RIPPLE_TICKS;
    public static final ModConfigSpec.DoubleValue ROCKET_SPEED;
    public static final ModConfigSpec.IntValue ROCKET_BURN_TICKS;
    public static final ModConfigSpec.DoubleValue ROCKET_DISPERSION_DEGREES;
    public static final ModConfigSpec.DoubleValue ROCKET_LIFETIME_SECONDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Create Warnautics — detonation behaviour").push("detonation");
        FRIENDLY_CHAIN_DETONATION = builder.comment(
                        "Allow close, exposed Warnautics blasts to damage armed airborne fuzes.",
                        "Peripheral pressure deflects munitions instead of igniting them.",
                        "Explosive blocks destroyed by a blast always chain-detonate, regardless of this setting.")
                .define("friendlyChainDetonation", true);
        EXTERNAL_CHAIN_DETONATION = builder.comment(
                        "Allow external damage to ignite airborne bombs, and fire/lava/projectile",
                        "hits to cook off placed bombs. Explosive blocks destroyed by any blast",
                        "always chain-detonate, regardless of this setting.")
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

        builder.comment("Create Warnautics — cruise missile").push("cruiseMissile");
        CRUISE_FUEL_SECONDS = builder.comment(
                        "Sustainer fuel in seconds of powered flight. Range is roughly",
                        "fuel x 80 blocks for the direct profile; arc and weave burn faster.")
                .defineInRange("fuelSeconds", 24, 5, 120);
        CRUISE_TERRAIN_FOLLOWING = builder.comment(
                        "Fly low over the ground and the sea (terrain following) during cruise.",
                        "When off, the missile flies a straight line at its launch altitude.")
                .define("terrainFollowing", true);
        builder.pop();

        builder.comment(
                        "Create Warnautics — explosion strength.",
                        "Power multipliers stack: global x type. 1.0 is the mod as designed; 0 turns",
                        "that part of a blast off entirely (no crater, or no damage).")
                .push("explosions");
        BLOCK_POWER_MULTIPLIER = builder.comment("Crater size of every Warnautics explosion.")
                .defineInRange("blockPowerMultiplier", 1.0D, 0.0D, 10.0D);
        ENTITY_POWER_MULTIPLIER = builder.comment("Damage and pressure reach of every Warnautics explosion.")
                .defineInRange("entityPowerMultiplier", 1.0D, 0.0D, 10.0D);
        BOMB_POWER_MULTIPLIER = builder.comment("Dropped bombs of every size, MOAB and torpedoes included.")
                .defineInRange("bombPowerMultiplier", 1.0D, 0.0D, 10.0D);
        MISSILE_POWER_MULTIPLIER =
                builder.comment("Cruise missile warheads.").defineInRange("missilePowerMultiplier", 1.0D, 0.0D, 10.0D);
        C4_POWER_MULTIPLIER =
                builder.comment("C4 charges and bomb vests.").defineInRange("c4PowerMultiplier", 1.0D, 0.0D, 10.0D);
        MINE_POWER_MULTIPLIER = builder.comment("Land mines, sea mines and the claymore back-blast.")
                .defineInRange("minePowerMultiplier", 1.0D, 0.0D, 10.0D);
        ROCKET_POWER_MULTIPLIER = builder.comment("Unguided rockets from rocket pods.")
                .defineInRange("rocketPowerMultiplier", 1.0D, 0.0D, 10.0D);
        DESTROY_TERRAIN = builder.comment(
                        "Whether explosions dig craters and break blocks. Off: blasts still hurt,",
                        "push and throw, but leave the world as it was.")
                .define("destroyTerrain", true);
        SCORCH_GROUND = builder.comment(
                        "Whether blasts scar the ground around them (grass to dirt and coarse dirt,",
                        "scorched stone). Turn off to keep lawns green, or alongside Explosion't.")
                .define("scorchGround", true);
        builder.pop();

        builder.comment("Create Warnautics — C4 keypad").push("c4");
        C4_CODE_ATTEMPTS = builder.comment(
                        "Wrong codes allowed before the keypad locks. 0 = never locks (unlimited",
                        "guessing, as before).")
                .defineInRange("codeAttempts", 3, 0, 100);
        C4_LOCKOUT_SECONDS = builder.comment("How long the first lockout lasts, in seconds.")
                .defineInRange("lockoutSeconds", 10.0D, 0.5D, 86400.0D);
        C4_LOCKOUT_GROWTH = builder.comment(
                        "Each further lockout on the same charge lasts this many times longer.",
                        "1.0 = always the same; 2.0 = 10 s, 20 s, 40 s ...")
                .defineInRange("lockoutGrowth", 1.0D, 1.0D, 10.0D);
        C4_MAX_LOCKOUT_SECONDS = builder.comment("Longest a lockout may grow to, in seconds.")
                .defineInRange("maxLockoutSeconds", 300.0D, 0.5D, 86400.0D);
        C4_FORGET_FAILURES_SECONDS = builder.comment(
                        "Seconds without a wrong code after which the keypad forgets earlier",
                        "failures and lockouts. 0 = never forgets.")
                .defineInRange("forgetFailuresSeconds", 120.0D, 0.0D, 86400.0D);
        C4_LOCKOUT_PER_PLAYER = builder.comment(
                        "true: every player has their own attempts and lockout on a charge.",
                        "false: the charge itself locks for everyone, so a team cannot take turns.")
                .define("lockoutPerPlayer", false);
        C4_DETONATE_AFTER_LOCKOUTS = builder.comment(
                        "Anti-tamper: the charge goes off after this many lockouts in a row.", "0 = never.")
                .defineInRange("detonateAfterLockouts", 0, 0, 100);
        C4_PRESETS = builder.comment(
                        "Whether a C4 stack can be set up before the throw (sneak + use it in the air):",
                        "code, timer or detonator, and when it goes live.")
                .define("presets", true);
        C4_ARM_ON_THROW = builder.comment(
                        "Whether a preset may make the charge live the moment it is thrown. When false,",
                        "preset charges land set up but idle and are started with the settings key.")
                .define("armOnThrow", true);
        C4_PRESET_AUTO_PAIR = builder.comment(
                        "Whether a preset detonator charge pairs itself, on landing live, with a detonator",
                        "in the thrower's inventory.")
                .define("presetAutoPair", true);
        builder.pop();

        builder.comment("Create Warnautics — detonator").push("detonator");
        DETONATOR_RANGE = builder.comment("How far, in blocks, the firing device reaches its charges.")
                .defineInRange("range", 250.0D, 8.0D, 4096.0D);
        DETONATOR_MAX_CHARGES = builder.comment("Charges and claymores one detonator can be paired with.")
                .defineInRange("maxCharges", 12, 1, 128);
        builder.pop();

        builder.comment("Create Warnautics — mines").push("mines");
        MINE_ARM_SECONDS = builder.comment("Seconds after placing before a land mine is live.")
                .defineInRange("armSeconds", 1.0D, 0.0D, 600.0D);
        CLAYMORE_ARM_SECONDS = builder.comment("Seconds after placing before a claymore can be fired.")
                .defineInRange("claymoreArmSeconds", 3.0D, 0.0D, 600.0D);
        CLAYMORE_BALLS = builder.comment(
                        "Steel balls a claymore throws. Each one is traced in flight: more is deadlier",
                        "and denser, and costs more when many fire at once.")
                .defineInRange("claymoreBalls", 120, 8, 600);
        CLAYMORE_FAN_DEGREES = builder.comment("Width of the claymore's fan, in degrees (real M18A1: 60).")
                .defineInRange("claymoreFanDegrees", 60.0D, 5.0D, 180.0D);
        CLAYMORE_BALL_SPEED = builder.comment(
                        "Ball speed in blocks per tick. Balls live one second, so the lethal reach is",
                        "about speed x 20 blocks.")
                .defineInRange("claymoreBallSpeed", 3.0D, 0.5D, 8.0D);
        CLAYMORE_ONLY_WHEN_ARMED = builder.comment(
                        "true: a claymore that has not finished arming ignores wires and signals.",
                        "false: it can be fired from the moment it is set down.")
                .define("claymoreOnlyWhenArmed", true);
        CLAYMORE_BALL_DAMAGE = builder.comment(
                        "Damage of one claymore ball before armour. How many balls strike someone",
                        "depends on how much of the fan they fill: dozens point-blank, one or two at",
                        "the edge of its reach. They all land as one blow, so armour cannot shrug off",
                        "a hail of small hits one at a time.")
                .defineInRange("claymoreBallDamage", 5.0D, 0.0D, 100.0D);
        builder.pop();

        builder.comment("Create Warnautics — tripwires").push("tripwire");
        TRIPWIRES_PER_POST =
                builder.comment("Tripwires that can be tied to one post.").defineInRange("wiresPerPost", 4, 1, 32);
        builder.pop();

        builder.comment("Create Warnautics — rocket pod (unguided rockets)").push("rockets");
        ROCKET_POD_CAPACITY = builder.comment("Rockets one pod holds.").defineInRange("podCapacity", 8, 1, 64);
        ROCKETS_PER_SALVO = builder.comment(
                        "Rockets fired by one redstone pulse on a pod not yet set with the settings key.",
                        "0 = everything loaded.")
                .defineInRange("rocketsPerSalvo", 0, 0, 64);
        ROCKET_RIPPLE_TICKS = builder.comment(
                        "Ticks between rockets in a salvo, for a pod not yet set with the settings key.",
                        "0 fires the whole salvo at once.")
                .defineInRange("rippleTicks", 6, 0, 40);
        ROCKET_SPEED = builder.comment("Top speed of a rocket under thrust, in blocks per tick.")
                .defineInRange("speed", 4.0D, 0.5D, 12.0D);
        ROCKET_BURN_TICKS = builder.comment("Ticks the motor burns. After burnout the rocket coasts and drops.")
                .defineInRange("burnTicks", 30, 1, 400);
        ROCKET_DISPERSION_DEGREES = builder.comment(
                        "Random spread of each rocket off the pod's axis, in degrees. 0 = laser straight.")
                .defineInRange("dispersionDegrees", 1.0D, 0.0D, 20.0D);
        ROCKET_LIFETIME_SECONDS = builder.comment("Seconds before a rocket that hit nothing is gone.")
                .defineInRange("lifetimeSeconds", 10.0D, 1.0D, 120.0D);
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

    public static int cruiseFuelTicks() {
        return (SPEC.isLoaded() ? CRUISE_FUEL_SECONDS.get() : 24) * 20;
    }

    public static boolean cruiseTerrainFollowing() {
        return !SPEC.isLoaded() || CRUISE_TERRAIN_FOLLOWING.get();
    }

    private static double get(ModConfigSpec.DoubleValue value, double fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    private static int get(ModConfigSpec.IntValue value, int fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    private static boolean get(ModConfigSpec.BooleanValue value, boolean fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    /** What kind of munition a blast came from, for its own multiplier. */
    public enum Munition {
        BOMB,
        MISSILE,
        C4,
        MINE,
        ROCKET
    }

    /** Crater power after the global and per-type multipliers; 0 when terrain damage is off. */
    public static float blockPower(float power, Munition kind) {
        if (!get(DESTROY_TERRAIN, true)) {
            return 0.0f;
        }
        return (float) (power * get(BLOCK_POWER_MULTIPLIER, 1.0D) * typeMultiplier(kind));
    }

    /** Damage and pressure power after the global and per-type multipliers. */
    public static float entityPower(float power, Munition kind) {
        return (float) (power * get(ENTITY_POWER_MULTIPLIER, 1.0D) * typeMultiplier(kind));
    }

    private static double typeMultiplier(Munition kind) {
        return switch (kind) {
            case BOMB -> get(BOMB_POWER_MULTIPLIER, 1.0D);
            case MISSILE -> get(MISSILE_POWER_MULTIPLIER, 1.0D);
            case C4 -> get(C4_POWER_MULTIPLIER, 1.0D);
            case MINE -> get(MINE_POWER_MULTIPLIER, 1.0D);
            case ROCKET -> get(ROCKET_POWER_MULTIPLIER, 1.0D);
        };
    }

    public static boolean scorchGround() {
        return get(SCORCH_GROUND, true);
    }

    public static int c4CodeAttempts() {
        return get(C4_CODE_ATTEMPTS, 3);
    }

    /** Length of the lockout that follows {@code earlierLockouts} earlier ones, in ticks. */
    public static long c4LockoutTicks(int earlierLockouts) {
        double seconds = get(C4_LOCKOUT_SECONDS, 10.0D) * Math.pow(get(C4_LOCKOUT_GROWTH, 1.0D), earlierLockouts);
        return Math.max(1L, Math.round(Math.min(seconds, get(C4_MAX_LOCKOUT_SECONDS, 300.0D)) * 20.0D));
    }

    public static long c4ForgetFailuresTicks() {
        return Math.round(get(C4_FORGET_FAILURES_SECONDS, 120.0D) * 20.0D);
    }

    public static boolean c4LockoutPerPlayer() {
        return get(C4_LOCKOUT_PER_PLAYER, false);
    }

    public static boolean c4PresetsAllowed() {
        return get(C4_PRESETS, true);
    }

    public static boolean c4ArmOnThrowAllowed() {
        return get(C4_ARM_ON_THROW, true);
    }

    public static boolean c4PresetAutoPair() {
        return get(C4_PRESET_AUTO_PAIR, true);
    }

    public static int c4DetonateAfterLockouts() {
        return get(C4_DETONATE_AFTER_LOCKOUTS, 0);
    }

    public static double detonatorRange() {
        return get(DETONATOR_RANGE, 250.0D);
    }

    public static int detonatorMaxCharges() {
        return get(DETONATOR_MAX_CHARGES, 12);
    }

    public static int mineArmTicks() {
        return (int) Math.round(get(MINE_ARM_SECONDS, 1.0D) * 20.0D);
    }

    public static int claymoreArmTicks() {
        return (int) Math.round(get(CLAYMORE_ARM_SECONDS, 3.0D) * 20.0D);
    }

    public static int claymoreBalls() {
        return get(CLAYMORE_BALLS, 120);
    }

    public static double claymoreHalfAngle() {
        return Math.toRadians(get(CLAYMORE_FAN_DEGREES, 60.0D) * 0.5D);
    }

    public static double claymoreBallSpeed() {
        return get(CLAYMORE_BALL_SPEED, 3.0D);
    }

    public static double claymoreBallDamage() {
        return get(CLAYMORE_BALL_DAMAGE, 5.0D);
    }

    public static boolean claymoreOnlyWhenArmed() {
        return get(CLAYMORE_ONLY_WHEN_ARMED, true);
    }

    public static int tripwiresPerPost() {
        return get(TRIPWIRES_PER_POST, 4);
    }

    public static int rocketPodCapacity() {
        return get(ROCKET_POD_CAPACITY, 8);
    }

    public static int rocketsPerSalvo() {
        return get(ROCKETS_PER_SALVO, 0);
    }

    public static int rocketRippleTicks() {
        return get(ROCKET_RIPPLE_TICKS, 6);
    }

    public static double rocketSpeed() {
        return get(ROCKET_SPEED, 4.0D);
    }

    public static int rocketBurnTicks() {
        return get(ROCKET_BURN_TICKS, 30);
    }

    public static double rocketDispersion() {
        return Math.toRadians(get(ROCKET_DISPERSION_DEGREES, 1.0D));
    }

    public static int rocketLifetimeTicks() {
        return (int) Math.round(get(ROCKET_LIFETIME_SECONDS, 10.0D) * 20.0D);
    }
}
