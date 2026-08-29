package com.cbc_more_content.mixin.client;

import com.cbc_more_content.client.BombedCreativeCardRenderer;
import com.cbc_more_content.registry.ModCreativeTabs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin {
    @Shadow
    private static CreativeModeTab selectedTab;

    @Inject(method = "render", at = @At("TAIL"))
    private void cbcMoreContent$renderBombedCard(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        if (selectedTab == ModCreativeTabs.WARNAUTICS_TAB.get()) {
            BombedCreativeCardRenderer.render((CreativeModeInventoryScreen) (Object) this, graphics, mouseX, mouseY);
        }
    }
}
