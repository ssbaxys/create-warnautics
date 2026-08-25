package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.SirenSettingsPayload;
import com.cbc_more_content.registry.ModSounds;
import com.cbc_more_content.siren.SirenSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * What an air-raid post watches for.
 * <p>
 * Two columns, because the panel frame is 176 by 95 and that is all of it. Laid out down
 * one column it ran a third of its height past the bottom edge, and every control below
 * the fold sat on the dimmed background with no panel behind it — which read, fairly
 * enough, as the settings not working.
 * <p>
 * The trigger toggles stay visible while auto is off and read as dark, so the setting is
 * somewhere to come back to rather than something that vanished.
 */
@OnlyIn(Dist.CLIENT)
public class SirenSettingsScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;

    /** Left column: the two sliders. */
    private static final int TRACK_X = 10;
    private static final int TRACK_W = 82;
    private static final int KNOB_W = 7;
    private static final int KNOB_H = 12;
    private static final int RADIUS_Y = 30;
    private static final int LINGER_Y = 55;

    /** Right column: the three switches. */
    private static final int ROW_X = 100;
    private static final int ROW_W = 66;
    private static final int ROW_H = 13;
    private static final int AUTO_Y = 20;
    private static final int MISSILES_Y = 37;
    private static final int BOMBS_Y = 54;

    private static final int BUTTON_W = 66;
    private static final int BUTTON_H = 14;
    private static final int BUTTON_X = 100;
    private static final int BUTTON_Y = 73;

    private final BlockPos pos;
    private boolean auto;
    private int radius;
    private int lingerSeconds;
    private boolean watchMissiles;
    private boolean watchBombs;
    /** Which slider the mouse took hold of: 0 none, 1 radius, 2 linger. */
    private int dragging;
    private boolean sent;

    private int guiLeft;
    private int guiTop;

    public SirenSettingsScreen(BlockPos pos, SirenSettings settings) {
        super(Component.translatable("gui.cbc_more_content.siren.title"));
        this.pos = pos;
        this.auto = settings.auto();
        this.radius = settings.radius();
        this.lingerSeconds = settings.lingerSeconds();
        this.watchMissiles = settings.watchMissiles();
        this.watchBombs = settings.watchBombs();
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockState(this.pos).getBlock()
                instanceof com.cbc_more_content.block.SirenBlock)) {
            this.onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x50101010, 0x50101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_PANEL.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title,
                this.guiLeft + PANEL_W / 2, this.guiTop + 5, 0xE8E2CF);

        this.renderSlider(graphics, RADIUS_Y, this.radiusProgress(),
                Component.translatable("gui.cbc_more_content.siren.radius", this.radius));
        this.renderSlider(graphics, LINGER_Y, this.lingerProgress(),
                Component.translatable("gui.cbc_more_content.siren.linger", this.lingerSeconds));

        this.renderRow(graphics, mouseX, mouseY, AUTO_Y, this.auto, true,
                Component.translatable("gui.cbc_more_content.siren.auto"));
        this.renderRow(graphics, mouseX, mouseY, MISSILES_Y, this.watchMissiles, this.auto,
                Component.translatable("gui.cbc_more_content.siren.watch_missiles"));
        this.renderRow(graphics, mouseX, mouseY, BOMBS_Y, this.watchBombs, this.auto,
                Component.translatable("gui.cbc_more_content.siren.watch_bombs"));

        this.renderConfirm(graphics, mouseX, mouseY);

        graphics.drawString(this.font,
                Component.translatable("gui.cbc_more_content.siren.hint"),
                this.guiLeft + TRACK_X, this.guiTop + 76, 0x7A8070, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSlider(GuiGraphics graphics, int offsetY, float progress, Component label) {
        int x = this.guiLeft + TRACK_X;
        int y = this.guiTop + offsetY;

        graphics.drawString(this.font, label, x, y - 10, 0x9AA08C, false);
        graphics.fill(x, y + 4, x + TRACK_W, y + 7, 0xFF12140F);
        graphics.fill(x + 1, y + 5, x + TRACK_W - 1, y + 6, 0xFF3A3F34);

        int knob = x + Math.round(progress * (TRACK_W - KNOB_W));
        graphics.fill(knob, y, knob + KNOB_W, y + KNOB_H, 0xFF12140F);
        graphics.fill(knob + 1, y + 1, knob + KNOB_W - 1, y + KNOB_H - 1, 0xFFB08A3E);
    }

    private void renderRow(
            GuiGraphics graphics, int mouseX, int mouseY, int offsetY,
            boolean on, boolean live, Component label) {
        int x = this.guiLeft + ROW_X;
        int y = this.guiTop + offsetY;
        boolean hot = live && this.over(mouseX, mouseY, x, y, ROW_W, ROW_H);
        graphics.fill(x, y, x + ROW_W, y + ROW_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + ROW_W - 1, y + ROW_H - 1,
                on && live ? 0xFF3A4033 : (hot ? 0xFF2A2E24 : 0xFF1C1E1B));
        graphics.fill(x + 1, y + 1, x + 3, y + ROW_H - 1,
                on && live ? 0xFFB08A3E : 0xFF5C6450);
        graphics.drawString(this.font, label, x + 7, y + 3,
                live ? (on ? 0xFFB036 : 0x9AA08C) : 0x5A5F52, false);
    }

    private void renderConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        boolean hot = this.over(mouseX, mouseY, x, y, BUTTON_W, BUTTON_H);
        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1,
                hot ? 0xFF4A5042 : 0xFF3A3F34);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.cbc_more_content.siren.apply"),
                x + BUTTON_W / 2, y + 3, hot ? 0xFFB036 : 0xE8E2CF);
    }

    private float radiusProgress() {
        return (this.radius - SirenSettings.RADIUS_FLOOR)
                / (float) (SirenSettings.RADIUS_CEILING - SirenSettings.RADIUS_FLOOR);
    }

    private float lingerProgress() {
        return (this.lingerSeconds - SirenSettings.LINGER_FLOOR)
                / (float) (SirenSettings.LINGER_CEILING - SirenSettings.LINGER_FLOOR);
    }

    private boolean over(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private boolean overTrack(double mouseX, double mouseY, int offsetY) {
        return this.over(mouseX, mouseY, this.guiLeft + TRACK_X, this.guiTop + offsetY,
                TRACK_W, KNOB_H);
    }

    private boolean overRow(double mouseX, double mouseY, int offsetY) {
        return this.over(mouseX, mouseY, this.guiLeft + ROW_X, this.guiTop + offsetY, ROW_W, ROW_H);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.overRow(mouseX, mouseY, AUTO_Y)) {
            this.auto = !this.auto;
            this.click(this.auto ? 1.4f : 1.0f);
            return true;
        }
        // Both trigger rows are dead while auto is off, rather than quietly changing a
        // setting nothing is reading.
        if (this.auto && this.overRow(mouseX, mouseY, MISSILES_Y)) {
            this.watchMissiles = !this.watchMissiles;
            this.click(this.watchMissiles ? 1.35f : 1.0f);
            return true;
        }
        if (this.auto && this.overRow(mouseX, mouseY, BOMBS_Y)) {
            this.watchBombs = !this.watchBombs;
            this.click(this.watchBombs ? 1.35f : 1.0f);
            return true;
        }
        if (this.overTrack(mouseX, mouseY, RADIUS_Y)) {
            this.dragging = 1;
            this.dragTo(mouseX);
            return true;
        }
        if (this.overTrack(mouseX, mouseY, LINGER_Y)) {
            this.dragging = 2;
            this.dragTo(mouseX);
            return true;
        }
        if (this.over(mouseX, mouseY, this.guiLeft + BUTTON_X, this.guiTop + BUTTON_Y,
                BUTTON_W, BUTTON_H)) {
            this.confirm();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging != 0) {
            this.dragTo(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragTo(double mouseX) {
        float progress = Mth.clamp(
                (float) (mouseX - (this.guiLeft + TRACK_X + KNOB_W / 2.0D)) / (TRACK_W - KNOB_W),
                0.0f, 1.0f);
        if (this.dragging == 1) {
            int next = SirenSettings.RADIUS_FLOOR + Math.round(progress
                    * (SirenSettings.RADIUS_CEILING - SirenSettings.RADIUS_FLOOR));
            next = next / 8 * 8;
            if (next != this.radius) {
                this.radius = next;
                this.tick(progress);
            }
        } else if (this.dragging == 2) {
            int next = SirenSettings.LINGER_FLOOR + Math.round(progress
                    * (SirenSettings.LINGER_CEILING - SirenSettings.LINGER_FLOOR));
            next = next / 5 * 5;
            if (next != this.lingerSeconds) {
                this.lingerSeconds = next;
                this.tick(progress);
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new SirenSettingsPayload(this.pos,
                new SirenSettings(this.auto, this.radius, this.lingerSeconds,
                        this.watchMissiles, this.watchBombs)));
        this.click(0.85f);
        this.onClose();
    }

    private void tick(float progress) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.22f, 1.1f + progress * 0.7f);
        }
    }

    private void click(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(ModSounds.C4_BUTTON.get(), 0.75f, pitch);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
