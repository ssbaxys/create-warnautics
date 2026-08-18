package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.mixin.client.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Animated Warnautics card rendered in the first, reserved row of the Bombed tab.
 * This is intentionally independent of Create Simulated, so the card belongs to
 * our own creative tab and cannot reappear in Simulated's item list.
 */
public final class BombedCreativeCardRenderer {
    /**
     * Deliberately not under {@code textures/gui/sprites/}. Everything in that directory
     * is stitched into the shared GUI atlas, and the animation mcmeta this strip used to
     * carry told the stitcher it was a twelve-frame animation of a single 18px sprite.
     * The file was therefore live in two incompatible roles at once: an atlas sprite the
     * game animated on its own, and a plain texture this class blits frame by frame.
     * Which one won depended on load order, so any mod that touches texture setup — a
     * GeckoLib install is enough — could leave the card showing a stitched fragment or
     * the wrong frame. Kept outside the atlas, it is a plain strip and nothing else.
     */
    private static final ResourceLocation BANNER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    CBCMoreContent.MOD_ID,
                    "textures/gui/banner.png");
    private static final Component TITLE =
            Component.translatable(CBCMoreContent.MOD_ID + ".simulated_section.cbc_more_content");

    private static final int WIDTH = 162;
    private static final int HEIGHT = 18;
    private static final int FRAME_COUNT = 12;
    private static final int TEXTURE_HEIGHT = HEIGHT * FRAME_COUNT;
    /** Matches banner.png.mcmeta frametime=6 (six 20 TPS ticks). */
    private static final long FRAME_TIME_MS = 300L;
    private static final int TITLE_BACKGROUND = 0x602A1810;
    private static final int TITLE_DARK = 0xFFE08A3A;
    private static final int TITLE_LIGHT = 0xFFFFC86A;

    private BombedCreativeCardRenderer() {
    }

    public static void render(
            CreativeModeInventoryScreen screen,
            GuiGraphics graphics,
            int mouseX,
            int mouseY) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        int left = accessor.cbcMoreContent$getLeftPos() + 8;
        int top = accessor.cbcMoreContent$getTopPos() + 17;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        // Only the shader tint is forced, and it is a value every GUI draw expects.
        // Depth state is left as the screen set it: this used to enable depth on entry
        // and disable it on exit, which silently handed the rest of the screen — and any
        // mod drawing after us, such as a GeckoLib item renderer — a different GL state
        // than it was rendered with.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // The strip already contains a complete loop. Playing it backwards made
        // the blast collapse back into a bomb and looked especially awkward at
        // the smoke-to-impact transition.
        int frame = (int) ((Util.getMillis() / FRAME_TIME_MS) % FRAME_COUNT);
        graphics.blit(
                BANNER_TEXTURE,
                left,
                top,
                0.0f,
                frame * HEIGHT,
                WIDTH,
                HEIGHT,
                WIDTH,
                TEXTURE_HEIGHT);

        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(TITLE);
        graphics.fill(left + 2, top + 2, left + textWidth + 8, top + HEIGHT - 2, TITLE_BACKGROUND);
        drawAuraText(graphics, TITLE, left + 5, top + 5);

        pose.popPose();
    }

    /**
     * Highlight pass: the upper part of the glyphs is redrawn in a lighter colour.
     * <p>
     * The clip goes through {@link GuiGraphics}, not {@link RenderSystem}, because
     * GuiGraphics batches its draws and only submits them on flush. A raw GL scissor was
     * applied immediately and lifted again before the batched text was ever submitted, so
     * the clip landed on whatever happened to flush inside that window — usually nothing,
     * occasionally another mod's content. GuiGraphics#enableScissor flushes around itself
     * and keeps a stack, so it clips this text and restores any outer clip afterwards.
     * It also takes GUI coordinates, which removes the manual gui-scale conversion and
     * the framebuffer y-flip that came with it.
     */
    private static void drawAuraText(GuiGraphics graphics, Component text, int x, int y) {
        Font font = Minecraft.getInstance().font;

        graphics.drawString(font, text, x, y, TITLE_DARK, true);

        int right = x + font.width(text);
        int bottom = y + Math.max(1, Math.round(font.lineHeight / 1.8f));
        graphics.enableScissor(x, y, right, bottom);
        graphics.drawString(font, text, x, y, TITLE_LIGHT, false);
        graphics.disableScissor();
    }
}
