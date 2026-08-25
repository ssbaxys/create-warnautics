package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CBCMoreContent.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WARNAUTICS_TAB =
            CREATIVE_MODE_TABS.register("warnautics_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CBCMoreContent.MOD_ID + ".warnautics_tab"))
                    .icon(() -> new ItemStack(ModItems.SMALL_BOMB.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.SMALL_BOMB.get());
                        output.accept(ModItems.SMALL_BOMB_2.get());
                        output.accept(ModItems.SMALL_BOMB_3.get());
                        output.accept(ModItems.SMALL_BOMB_4.get());
                        output.accept(ModItems.MEDIUM_BOMB.get());
                        output.accept(ModItems.LARGE_BOMB.get());
                        output.accept(ModItems.SEA_BOMB.get());
                        output.accept(ModItems.SMALL_MINE.get());
                        output.accept(ModItems.BOUNDING_MINE.get());
                        output.accept(ModItems.LARGE_MINE.get());
                        output.accept(ModItems.C4.get());
                        output.accept(ModItems.CRUISE_MISSILE.get());
                        output.accept(ModItems.TRIPWIRE_COIL.get());
                        output.accept(ModItems.SIREN.get());
                        output.accept(ModItems.DETONATOR.get());
                        output.accept(ModItems.WIRE_CUTTERS.get());
                        output.accept(ModItems.TARGET_DESIGNATOR.get());
                        output.accept(ModItems.SETTINGS_KEY.get());
                        output.accept(ModItems.MUSIC_DISC_BREAKER_OF_SKIES.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
