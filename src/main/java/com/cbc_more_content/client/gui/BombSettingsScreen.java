package com.cbc_more_content.client.gui;

import java.util.Locale;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.network.ConfigureBombPayload;

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
 * Release-interval dial for a placed bomb, opened by the settings key.
 * <p>
 * Follows the interaction language Simulated uses for its instrument screens — a
 * recessed well with a physical control you grab and turn, detent clicks fed back
 * through {@code LEVER_CLICK} at a rising pitch, a value that eases toward its target
 * instead of snapping, and settings committed once on close — but drawn from this
 * mod's own atlas and palette.
 */
@OnlyIn(Dist.CLIENT)
public class BombSettingsScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;
    /** Total sweep of the dial, centred on 12 o'clock. */
    private static final float ARC_DEGREES = 240.0f;
    private static final int DIAL_MARKERS = 11;
    private static final int KNOB_CX = 50;
    private static final int KNOB_CY = 54;
    private static final int DETENT_RADIUS = 37;

    private final BlockPos pos;
    private final int cassette;

    private int delayTicks;
    private boolean dragging;
    private int ticksOpen;
    /** Eased pointer angle, so the dial swings to a new detent rather than jumping. */
    private float previousVisualAngle;
    private float visualAngle;
    private float targetAngle;

    private int guiLeft;
    private int guiTop;

    public BombSettingsScreen(BlockPos pos, int storedDelay, int cassette) {
        super(Component.translatable("gui.cbc_more_content.bomb_settings.title"));
        this.pos = pos;
        this.delayTicks = DropBombBlock.releaseDelayTicks(storedDelay);
        this.cassette = cassette;
        this.targetAngle = angleOf(this.delayTicks);
        this.visualAngle = this.targetAngle;
        this.previousVisualAngle = this.targetAngle;
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    private static float angleAtProgress(float progress) {
        return -ARC_DEGREES * 0.5f + ARC_DEGREES * Mth.clamp(progress, 0.0f, 1.0f);
    }

    private static float progressOf(int ticks) {
        return (DropBombBlock.normalizeReleaseDelayTicks(ticks) - DropBombBlock.MIN_RELEASE_DELAY_TICKS)
                / (float) (DropBombBlock.MAX_RELEASE_DELAY_TICKS - DropBombBlock.MIN_RELEASE_DELAY_TICKS);
    }

    private static float angleOf(int ticks) {
        return angleAtProgress(progressOf(ticks));
    }

    @Override
    public void tick() {
        this.ticksOpen++;
        this.previousVisualAngle = this.visualAngle;
        // Exponential chase, the same feel Catnip's LerpedFloat gives Simulated's dials.
        this.visualAngle += (this.targetAngle - this.visualAngle) * 0.32f;
        if (Math.abs(this.targetAngle - this.visualAngle) < 0.05f) {
            this.visualAngle = this.targetAngle;
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

        WarnauticsGuiTextures.BOMB_SETTINGS.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title,
                this.guiLeft + PANEL_W / 2, this.guiTop + 4, 0xE8E2CF);

        this.renderDetents(graphics);
        this.renderKnob(graphics, mouseX, mouseY, partialTick);
        this.renderReadout(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderDetents(GuiGraphics graphics) {
        int selectedMarker = Math.round(progressOf(this.delayTicks) * (DIAL_MARKERS - 1));
        for (int i = 0; i < DIAL_MARKERS; i++) {
            double rad = Math.toRadians(angleAtProgress(i / (float) (DIAL_MARKERS - 1)));
            WarnauticsGuiTextures sprite = i == selectedMarker
                    ? WarnauticsGuiTextures.DETENT_LIT
                    : WarnauticsGuiTextures.DETENT;
            // Centred on the sprite's own size rather than a hardcoded half-width, so the
            // dots stay on the ring whatever dimensions the authored atlas gives them.
            int x = this.guiLeft + KNOB_CX
                    + (int) Math.round(Math.sin(rad) * DETENT_RADIUS) - sprite.width / 2;
            int y = this.guiTop + KNOB_CY
                    - (int) Math.round(Math.cos(rad) * DETENT_RADIUS) - sprite.height / 2;
            sprite.render(graphics, x, y);
        }
    }

    private void renderKnob(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean lit = this.dragging || this.overKnob(mouseX, mouseY);
        WarnauticsGuiTextures sprite = lit
                ? WarnauticsGuiTextures.KNOB_LIT
                : WarnauticsGuiTextures.KNOB;

        int cx = this.guiLeft + KNOB_CX;
        int cy = this.guiTop + KNOB_CY;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0.0f);
        float renderedAngle = Mth.lerp(partialTick, this.previousVisualAngle, this.visualAngle);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(renderedAngle));
        pose.translate(-sprite.width / 2.0f, -sprite.height / 2.0f, 0.0f);
        sprite.render(graphics, 0, 0);
        pose.popPose();
    }

    private void renderReadout(GuiGraphics graphics) {
        int ticks = this.delayTicks;
        int x = this.guiLeft + 130;
        int y = this.guiTop + 30;

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.cbc_more_content.bomb_settings.interval"),
                x, y, 0x9AA08C);
        graphics.drawCenteredString(this.font,
                Component.literal(ticks + "t"), x, y + 14, 0xFFB036);
        graphics.drawCenteredString(this.font,
                Component.literal(String.format(Locale.ROOT, "%.1f s", ticks / 20.0D)),
                x, y + 24, 0xE8E2CF);

        if (this.cassette > 1) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.cbc_more_content.bomb_settings.cassette", this.cassette),
                    this.guiLeft + KNOB_CX, this.guiTop + PANEL_H - 12, 0x9AA08C);
        }
    }

    private boolean overKnob(double mouseX, double mouseY) {
        double dx = mouseX - (this.guiLeft + KNOB_CX);
        double dy = mouseY - (this.guiTop + KNOB_CY);
        return dx * dx + dy * dy <= 23 * 23;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.overKnob(mouseX, mouseY)) {
            this.dragging = true;
            this.turnTo(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging) {
            this.turnTo(mouseX, mouseY);
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
            this.setDelayTicks(this.delayTicks + (scrollY > 0.0D
                    ? DropBombBlock.RELEASE_DELAY_STEP_TICKS
                    : -DropBombBlock.RELEASE_DELAY_STEP_TICKS));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Maps the cursor's bearing onto 0.1-second steps across the full dial. */
    private void turnTo(double mouseX, double mouseY) {
        double dx = mouseX - (this.guiLeft + KNOB_CX);
        double dy = mouseY - (this.guiTop + KNOB_CY);
        if (dx * dx + dy * dy < 4.0D) {
            return;
        }
        float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
        angle = Mth.clamp(angle, -ARC_DEGREES * 0.5f, ARC_DEGREES * 0.5f);
        float progress = (angle + ARC_DEGREES * 0.5f) / ARC_DEGREES;
        int ticks = Math.round(Mth.lerp(progress,
                DropBombBlock.MIN_RELEASE_DELAY_TICKS,
                DropBombBlock.MAX_RELEASE_DELAY_TICKS));
        this.setDelayTicks(ticks);
    }

    private void setDelayTicks(int ticks) {
        int clamped = DropBombBlock.normalizeReleaseDelayTicks(ticks);
        if (clamped == this.delayTicks) {
            return;
        }
        this.delayTicks = clamped;
        this.targetAngle = angleOf(clamped);
        this.playClick(0.6f + progressOf(clamped) * 0.7f);
    }

    private void playClick(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.LEVER_CLICK, 0.25f, pitch);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new ConfigureBombPayload(this.pos, this.delayTicks));
        super.onClose();
    }
}
