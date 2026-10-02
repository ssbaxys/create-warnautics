package com.cbc_more_content.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Keeps background text measurement from repopulating the cache with retiring font providers. */
@Mixin(FontManager.class)
public abstract class FontReloadMixin {
    @WrapMethod(method = "getFontSetCached")
    private FontSet warnautics$lookupCurrentFont(ResourceLocation location, Operation<FontSet> original) {
        synchronized (this) {
            return original.call(location);
        }
    }

    @WrapMethod(method = "apply")
    private void warnautics$replaceFontsAtomically(
            @Coerce Object preparation, ProfilerFiller profiler, Operation<Void> original) {
        synchronized (this) {
            original.call(preparation, profiler);
        }
    }
}
