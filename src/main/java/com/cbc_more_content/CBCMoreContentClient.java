package com.cbc_more_content;

import com.cbc_more_content.client.ClientSetup;
import com.cbc_more_content.client.SeaBombRenderer;
import com.cbc_more_content.registry.ModEntityTypes;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonProjectileRenderer;

@Mod(value = CBCMoreContent.MOD_ID, dist = Dist.CLIENT)
public class CBCMoreContentClient {
    public CBCMoreContentClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(ClientSetup::onClientSetup);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.SMALL_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.SEA_BOMB.get(), SeaBombRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.MEDIUM_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.LARGE_BOMB.get(), BigCannonProjectileRenderer::new);
    }
}
