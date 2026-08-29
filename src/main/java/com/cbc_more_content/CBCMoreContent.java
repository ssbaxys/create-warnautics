package com.cbc_more_content;

import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.network.ModNetworking;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModCreativeTabs;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.registry.ModLootModifiers;
import com.cbc_more_content.registry.ModParticles;
import com.cbc_more_content.registry.ModSounds;
import com.cbc_more_content.util.ReflectiveDispatcher;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(CBCMoreContent.MOD_ID)
public class CBCMoreContent {
    public static final String MOD_ID = "cbc_more_content";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CBCMoreContent(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, WarnauticsConfig.SPEC);
        modContainer.registerConfig(
                net.neoforged.fml.config.ModConfig.Type.CLIENT,
                com.cbc_more_content.config.WarnauticsClientConfig.SPEC);

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);
        ModLootModifiers.SERIALIZERS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(ModNetworking::register);

        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.compat.sable.SableCollisionDetonationQueue", "register", new Class<?>[0]);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModEntityTypes.registerMunitionHandlers();
            // Pay the class-loading cost here rather than inside the first detonation.
            Warmup.common();
        });
        LOGGER.info("Create Warnautics loaded — drop bombs with CBC ballistics + optional Sable kick");
    }
}
