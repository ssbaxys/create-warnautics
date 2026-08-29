package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.RadarSettingsPayload;
import com.cbc_more_content.radar.InterceptSettings;
import com.cbc_more_content.registry.ModSounds;
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
 * Intercept conditions for a radar network.
 * <p>
 * Two sliders and a toggle, in the same panel the charges use, because the operator
 * setting up a battery is the same person who set the fuses. Deliberately not a mirror of
 * Radar's own filter slots — those stay Radar's business, and this only decides which of
 * the tracks it hands over are worth spending a missile on.
 */
@OnlyIn(Dist.CLIENT)
public class RadarSettingsScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;

    private static final int TRACK_X = 22;
    private static final int TRACK_W = 132;
    private static final int SPEED_Y = 26;
    private static final int RANGE_Y = 48;
    private static final int KNOB_W = 8;
    private static final int KNOB_H = 14;

    private static final int TOGGLE_X = 22;
    private static final int TOGGLE_Y = 62;
    private static final int TOGGLE_W = 132;
    private static final int TOGGLE_H = 14;

    private static final int BUTTON_W = 72;
    private static final int BUTTON_H = 16;
    private static final int BUTTON_X = 52;
    private static final int BUTTON_Y = 78;

    private final BlockPos controller;
    private float minSpeed;
    private int maxRange;
    private boolean hullsOnly;
    /** Which slider the mouse took hold of: 0 none, 1 speed, 2 range. */
    private int dragging;

    private boolean sent;

    private int guiLeft;
    private int guiTop;

    public RadarSettingsScreen(BlockPos controller, InterceptSettings settings) {
        super(Component.translatable("gui.cbc_more_content.radar.title"));
        this.controller = controller;
        this.minSpeed = settings.minSpeed();
        this.maxRange = settings.maxRange();
        this.hullsOnly = settings.hullsOnly();
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x50101010, 0x50101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_PANEL.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title, this.guiLeft + PANEL_W / 2, this.guiTop + 5, 0xE8E2CF);

        this.renderSlider(
                graphics,
                SPEED_Y,
                this.speedProgress(),
                Component.translatable("gui.cbc_more_content.radar.min_speed", String.format("%.2f", this.minSpeed)));
        this.renderSlider(
                graphics,
                RANGE_Y,
                this.rangeProgress(),
                Component.translatable("gui.cbc_more_content.radar.max_range", this.maxRange));
        this.renderToggle(graphics, mouseX, mouseY);
        this.renderConfirm(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSlider(GuiGraphics graphics, int offsetY, float progress, Component label) {
        int x = this.guiLeft + TRACK_X;
        int y = this.guiTop + offsetY;

        graphics.drawString(this.font, label, x, y - 10, 0x9AA08C, false);
        graphics.fill(x, y + 5, x + TRACK_W, y + 8, 0xFF12140F);
        graphics.fill(x + 1, y + 6, x + TRACK_W - 1, y + 7, 0xFF3A3F34);

        int knob = x + Math.round(progress * (TRACK_W - KNOB_W));
        graphics.fill(knob, y, knob + KNOB_W, y + KNOB_H, 0xFF12140F);
        graphics.fill(knob + 1, y + 1, knob + KNOB_W - 1, y + KNOB_H - 1, 0xFFB08A3E);
    }

    private void renderToggle(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + TOGGLE_X;
        int y = this.guiTop + TOGGLE_Y;
        boolean hot = this.over(mouseX, mouseY, x, y, TOGGLE_W, TOGGLE_H);
        graphics.fill(x, y, x + TOGGLE_W, y + TOGGLE_H, 0xFF12140F);
        graphics.fill(
                x + 1,
                y + 1,
                x + TOGGLE_W - 1,
                y + TOGGLE_H - 1,
                this.hullsOnly ? 0xFF3A4033 : (hot ? 0xFF2A2E24 : 0xFF1C1E1B));
        graphics.fill(x + 1, y + 1, x + 3, y + TOGGLE_H - 1, this.hullsOnly ? 0xFFB08A3E : 0xFF5C6450);
        graphics.drawString(
                this.font,
                Component.translatable("gui.cbc_more_content.radar.hulls_only"),
                x + 8,
                y + 3,
                this.hullsOnly ? 0xFFB036 : 0x9AA08C,
                false);
    }

    private void renderConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        boolean hot = this.over(mouseX, mouseY, x, y, BUTTON_W, BUTTON_H);
        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1, hot ? 0xFF4A5042 : 0xFF3A3F34);
        graphics.drawCenteredString(
                this.font,
                Component.translatable("gui.cbc_more_content.radar.apply"),
                x + BUTTON_W / 2,
                y + 4,
                hot ? 0xFFB036 : 0xE8E2CF);
    }

    private float speedProgress() {
        return this.minSpeed / InterceptSettings.MIN_SPEED_CEILING;
    }

    private float rangeProgress() {
        return (this.maxRange - InterceptSettings.RANGE_FLOOR)
                / (float) (InterceptSettings.RANGE_CEILING - InterceptSettings.RANGE_FLOOR);
    }

    private boolean over(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private boolean overTrack(double mouseX, double mouseY, int offsetY) {
        return this.over(mouseX, mouseY, this.guiLeft + TRACK_X, this.guiTop + offsetY, TRACK_W, KNOB_H);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.overTrack(mouseX, mouseY, SPEED_Y)) {
            this.dragging = 1;
            this.dragTo(mouseX);
            return true;
        }
        if (this.overTrack(mouseX, mouseY, RANGE_Y)) {
            this.dragging = 2;
            this.dragTo(mouseX);
            return true;
        }
        if (this.over(mouseX, mouseY, this.guiLeft + TOGGLE_X, this.guiTop + TOGGLE_Y, TOGGLE_W, TOGGLE_H)) {
            this.hullsOnly = !this.hullsOnly;
            this.click(this.hullsOnly ? 1.4f : 1.0f);
            return true;
        }
        if (this.over(mouseX, mouseY, this.guiLeft + BUTTON_X, this.guiTop + BUTTON_Y, BUTTON_W, BUTTON_H)) {
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
        float progress =
                Mth.clamp((float) (mouseX - (this.guiLeft + TRACK_X + KNOB_W / 2.0D)) / (TRACK_W - KNOB_W), 0.0f, 1.0f);
        if (this.dragging == 1) {
            float next = progress * InterceptSettings.MIN_SPEED_CEILING;
            // Quantised, so the number under the label settles instead of flickering.
            next = Math.round(next * 100.0f) / 100.0f;
            if (next != this.minSpeed) {
                this.minSpeed = next;
                this.tick(progress);
            }
        } else if (this.dragging == 2) {
            int next = InterceptSettings.RANGE_FLOOR
                    + Math.round(progress * (InterceptSettings.RANGE_CEILING - InterceptSettings.RANGE_FLOOR));
            next = next / 8 * 8;
            if (next != this.maxRange) {
                this.maxRange = next;
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
        PacketDistributor.sendToServer(new RadarSettingsPayload(
                this.controller, new InterceptSettings(this.minSpeed, this.maxRange, this.hullsOnly)));
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
