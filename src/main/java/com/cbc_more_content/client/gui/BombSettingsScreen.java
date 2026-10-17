package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.ConfigureBombPayload;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Release-interval dial for a placed bomb, opened by the settings key.
 * <p>
 * A recessed well with a brass knob you grab and turn, detents around it, a value that
 * eases toward its target instead of snapping, and the setting committed once on close.
 * The readout beside it says the interval in seconds, the unit people think in, with
 * the game ticks underneath for anyone timing a rack against redstone.
 */
@OnlyIn(Dist.CLIENT)
public class BombSettingsScreen extends WarnauticsScreen {
    public static final int PANEL_W = 196;
    public static final int PANEL_H = 112;
    /** Total sweep of the dial, centred on 12 o'clock. */
    private static final float ARC_DEGREES = 240.0f;

    private static final int DIAL_MARKERS = 11;
    private static final int KNOB_CX = 52;
    private static final int KNOB_CY = 56;
    private static final int WELL_RADIUS = 25;
    private static final int DETENT_RADIUS = 31;
    private static final int READOUT_X = 100;
    private static final int READOUT_W = PANEL_W - READOUT_X - Ui.MARGIN;
    private static final int READOUT_H = 50;

    private final BlockPos pos;
    private final int cassette;

    private int delayTicks;
    private boolean dragging;
    /** Eased pointer angle, so the dial swings to a new detent rather than jumping. */
    private float previousVisualAngle;

    private float visualAngle;
    private float targetAngle;

    public BombSettingsScreen(BlockPos pos, int storedDelay, int cassette) {
        super(Component.translatable("gui.cbc_more_content.bomb_settings.title"), PANEL_W, PANEL_H);
        this.pos = pos;
        this.delayTicks = DropBombBlock.releaseDelayTicks(storedDelay);
        this.cassette = cassette;
        this.targetAngle = angleOf(this.delayTicks);
        this.visualAngle = this.targetAngle;
        this.previousVisualAngle = this.targetAngle;
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
        super.tick();
        this.previousVisualAngle = this.visualAngle;
        // Exponential chase, the same feel Catnip's LerpedFloat gives Simulated's dials.
        this.visualAngle += (this.targetAngle - this.visualAngle) * 0.32f;
        if (Math.abs(this.targetAngle - this.visualAngle) < 0.05f) {
            this.visualAngle = this.targetAngle;
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        Ui.roundWell(graphics, this.x(KNOB_CX), this.y(KNOB_CY), WELL_RADIUS);
        this.renderDetents(graphics);
        this.renderKnob(graphics, mouseX, mouseY, partialTick);
        this.renderReadout(graphics);
    }

    private void renderDetents(GuiGraphics graphics) {
        int selectedMarker = Math.round(progressOf(this.delayTicks) * (DIAL_MARKERS - 1));
        for (int i = 0; i < DIAL_MARKERS; i++) {
            double rad = Math.toRadians(angleAtProgress(i / (float) (DIAL_MARKERS - 1)));
            int x = this.x(KNOB_CX) + (int) Math.round(Math.sin(rad) * DETENT_RADIUS) - 1;
            int y = this.y(KNOB_CY) - (int) Math.round(Math.cos(rad) * DETENT_RADIUS) - 1;
            graphics.fill(x - 1, y - 1, x + 3, y + 3, Ui.OUTLINE);
            graphics.fill(
                    x,
                    y,
                    x + 2,
                    y + 2,
                    i == selectedMarker ? Ui.AMBER : i <= selectedMarker ? Ui.BRASS_SHADE : Ui.CASE_SHADE);
        }
    }

    private void renderKnob(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean lit = this.dragging || this.overKnob(mouseX, mouseY);
        WarnauticsGuiTextures sprite = lit ? WarnauticsGuiTextures.KNOB_LIT : WarnauticsGuiTextures.KNOB;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(this.x(KNOB_CX), this.y(KNOB_CY), 0.0f);
        float renderedAngle = Mth.lerp(partialTick, this.previousVisualAngle, this.visualAngle);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(renderedAngle));
        pose.translate(-sprite.width / 2.0f, -sprite.height / 2.0f, 0.0f);
        sprite.render(graphics, 0, 0);
        pose.popPose();
    }

    private void renderReadout(GuiGraphics graphics) {
        int x = this.x(READOUT_X);
        int y = this.y(this.contentTop());
        Ui.well(graphics, x, y, READOUT_W, READOUT_H);
        Ui.fitCentered(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.bomb_settings.interval"),
                x + READOUT_W / 2,
                y + 5,
                READOUT_W - 8,
                Ui.TEXT_MUTED,
                false);
        Ui.readout(
                graphics,
                this.font,
                Component.translatable(
                        "gui.cbc_more_content.unit.seconds",
                        String.format(Locale.ROOT, "%.1f", this.delayTicks / 20.0D)),
                x + READOUT_W / 2,
                y + 25,
                READOUT_W - 10,
                Ui.AMBER);
        Ui.fitCentered(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.unit.ticks", this.delayTicks),
                x + READOUT_W / 2,
                y + 38,
                READOUT_W - 8,
                Ui.TEXT_DIM,
                false);
        if (this.cassette > 1) {
            Ui.caseLabel(
                    graphics,
                    this.font,
                    Component.translatable("gui.cbc_more_content.bomb_settings.cassette", this.cassette),
                    x,
                    y + READOUT_H + 6,
                    READOUT_W);
        }
    }

    @Override
    protected Component status() {
        return Component.translatable("gui.cbc_more_content.bomb_settings.status");
    }

    @Override
    protected int statusColour() {
        return Ui.AMBER;
    }

    private boolean overKnob(double mouseX, double mouseY) {
        double dx = mouseX - this.x(KNOB_CX);
        double dy = mouseY - this.y(KNOB_CY);
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
            this.step(scrollY > 0.0D ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_DOWN) {
            this.step(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP) {
            this.step(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean primaryAction() {
        this.onClose();
        return true;
    }

    private void step(int direction) {
        this.setDelayTicks(this.delayTicks + direction * DropBombBlock.RELEASE_DELAY_STEP_TICKS);
    }

    /** Maps the cursor's bearing onto 0.1-second steps across the full dial. */
    private void turnTo(double mouseX, double mouseY) {
        double dx = mouseX - this.x(KNOB_CX);
        double dy = mouseY - this.y(KNOB_CY);
        if (dx * dx + dy * dy < 4.0D) {
            return;
        }
        float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
        angle = Mth.clamp(angle, -ARC_DEGREES * 0.5f, ARC_DEGREES * 0.5f);
        float progress = (angle + ARC_DEGREES * 0.5f) / ARC_DEGREES;
        int ticks = Math.round(
                Mth.lerp(progress, DropBombBlock.MIN_RELEASE_DELAY_TICKS, DropBombBlock.MAX_RELEASE_DELAY_TICKS));
        this.setDelayTicks(ticks);
    }

    private void setDelayTicks(int ticks) {
        int clamped = DropBombBlock.normalizeReleaseDelayTicks(ticks);
        if (clamped == this.delayTicks) {
            return;
        }
        this.delayTicks = clamped;
        this.targetAngle = angleOf(clamped);
        UiSounds.detent(progressOf(clamped));
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new ConfigureBombPayload(this.pos, this.delayTicks));
        super.onClose();
    }
}
