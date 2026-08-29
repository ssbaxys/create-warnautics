package com.cbc_more_content.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

@OnlyIn(Dist.CLIENT)
public final class FlashRenderMode {
    private static Boolean veilOwnsFlash;
    private static Boolean sodiumExtrasLoaded;

    private FlashRenderMode() {}

    public static boolean sodiumExtrasLoaded() {
        Boolean cached = sodiumExtrasLoaded;
        if (cached != null) {
            return cached;
        }
        ModList mods = ModList.get();
        boolean resolved = mods.isLoaded("sodiumextras")
                || mods.isLoaded("sodium_extra")
                || mods.isLoaded("sodium-extra")
                || mods.isLoaded("sodiumextras-neoforge");
        sodiumExtrasLoaded = resolved;
        return resolved;
    }

    public static boolean veilOwnsFlash() {
        Boolean cached = veilOwnsFlash;
        if (cached != null) {
            return cached;
        }
        ModList mods = ModList.get();
        boolean resolved = mods.isLoaded("veil")
                && !sodiumExtrasLoaded()
                && !mods.isLoaded("sodium")
                && !mods.isLoaded("embeddium")
                && !mods.isLoaded("rubidium")
                && !mods.isLoaded("iris")
                && !mods.isLoaded("oculus");
        veilOwnsFlash = resolved;
        return resolved;
    }

    /** Veil already lights the scene, so there the overlay only tops it up. */
    public static float overlayWeight() {
        return veilOwnsFlash() ? 0.4f : 1.0f;
    }
}
