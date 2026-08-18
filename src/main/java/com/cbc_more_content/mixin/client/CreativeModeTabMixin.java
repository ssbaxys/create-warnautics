package com.cbc_more_content.mixin.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.cbc_more_content.registry.ModCreativeTabs;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reserves one inventory row for the animated Bombed card. Empty stacks are used
 * by the picker menu itself (the same representation vanilla uses for blank slots).
 */
@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabMixin {
    private static final int CARD_SLOTS = 9;

    @Shadow
    private Collection<ItemStack> displayItems;

    @Inject(method = "buildContents", at = @At("RETURN"))
    private void cbcMoreContent$reserveBombedCardRow(
            CreativeModeTab.ItemDisplayParameters parameters,
            CallbackInfo callback) {
        if ((Object) this != ModCreativeTabs.WARNAUTICS_TAB.get()) {
            return;
        }

        List<ItemStack> padded = new ArrayList<>(this.displayItems.size() + CARD_SLOTS);
        for (int i = 0; i < CARD_SLOTS; i++) {
            padded.add(ItemStack.EMPTY);
        }
        padded.addAll(this.displayItems);
        this.displayItems = padded;
    }
}
