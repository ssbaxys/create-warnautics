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
    private ClientSetup() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            com.cbc_more_content.Warmup.client();
            ResourceLocation cassette = ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "cassette");
            // 1→0.25, 2→0.5, 3→0.75, 4→1.0 — matches item model overrides.
            ItemProperties.register(
                    ModItems.SMALL_BOMB.get(),
                    cassette,
                    (stack, level, entity, seed) -> DropBombItem.getCassette(stack) / 4.0f);

            ResourceLocation bound = ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bound");
            ItemProperties.register(
                    ModItems.DETONATOR.get(),
                    bound,
                    (stack, level, entity, seed) -> com.cbc_more_content.item.DetonatorItem.boundCharges(stack)
                                    .isEmpty()
                            ? 0.0f
                            : 1.0f);

            ResourceLocation vestLinked = ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "vest_linked");
            ItemProperties.register(
                    ModItems.DETONATOR.get(),
                    vestLinked,
                    (stack, level, entity, seed) ->
                            com.cbc_more_content.item.DetonatorItem.holdsVestLink(stack) ? 1.0f : 0.0f);

            if (ModList.get().isLoaded("veil")) {
                try {
                    Class<?> veilFx = Class.forName("com.cbc_more_content.client.veil.VeilBombFx");
                    NeoForge.EVENT_BUS.register(veilFx);
                    Class<?> concussionFx = Class.forName("com.cbc_more_content.client.veil.VeilConcussionFx");
                    NeoForge.EVENT_BUS.register(concussionFx);
                    CBCMoreContent.LOGGER.info("Veil bomb flash lights/bloom and concussion pass enabled");
                } catch (ReflectiveOperationException e) {
                    CBCMoreContent.LOGGER.warn("Veil present but bomb FX failed to register: {}", e.toString());
                }
            }
        });
    }
}
