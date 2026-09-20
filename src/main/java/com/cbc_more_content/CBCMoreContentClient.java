package com.cbc_more_content;

import com.cbc_more_content.client.BombVestLayer;
import com.cbc_more_content.client.C4ProjectileRenderer;
import com.cbc_more_content.client.C4Renderer;
import com.cbc_more_content.client.ClientSetup;
import com.cbc_more_content.client.CruiseMissileBlockRenderer;
import com.cbc_more_content.client.MoabRenderer;
import com.cbc_more_content.client.SeaBombRenderer;
import com.cbc_more_content.client.TripwireRenderer;
import com.cbc_more_content.client.WireCutterRenderer;
import com.cbc_more_content.client.particle.MineFragmentParticle;
import com.cbc_more_content.client.particle.MissileExhaustParticle;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonProjectileRenderer;

@Mod(value = CBCMoreContent.MOD_ID, dist = Dist.CLIENT)
public class CBCMoreContentClient {
    public CBCMoreContentClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::addBombVestLayers);
        modEventBus.addListener(this::registerParticles);
        modEventBus.addListener(this::registerExtraModels);
        modEventBus.addListener(this::registerItemExtensions);
        modEventBus.addListener(ClientSetup::onClientSetup);
    }

    private void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.MINE_FRAGMENT.get(), MineFragmentParticle.Provider::new);
        event.registerSpriteSet(ModParticles.MISSILE_EXHAUST.get(), MissileExhaustParticle.Provider::new);
        event.registerSpriteSet(
                ModParticles.MISSILE_GAS.get(), com.cbc_more_content.client.particle.MissileGasParticle.Provider::new);
    }

    private void registerExtraModels(ModelEvent.RegisterAdditional event) {
        event.register(C4Renderer.COG);
        event.register(C4Renderer.COG_ARMED);
        event.register(C4Renderer.COG_LIT);
        event.register(WireCutterRenderer.FULL);
        event.register(WireCutterRenderer.RIGHT);
        event.register(WireCutterRenderer.LEFT);
        event.register(WireCutterRenderer.CENTER);
        event.register(BombVestLayer.LINKED_MODEL);
    }

    private void registerItemExtensions(RegisterClientExtensionsEvent event) {
        Minecraft mc = Minecraft.getInstance();
        WireCutterRenderer renderer = new WireCutterRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
        event.registerItem(
                new IClientItemExtensions() {
                    @Override
                    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                        return renderer;
                    }
                },
                ModItems.WIRE_CUTTERS.get());
    }

    private void addBombVestLayers(EntityRenderersEvent.AddLayers event) {
        ItemInHandRenderer itemInHandRenderer = event.getContext().getItemInHandRenderer();
        for (PlayerSkin.Model skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                addVestLayer(renderer, itemInHandRenderer);
            }
        }
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof LivingEntityRenderer<?, ?> living) {
                addVestLayerRaw(living, itemInHandRenderer);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addVestLayerRaw(LivingEntityRenderer renderer, ItemInHandRenderer itemInHandRenderer) {
        if (renderer.getModel() instanceof HumanoidModel) {
            renderer.addLayer(new BombVestLayer(renderer, itemInHandRenderer));
        }
    }

    private static void addVestLayer(PlayerRenderer renderer, ItemInHandRenderer itemInHandRenderer) {
        renderer.addLayer(new BombVestLayer<>(renderer, itemInHandRenderer));
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.SMALL_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.SEA_BOMB.get(), SeaBombRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.MEDIUM_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.LARGE_BOMB.get(), BigCannonProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.MOAB.get(), MoabRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.C4.get(), C4ProjectileRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.TRIPWIRE.get(), TripwireRenderer::new);
        event.registerEntityRenderer(
                ModEntityTypes.BOUNDING_MINE.get(), com.cbc_more_content.client.BoundingMineRenderer::new);

        event.registerEntityRenderer(
                ModEntityTypes.BLAST_DEBRIS.get(), com.cbc_more_content.client.BlastDebrisRenderer::new);
        event.registerEntityRenderer(
                ModEntityTypes.CRUISE_MISSILE.get(), com.cbc_more_content.client.CruiseMissileRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.C4.get(), C4Renderer::new);
        // The turning stub of shaft in the socket under a post.
        event.registerBlockEntityRenderer(ModBlockEntities.SIREN.get(), com.cbc_more_content.client.SirenRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CRUISE_MISSILE.get(), CruiseMissileBlockRenderer::new);
    }
}
