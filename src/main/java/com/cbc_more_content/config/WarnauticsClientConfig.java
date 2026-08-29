package com.cbc_more_content.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side switches for the Veil effects.
 * <p>
 * Both default on. They exist because the two Veil paths — dynamic lights and the
 * screen-space flash — fail in ways that look identical from the player's side, and
 * turning one off is the only way to tell which is misbehaving on a given driver.
 */
public final class WarnauticsClientConfig {
    public static final ModConfigSpec SPEC;

    /** Veil point lights thrown by a detonation. */
    public static final ModConfigSpec.BooleanValue BOMB_LIGHTS;
    /** Veil post-processing pass for the flash and the concussion blur. */
    public static final ModConfigSpec.BooleanValue SCREEN_EFFECTS;

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
                        "Without it the mod falls back to plain overlay quads, which look",
                        "worse but cannot touch the framebuffer the hand is drawn into.")
                .define("screenEffects", true);
        builder.pop();

        SPEC = builder.build();
    }

    private WarnauticsClientConfig() {}

    public static boolean bombLights() {
        return !SPEC.isLoaded() || BOMB_LIGHTS.get();
    }

    public static boolean screenEffects() {
        return !SPEC.isLoaded() || SCREEN_EFFECTS.get();
    }
}
