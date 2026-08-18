package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.item.DropBombItem;
import com.cbc_more_content.registry.ModItems;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientSetup {
    private ClientSetup() {
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ResourceLocation cassette = ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "cassette");
            // 1→0.25, 2→0.5, 3→0.75, 4→1.0 — matches item model overrides.
            ItemProperties.register(ModItems.SMALL_BOMB.get(), cassette,
                    (stack, level, entity, seed) -> DropBombItem.getCassette(stack) / 4.0f);

            // Veil is registered whenever it is present, exactly as before. The
            // GuiGraphics overlay in BombFlashOverlay runs on every client on top of
            // it, so the flash exists with Veil, without Veil, and under Sodium/Iris.
            if (ModList.get().isLoaded("veil")) {
                try {
                    Class<?> veilFx = Class.forName("com.cbc_more_content.client.veil.VeilBombFx");
                    NeoForge.EVENT_BUS.register(veilFx);
                    Class<?> concussionFx =
                            Class.forName("com.cbc_more_content.client.veil.VeilConcussionFx");
                    NeoForge.EVENT_BUS.register(concussionFx);
                    CBCMoreContent.LOGGER.info("Veil bomb flash lights/bloom and concussion pass enabled");
                } catch (ReflectiveOperationException e) {
                    CBCMoreContent.LOGGER.warn("Veil present but bomb FX failed to register: {}", e.toString());
                }
            }
        });
    }
}
