package com.cbc_more_content.client.gui;

import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.UiToggle;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.RadarSettingsPayload;
import com.cbc_more_content.radar.InterceptSettings;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Intercept conditions for a radar network.
 * <p>
 * Two sliders and a switch, in the same case the charges use, because the operator
 * setting up a battery is the same person who set the fuses. Deliberately not a mirror of
 * Radar's own filter slots — those stay Radar's business, and this only decides which of
 * the tracks it hands over are worth spending a missile on.
 */
@OnlyIn(Dist.CLIENT)
public class RadarSettingsScreen extends WarnauticsScreen {
    public static final int PANEL_W = 212;
    public static final int PANEL_H = 149;
    private static final int RANGE_STEP = 8;

    private final BlockPos controller;
    private float minSpeed;
    private int maxRange;
    private boolean hullsOnly;
    private boolean sent;

    public RadarSettingsScreen(BlockPos controller, InterceptSettings settings) {
        super(Component.translatable("gui.cbc_more_content.radar.title"), PANEL_W, PANEL_H);
        this.controller = controller;
        this.minSpeed = settings.minSpeed();
        this.maxRange = settings.maxRange();
        this.hullsOnly = settings.hullsOnly();
    }

    @Override
    protected void build() {
        int x = this.x(Ui.MARGIN);
        int width = PANEL_W - Ui.MARGIN * 2;
        int y = this.y(this.contentTop());
        this.addRenderableWidget(new UiSlider(
                x,
                y,
                width,
                Component.translatable("gui.cbc_more_content.radar.min_speed_label"),
                Math.round(InterceptSettings.MIN_SPEED_CEILING * 100),
                8,
                () -> Math.round(this.minSpeed * 100),
                step -> this.minSpeed = step / 100.0f,
                step -> Component.translatable(
                        "gui.cbc_more_content.unit.speed", String.format(Locale.ROOT, "%.2f", step / 100.0f))));
        this.addRenderableWidget(new UiSlider(
                x,
                y + UiSlider.HEIGHT + 5,
                width,
                Component.translatable("gui.cbc_more_content.radar.max_range_label"),
                (InterceptSettings.RANGE_CEILING - InterceptSettings.RANGE_FLOOR) / RANGE_STEP,
                8,
                () -> Math.round((this.maxRange - InterceptSettings.RANGE_FLOOR) / (float) RANGE_STEP),
                step -> this.maxRange = InterceptSettings.RANGE_FLOOR + step * RANGE_STEP,
                step -> Component.translatable(
                        "gui.cbc_more_content.unit.blocks", InterceptSettings.RANGE_FLOOR + step * RANGE_STEP)));
        this.addRenderableWidget(new UiToggle(
                x,
                y + (UiSlider.HEIGHT + 5) * 2,
                width,
                () -> Component.translatable("gui.cbc_more_content.radar.hulls_only"),
                () -> this.hullsOnly,
                () -> this.hullsOnly = !this.hullsOnly));
        this.addCancelApply(Component.translatable("gui.cbc_more_content.radar.apply"), this::confirm);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {}

    @Override
    protected Component status() {
        return Component.translatable("gui.cbc_more_content.radar.status");
    }

    @Override
    protected int statusColour() {
        return Ui.PHOSPHOR;
    }

    @Override
    protected int lampColour() {
        return Ui.PHOSPHOR;
    }

    @Override
    protected boolean primaryAction() {
        this.confirm();
        return true;
    }

    private void confirm() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new RadarSettingsPayload(
                this.controller, new InterceptSettings(this.minSpeed, this.maxRange, this.hullsOnly)));
        UiSounds.confirm();
        this.onClose();
    }
}
