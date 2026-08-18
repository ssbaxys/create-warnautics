package com.cbc_more_content.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int cbcMoreContent$getLeftPos();

    @Accessor("topPos")
    int cbcMoreContent$getTopPos();
}
