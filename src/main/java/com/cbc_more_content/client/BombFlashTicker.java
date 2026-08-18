package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client tick for flash lifetimes (Veil owns rendering when present). */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class BombFlashTicker {
    private BombFlashTicker() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        BombFlashClient.tick();
    }
}
