package com.cbc_more_content.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side switches for the Veil effects.
 * <p>
 * Each effect can be disabled separately for performance or driver compatibility.
 */
public final class WarnauticsClientConfig {
    public static final ModConfigSpec SPEC;

    /** Veil point lights thrown by a detonation. */
    public static final ModConfigSpec.BooleanValue BOMB_LIGHTS;
    /** Veil post-processing pass for the flash and the concussion blur. */
    public static final ModConfigSpec.BooleanValue SCREEN_EFFECTS;

    public static final ModConfigSpec.BooleanValue MISSILE_PLUME;
    public static final ModConfigSpec.BooleanValue MISSILE_LIGHTS;
    public static final ModConfigSpec.BooleanValue STABLE_LIGHTING_BUFFERS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Create Warnautics — Veil effects").push("veil");
        BOMB_LIGHTS = builder.comment(
                        "Real dynamic light from detonations, through Veil's light renderer.",
                        "Veil draws a point light as an inverted cube; if one of those cubes",
                        "is ever rasterised into the scene it shows up as a flat white sheet",
                        "and can leave the first-person hand drawn at the wrong transform.",
                        "Turn this off to rule the lights out.")
                .define("bombLights", true);
        SCREEN_EFFECTS = builder.comment(
                        "Veil post-processing: the long-range flash and the concussion blur.",
                        "Disables camera effects, including the fallback overlay; dynamic lights are controlled separately.")
                .define("screenEffects", true);
        MISSILE_PLUME = builder.comment(
                        "Volumetric cruise missile exhaust through Veil. Falls back to particles when disabled.")
                .define("missilePlume", true);
        MISSILE_LIGHTS = builder.comment("Dynamic light following cruise missile engines.")
                .define("missileLights", true);
        STABLE_LIGHTING_BUFFERS = builder.comment(
                        "Prepare Veil's normal/albedo buffers when joining a world and retain them while dynamic lights are enabled.",
                        "Avoids shader recompilation when a rocket/explosion light enters or leaves view.",
                        "Uses additional GPU buffer memory/bandwidth between effects; disable on bandwidth-limited GPUs.")
                .define("stableLightingBuffers", true);
        builder.pop();

        SPEC = builder.build();
    }

    public static boolean missilePlume() {
        return !SPEC.isLoaded() || MISSILE_PLUME.get();
    }

    public static boolean missileLights() {
        return !SPEC.isLoaded() || MISSILE_LIGHTS.get();
    }

    public static boolean stableLightingBuffers() {
        return !SPEC.isLoaded() || STABLE_LIGHTING_BUFFERS.get();
    }

    private WarnauticsClientConfig() {}

    public static boolean bombLights() {
        return !SPEC.isLoaded() || BOMB_LIGHTS.get();
    }

    public static boolean screenEffects() {
        return !SPEC.isLoaded() || SCREEN_EFFECTS.get();
    }
}
