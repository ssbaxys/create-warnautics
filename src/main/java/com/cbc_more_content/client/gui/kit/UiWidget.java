package com.cbc_more_content.client.gui.kit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Common ground for the kit's controls: they play the kit's sounds instead of the vanilla
 * button click, show a focus ring only when the keyboard put the focus there, and know
 * whether Enter belongs to them or to the screen.
 */
@OnlyIn(Dist.CLIENT)
public abstract class UiWidget extends AbstractWidget {
    protected UiWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    protected static Font font() {
        return Minecraft.getInstance().font;
    }

    /** Hover, or keyboard focus; never the focus a mouse click leaves behind. */
    protected boolean hot() {
        return this.active && (this.isHovered() || this.keyboardFocused());
    }

    protected boolean keyboardFocused() {
        return this.isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
    }

    /** True when Enter on this control means "operate this control", not "submit". */
    public boolean handlesEnter() {
        return false;
    }

    @Override
    public void playDownSound(SoundManager handler) {
        // Each control plays its own kit sound when it actually changes something.
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
