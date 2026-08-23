package com.cbc_more_content.client.gui;

import java.util.Locale;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.network.ConfigureC4Payload;
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

/**
 * Fuse timer for a placed C4 charge: a continuous 5–45 second slider and a confirm
 * button that arms it. Separate from the bomb dial, which snaps to six fixed presets.
 */
@OnlyIn(Dist.CLIENT)
public class C4SettingsScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;
    private static final int TRACK_X = 22;
    private static final int TRACK_Y = 52;
    private static final int TRACK_W = 132;
    private static final int KNOB_W = 8;
    private static final int KNOB_H = 16;
    private static final int BUTTON_W = 72;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_X = 52;
    private static final int BUTTON_Y = 74;

    private final BlockPos pos;
    private final int openedWith;
    private int seconds;
    private boolean dragging;
    private boolean armed;
    private int ticksOpen;

    private int guiLeft;
    private int guiTop;

    public C4SettingsScreen(BlockPos pos, int seconds) {
        super(Component.translatable("gui.cbc_more_content.c4.title"));
        this.pos = pos;
        this.seconds = clampSeconds(seconds);
        this.openedWith = this.seconds;
    }

    private static int clampSeconds(int value) {
        return Mth.clamp(value, C4BlockEntity.MIN_SECONDS, C4BlockEntity.MAX_SECONDS);
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void tick() {
        this.ticksOpen++;
        // The charge can go off, be broken or fall away while this is open.
        if (!C4Screens.stillThere(this.pos)) {
            this.armed = true; // suppress the closing packet for a charge that is gone
            this.onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int alpha = ((int) (0x50 * Math.min(1.0f, (this.ticksOpen + partialTick) / 8.0f))) << 24;
        graphics.fillGradient(0, 0, this.width, this.height, 0x101010 | alpha, 0x101010 | alpha);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_SETTINGS.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title,
                this.guiLeft + PANEL_W / 2, this.guiTop + 4, 0xE8E2CF);

        graphics.drawCenteredString(this.font,
                Component.literal(String.format(Locale.ROOT, "%d s", this.seconds)),
                this.guiLeft + PANEL_W / 2, this.guiTop + 30, 0xFFB036);

        this.renderTrack(graphics, mouseX, mouseY);
        this.renderButton(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderTrack(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + TRACK_X;
        int y = this.guiTop + TRACK_Y;

        graphics.fill(x, y + 6, x + TRACK_W, y + 9, 0xFF1C1E1B);
        graphics.fill(x, y + 6, x + TRACK_W, y + 7, 0xFF6C745E);

        // End labels, so the usable range is readable without dragging first.
        graphics.drawString(this.font, String.valueOf(C4BlockEntity.MIN_SECONDS),
                x - 14, y + 4, 0x9AA08C, false);
        graphics.drawString(this.font, String.valueOf(C4BlockEntity.MAX_SECONDS),
                x + TRACK_W + 4, y + 4, 0x9AA08C, false);

        int knobX = x + Math.round(this.progress() * (TRACK_W - KNOB_W));
        boolean hot = this.dragging || this.overTrack(mouseX, mouseY);
        int face = hot ? 0xFFB08A3E : 0xFF5C6450;
        graphics.fill(knobX, y, knobX + KNOB_W, y + KNOB_H, 0xFF12140F);
        graphics.fill(knobX + 1, y + 1, knobX + KNOB_W - 1, y + KNOB_H - 1, face);
        graphics.fill(knobX + 3, y + 4, knobX + KNOB_W - 3, y + KNOB_H - 4, 0xFF12140F);
    }

    private void renderButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        boolean hot = this.overButton(mouseX, mouseY);
        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1,
                hot ? 0xFF4A5042 : 0xFF3A3F34);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.cbc_more_content.c4.arm"),
                x + BUTTON_W / 2, y + 5, hot ? 0xFFB036 : 0xE8E2CF);
    }

    private float progress() {
        return (this.seconds - C4BlockEntity.MIN_SECONDS)
                / (float) (C4BlockEntity.MAX_SECONDS - C4BlockEntity.MIN_SECONDS);
    }

    private boolean overTrack(double mouseX, double mouseY) {
        int x = this.guiLeft + TRACK_X;
        int y = this.guiTop + TRACK_Y;
        return mouseX >= x - 2 && mouseX <= x + TRACK_W + 2 && mouseY >= y && mouseY <= y + KNOB_H;
    }

    private boolean overButton(double mouseX, double mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.overButton(mouseX, mouseY)) {
            this.arm();
            return true;
        }
        if (this.overTrack(mouseX, mouseY)) {
            this.dragging = true;
            this.slideTo(mouseX);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging) {
            this.slideTo(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            this.setSeconds(this.seconds + (scrollY > 0.0D ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void slideTo(double mouseX) {
        int x = this.guiLeft + TRACK_X;
        float t = Mth.clamp((float) (mouseX - x - KNOB_W / 2.0D) / (TRACK_W - KNOB_W), 0.0f, 1.0f);
        this.setSeconds(Math.round(Mth.lerp(t,
                C4BlockEntity.MIN_SECONDS, C4BlockEntity.MAX_SECONDS)));
    }

    /** Detents on whole seconds, each with its own click. */
    private void setSeconds(int value) {
        int clamped = clampSeconds(value);
        if (clamped == this.seconds) {
            return;
        }
        this.seconds = clamped;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.22f,
                    1.1f + this.progress() * 0.7f);
        }
    }

    private void arm() {
        this.armed = true;
        PacketDistributor.sendToServer(new ConfigureC4Payload(this.pos, this.seconds, true));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(ModSounds.C4_BUTTON.get(), 0.9f, 0.85f);
        }
        this.onClose();
    }

    @Override
    public void onClose() {
        // Closing without confirming keeps the chosen fuse but leaves the charge unarmed.
        // Nothing is sent when the slider was never moved, so opening the screen to read
        // the timer and closing it again cannot overwrite the setting.
        if (!this.armed && this.seconds != this.openedWith) {
            PacketDistributor.sendToServer(new ConfigureC4Payload(this.pos, this.seconds, false));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
