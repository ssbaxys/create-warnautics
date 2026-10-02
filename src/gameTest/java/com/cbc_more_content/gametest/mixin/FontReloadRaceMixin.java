package com.cbc_more_content.gametest.mixin;

import com.cbc_more_content.PonderClientCheck;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forces the background font lookup into the real font reload's retirement window. */
@Mixin(FontManager.class)
public abstract class FontReloadRaceMixin {
    @Inject(method = "apply", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V", ordinal = 0))
    private void warnautics$raceLookupAgainstReload(CallbackInfo callback) {
        if (Boolean.getBoolean("warnautics.ponderCheck")) {
            PonderClientCheck.raceFontLookup();
        }
    }
}
