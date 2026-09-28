package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Shared smoothed exposure for renderer combinations which need the lightweight overlay. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class BombFlashOverlay {
    private BombFlashOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!com.cbc_more_content.config.WarnauticsClientConfig.screenEffects()) {
            return;
        }
        if (mc.level == null || mc.player == null || !FlashExposure.visible()) {
            return;
        }
        if (net.neoforged.fml.ModList.get().isLoaded("veil")
                && !FlashRenderMode.sodiumExtrasLoaded()
                && com.cbc_more_content.config.WarnauticsClientConfig.screenEffects()) {
            return;
        }
        float alpha = Mth.clamp(FlashExposure.exposure() * .48f + FlashExposure.glow(), 0, .68f);
        if (alpha < .003f) {
            return;
        }
        int color = ((int) (alpha * 255) << 24) | 0xffe2ab;
        var graphics = event.getGuiGraphics();
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), color);
    }
}
