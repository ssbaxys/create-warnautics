package com.cbc_more_content.client.gui;

import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.UiToggle;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.SirenSettingsPayload;
import com.cbc_more_content.siren.SirenSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What an air-raid post watches for.
 * <p>
 * Automatic on top, because it decides whether anything below it matters; the two
 * triggers under it stay visible while it is off and read as dead, so the setting is
 * somewhere to come back to rather than something that vanished. Then how far the post
 * listens and how long it keeps wailing after the sky goes quiet.
 */
@OnlyIn(Dist.CLIENT)
public class SirenSettingsScreen extends WarnauticsScreen {
    public static final int PANEL_W = 212;
    public static final int PANEL_H = 172;
    private static final int RADIUS_STEP = 8;
    private static final int LINGER_STEP = 5;

    private final BlockPos pos;
    private boolean auto;
    private int radius;
    private int lingerSeconds;
    private boolean watchMissiles;
    private boolean watchBombs;
    private boolean sent;

    public SirenSettingsScreen(BlockPos pos, SirenSettings settings) {
        super(Component.translatable("gui.cbc_more_content.siren.title"), PANEL_W, PANEL_H);
        this.pos = pos;
        this.auto = settings.auto();
        this.radius = settings.radius();
        this.lingerSeconds = settings.lingerSeconds();
        this.watchMissiles = settings.watchMissiles();
        this.watchBombs = settings.watchBombs();
    }

    @Override
    protected void build() {
        int x = this.x(Ui.MARGIN);
        int width = PANEL_W - Ui.MARGIN * 2;
        int y = this.y(this.contentTop());
        this.addRenderableWidget(new UiToggle(
                x,
                y,
                width,
                () -> Component.translatable("gui.cbc_more_content.siren.auto"),
                () -> this.auto,
                () -> this.auto = !this.auto));
        // Both trigger rows are dead while auto is off, rather than quietly changing a
        // setting nothing is reading.
        int half = (width - 4) / 2;
        this.addRenderableWidget(new UiToggle(
                x,
                y + Ui.ROW_H + 4,
                half,
                () -> Component.translatable("gui.cbc_more_content.siren.watch_missiles"),
                () -> this.watchMissiles,
                () -> this.watchMissiles = !this.watchMissiles,
                () -> this.auto));
        this.addRenderableWidget(new UiToggle(
                x + width - half,
                y + Ui.ROW_H + 4,
                half,
                () -> Component.translatable("gui.cbc_more_content.siren.watch_bombs"),
                () -> this.watchBombs,
                () -> this.watchBombs = !this.watchBombs,
                () -> this.auto));
        int sliders = y + (Ui.ROW_H + 4) * 2 + 2;
        this.addRenderableWidget(new UiSlider(
                x,
                sliders,
                width,
                Component.translatable("gui.cbc_more_content.siren.radius_label"),
                (SirenSettings.RADIUS_CEILING - SirenSettings.RADIUS_FLOOR) / RADIUS_STEP,
                6,
                () -> Math.round((this.radius - SirenSettings.RADIUS_FLOOR) / (float) RADIUS_STEP),
                step -> this.radius = SirenSettings.RADIUS_FLOOR + step * RADIUS_STEP,
                step -> Component.translatable(
                        "gui.cbc_more_content.unit.blocks", SirenSettings.RADIUS_FLOOR + step * RADIUS_STEP)));
        this.addRenderableWidget(new UiSlider(
                x,
                sliders + UiSlider.HEIGHT + 5,
                width,
                Component.translatable("gui.cbc_more_content.siren.linger_label"),
                (SirenSettings.LINGER_CEILING - SirenSettings.LINGER_FLOOR) / LINGER_STEP,
                6,
                () -> Math.round((this.lingerSeconds - SirenSettings.LINGER_FLOOR) / (float) LINGER_STEP),
                step -> this.lingerSeconds = SirenSettings.LINGER_FLOOR + step * LINGER_STEP,
                step -> Component.translatable(
                        "gui.cbc_more_content.unit.seconds", SirenSettings.LINGER_FLOOR + step * LINGER_STEP)));
        this.addCancelApply(Component.translatable("gui.cbc_more_content.siren.apply"), this::confirm);
    }

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null
                || !(mc.level.getBlockState(this.pos).getBlock() instanceof com.cbc_more_content.block.SirenBlock)) {
            this.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {}

    @Override
    protected Component status() {
        return Component.translatable("gui.cbc_more_content.siren.hint");
    }

    @Override
    protected int statusColour() {
        return this.auto ? Ui.AMBER : Ui.CONTROL;
    }

    @Override
    protected int lampColour() {
        return this.auto ? Ui.AMBER : Ui.CONTROL;
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
        PacketDistributor.sendToServer(new SirenSettingsPayload(
                this.pos,
                new SirenSettings(this.auto, this.radius, this.lingerSeconds, this.watchMissiles, this.watchBombs)));
        UiSounds.confirm();
        this.onClose();
    }
}
