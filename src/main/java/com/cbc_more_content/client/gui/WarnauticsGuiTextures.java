package com.cbc_more_content.client.gui;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Sprite table for the Warnautics screens.
 * <p>
 * Structured after Simulated's {@code SimGUITextures}: one enum entry per sprite,
 * carrying its atlas offset and size, so screen code never repeats blit coordinates.
 * The atlas itself is drawn in the mod's own palette rather than Simulated's.
 */
@OnlyIn(Dist.CLIENT)
public enum WarnauticsGuiTextures {
    // Coordinates read off the authored atlas rather than assumed: the panel occupies
    // the first band, the two knob states and the two detent dots sit in the second.
    BOMB_SETTINGS("bomb_settings", 0, 0, 176, 95),
    /** Own panel: the bomb sheet has a round dial well the slider cannot sit in. */
    C4_SETTINGS("c4_settings", 0, 0, 176, 95),
    /** Same frame, empty body — for screens that draw their own furniture. */
    C4_PANEL("c4_panel", 0, 0, 176, 95),
    KNOB("bomb_settings", 2, 102, 42, 42),
    KNOB_LIT("bomb_settings", 50, 102, 42, 42),
    DETENT("bomb_settings", 98, 102, 3, 3),
    DETENT_LIT("bomb_settings", 106, 102, 3, 3);

    private static final int ATLAS = 256;

    public final ResourceLocation location;
    public final int startX;
    public final int startY;
    public final int width;
    public final int height;

    WarnauticsGuiTextures(String path, int startX, int startY, int width, int height) {
        this.location = ResourceLocation.fromNamespaceAndPath(
                CBCMoreContent.MOD_ID, "textures/gui/" + path + ".png");
        this.startX = startX;
        this.startY = startY;
        this.width = width;
        this.height = height;
    }

    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(this.location, x, y, this.startX, this.startY,
                this.width, this.height, ATLAS, ATLAS);
    }

    /** Blit with an ARGB tint, used to fade widgets in with the window. */
    public void render(GuiGraphics graphics, int x, int y, float alpha) {
        graphics.setColor(1.0f, 1.0f, 1.0f, alpha);
        this.render(graphics, x, y);
        graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
