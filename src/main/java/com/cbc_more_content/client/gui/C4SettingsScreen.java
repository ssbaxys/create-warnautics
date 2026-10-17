package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiButton;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.ConfigureC4Payload;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fuse timer for a placed C4 charge: the charge's own display, a 5–45 second slider and
 * the button that arms it.
 * <p>
 * Last page of planting, and only reached on a charge set to run its own clock — the
 * trigger is chosen before this, and a charge waiting on a detonator is already live by
 * the time this would have opened.
 */
@OnlyIn(Dist.CLIENT)
public class C4SettingsScreen extends WarnauticsScreen {
    public static final int PANEL_W = 196;
    public static final int PANEL_H = 127;
    private static final int DISPLAY_W = 96;
    private static final int DISPLAY_H = 26;

    private final BlockPos pos;
    private final int openedWith;
    private int seconds;
    private boolean armed;

    public C4SettingsScreen(BlockPos pos, int seconds) {
        super(Component.translatable("gui.cbc_more_content.c4.title"), PANEL_W, PANEL_H);
        this.pos = pos;
        this.seconds = clampSeconds(seconds);
        this.openedWith = this.seconds;
    }

    private static int clampSeconds(int value) {
        return Mth.clamp(value, C4BlockEntity.MIN_SECONDS, C4BlockEntity.MAX_SECONDS);
    }

    @Override
    protected void build() {
        this.addRenderableWidget(new UiSlider(
                this.x(Ui.MARGIN),
                this.y(this.contentTop() + DISPLAY_H + 6),
                PANEL_W - Ui.MARGIN * 2,
                Component.translatable("gui.cbc_more_content.c4.fuse"),
                C4BlockEntity.MAX_SECONDS - C4BlockEntity.MIN_SECONDS,
                8,
                () -> this.seconds - C4BlockEntity.MIN_SECONDS,
                step -> this.seconds = clampSeconds(C4BlockEntity.MIN_SECONDS + step),
                step -> Component.translatable("gui.cbc_more_content.unit.seconds", C4BlockEntity.MIN_SECONDS + step)));
        int buttonW = 84;
        this.addRenderableWidget(new UiButton(
                this.x(PANEL_W - Ui.MARGIN - buttonW),
                this.y(this.statusY() - Ui.BUTTON_H - 5),
                buttonW,
                UiButton.Kind.ALARM,
                () -> Component.translatable("gui.cbc_more_content.c4.arm"),
                this::arm));
    }

    @Override
    public void tick() {
        super.tick();
        // The charge can go off, be broken or fall away while this is open.
        if (!C4Screens.stillThere(this.pos)) {
            this.armed = true; // suppress the closing packet for a charge that is gone
            this.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        int x = this.x((PANEL_W - DISPLAY_W) / 2);
        int y = this.y(this.contentTop());
        Ui.well(graphics, x, y, DISPLAY_W, DISPLAY_H);
        // The charge's own clock face: minutes and seconds, as it will count down.
        String clock = String.format(Locale.ROOT, "%02d:%02d", this.seconds / 60, this.seconds % 60);
        Ui.readout(
                graphics,
                this.font,
                Component.literal(clock),
                x + DISPLAY_W / 2,
                y + DISPLAY_H / 2 + 1,
                DISPLAY_W - 10,
                Ui.AMBER);
        Ui.lamp(graphics, x + 5, y + 5, Ui.RED, 0.5f + 0.5f * Mth.sin(now * 0.4f));

        Ui.caseLabel(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.c4.step", 3, 3),
                this.x(Ui.MARGIN),
                this.y(this.statusY() - Ui.BUTTON_H + 1),
                PANEL_W - Ui.MARGIN * 2 - 92);
    }

    @Override
    protected Component status() {
        return Component.translatable("gui.cbc_more_content.c4.timer.status");
    }

    @Override
    protected int statusColour() {
        return Ui.RED;
    }

    @Override
    protected int lampColour() {
        return Ui.AMBER;
    }

    @Override
    protected boolean primaryAction() {
        this.arm();
        return true;
    }

    /** The trigger was chosen on the page before, so this is where the fuse starts. */
    private void arm() {
        if (this.armed) {
            return;
        }
        this.armed = true;
        UiSounds.confirm();
        PacketDistributor.sendToServer(new ConfigureC4Payload(this.pos, this.seconds, true, false));
        this.onClose();
    }

    @Override
    public void onClose() {
        // Closing without confirming keeps the chosen fuse but leaves the charge unarmed.
        // Nothing is sent when the slider was never moved, so opening the screen to read
        // the timer and closing it again cannot overwrite the setting.
        if (!this.armed && this.seconds != this.openedWith) {
            PacketDistributor.sendToServer(new ConfigureC4Payload(this.pos, this.seconds, false, false));
        }
        super.onClose();
    }
}
