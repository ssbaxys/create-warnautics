package com.cbc_more_content.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Decides which of the two flash renderers is in charge on this client.
 * <p>
 * Veil's post-processing stage and Sodium/Iris both want to own the render pipeline
 * and cannot be active together. Rather than letting Veil fail quietly — which is how
 * Sodium users ended up with detonations that produced no flash at all — the mode is
 * resolved once and each renderer checks it before doing any work.
 */
@OnlyIn(Dist.CLIENT)
public final class FlashRenderMode {
    private static Boolean veilOwnsFlash;

    private FlashRenderMode() {
    }

    /**
     * True when Veil can realistically drive its own post pass: Veil present and no
     * Sodium/Iris-family renderer replacing the pipeline underneath it.
     * <p>
     * This only decides how <em>bright</em> the overlay draws. Veil itself is always
     * registered when present, and the overlay always draws — making them exclusive
     * was a mistake that cost Veil users their flash entirely.
     */
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

    /**
     * How strongly the screen overlay draws. When Veil is already lighting the scene
     * and running bloom, the overlay only tops it up so the two do not add into a
     * white-out; everywhere else it carries the whole flash on its own.
     */
    public static float overlayWeight() {
        return veilOwnsFlash() ? 0.4f : 1.0f;
    }

    /** True when a shader pack is likely present, so emissive particles are worth spawning. */
    public static boolean shaderPipelinePresent() {
        ModList mods = ModList.get();
        return mods.isLoaded("iris") || mods.isLoaded("oculus");
    }
}
