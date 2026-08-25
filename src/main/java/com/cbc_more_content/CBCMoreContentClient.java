package com.cbc_more_content;

import com.cbc_more_content.client.C4ProjectileRenderer;
import com.cbc_more_content.client.C4Renderer;
import com.cbc_more_content.client.CruiseMissileBlockRenderer;
import com.cbc_more_content.client.WireCutterRenderer;
import com.cbc_more_content.client.ClientSetup;
import com.cbc_more_content.client.SeaBombRenderer;
import com.cbc_more_content.client.TripwireRenderer;
import com.cbc_more_content.client.particle.MineFragmentParticle;
import com.cbc_more_content.client.particle.MissileExhaustParticle;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.registry.ModParticles;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonProjectileRenderer;

@Mod(value = CBCMoreContent.MOD_ID, dist = Dist.CLIENT)
public class CBCMoreContentClient {
    public CBCMoreContentClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerParticles);
        modEventBus.addListener(this::registerExtraModels);
        modEventBus.addListener(this::registerItemExtensions);
        modEventBus.addListener(ClientSetup::onClientSetup);
    }

    private void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.MINE_FRAGMENT.get(), MineFragmentParticle.Provider::new);
        event.registerSpriteSet(ModParticles.MISSILE_EXHAUST.get(), MissileExhaustParticle.Provider::new);
        event.registerSpriteSet(ModParticles.MISSILE_GAS.get(),
                com.cbc_more_content.client.particle.MissileGasParticle.Provider::new);
    }

    /** The spinning C4 cog is drawn by a renderer, so nothing else pulls it in to bake. */
    private void registerExtraModels(ModelEvent.RegisterAdditional event) {
        event.register(C4Renderer.COG);
        event.register(C4Renderer.COG_ARMED);
        event.register(C4Renderer.COG_LIT);
        event.register(WireCutterRenderer.FULL);
        event.register(WireCutterRenderer.RIGHT);
        event.register(WireCutterRenderer.LEFT);
        event.register(WireCutterRenderer.CENTER);
    }

    /** The cutters are drawn part by part so their handles can hinge. */
    private void registerItemExtensions(RegisterClientExtensionsEvent event) {
        Minecraft mc = Minecraft.getInstance();
        WireCutterRenderer renderer =
                new WireCutterRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModItems.WIRE_CUTTERS.get());
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.SMALL_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.SEA_BOMB.get(), SeaBombRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.MEDIUM_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.LARGE_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.C4.get(), C4ProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.TRIPWIRE.get(), TripwireRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.BOUNDING_MINE.get(),
                com.cbc_more_content.client.BoundingMineRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.BLAST_DEBRIS.get(),
                com.cbc_more_content.client.BlastDebrisRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.CRUISE_MISSILE.get(),
                com.cbc_more_content.client.CruiseMissileRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.C4.get(), C4Renderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CRUISE_MISSILE.get(),
                CruiseMissileBlockRenderer::new);
    }
}
