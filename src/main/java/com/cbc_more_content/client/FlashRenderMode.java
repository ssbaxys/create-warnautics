package com.cbc_more_content.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Whether Veil can drive its own post pass on this client. Veil's post processing and
 * Sodium/Iris both want the render pipeline and cannot both have it, so the answer is
 * resolved once and only decides how strongly {@link BombFlashOverlay} draws — the
 * overlay itself always runs, which is what makes the flash exist everywhere.
 */
@OnlyIn(Dist.CLIENT)
public final class FlashRenderMode {
    private static Boolean veilOwnsFlash;

    private FlashRenderMode() {
    }

    public static boolean veilOwnsFlash() {
        Boolean cached = veilOwnsFlash;
        if (cached != null) {
            return cached;
        }
        ModList mods = ModList.get();
        boolean resolved = mods.isLoaded("veil")
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
