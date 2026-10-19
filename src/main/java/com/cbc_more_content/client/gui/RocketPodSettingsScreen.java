package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.RocketPodBlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.network.RocketPodSettingsPayload;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The rocket pod's firing panel: how long between one rocket and the next, and how many go
 * on one redstone pulse. On the left the tubes light up in the order and at the pace the
 * salvo will actually fire, so the rhythm can be seen before it is set.
 */
@OnlyIn(Dist.CLIENT)
public class RocketPodSettingsScreen extends WarnauticsScreen {
    public static final int PANEL_WIDTH = 248;
    public static final int PANEL_HEIGHT = 224;
    public static final int PREVIEW = 92;
    public static final int COLUMN_X = Ui.MARGIN + PREVIEW + 8;
    public static final int COLUMN_W = PANEL_WIDTH - COLUMN_X - Ui.MARGIN;
    public static final int SLIDER_X = Ui.MARGIN;
    public static final int SLIDER_W = PANEL_WIDTH - Ui.MARGIN * 2;
    public static final int RIPPLE_Y = 120;
    public static final int SALVO_Y = 148;
    private static final int TUBES = RocketPodBlockEntity.TUBES;

    private final BlockPos pos;
    private final int loaded;
    private int ripple;
    private int salvo;
    private boolean sent;

    public RocketPodSettingsScreen(BlockPos pos, int ripple, int salvo, int loaded) {
        super(Component.translatable("gui.cbc_more_content.rocket_pod.title"), PANEL_WIDTH, PANEL_HEIGHT);
        this.pos = pos;
        this.ripple = Mth.clamp(ripple, RocketPodBlockEntity.MIN_RIPPLE, RocketPodBlockEntity.MAX_RIPPLE);
        this.salvo = Mth.clamp(salvo, 0, capacity());
        this.loaded = loaded;
    }

    private static int capacity() {
        return WarnauticsConfig.rocketPodCapacity();
    }

    @Override
    protected void build() {
        this.addRenderableWidget(new UiSlider(
                this.x(SLIDER_X),
                this.y(RIPPLE_Y),
                SLIDER_W,
                Component.translatable("gui.cbc_more_content.rocket_pod.ripple_label"),
                RocketPodBlockEntity.MAX_RIPPLE - RocketPodBlockEntity.MIN_RIPPLE,
                9,
                () -> this.ripple - RocketPodBlockEntity.MIN_RIPPLE,
                step -> this.ripple = RocketPodBlockEntity.MIN_RIPPLE + step,
                step -> rippleText(RocketPodBlockEntity.MIN_RIPPLE + step)));
        this.addRenderableWidget(new UiSlider(
                this.x(SLIDER_X),
                this.y(SALVO_Y),
                SLIDER_W,
                Component.translatable("gui.cbc_more_content.rocket_pod.salvo_label"),
                capacity(),
                Math.min(capacity() + 1, 9),
                () -> this.salvo,
                step -> this.salvo = step,
                RocketPodSettingsScreen::salvoText));
        this.addCancelApply(Component.translatable("gui.cbc_more_content.rocket_pod.apply"), this::apply);
    }

    private static Component rippleText(int ticks) {
        if (ticks <= 0) {
            return Component.translatable("gui.cbc_more_content.rocket_pod.ripple_at_once");
        }
        return Component.translatable(
                "gui.cbc_more_content.rocket_pod.ripple_value",
                String.format(Locale.ROOT, "%.2f", ticks / 20.0D),
                ticks);
    }

    private static Component salvoText(int count) {
        return count <= 0
                ? Component.translatable("gui.cbc_more_content.rocket_pod.salvo_all")
                : Component.translatable("gui.cbc_more_content.rocket_pod.salvo_value", count);
    }

    /** Rockets this pulse will actually send with what is loaded now. */
    private int firing() {
        return this.salvo <= 0 ? this.loaded : Math.min(this.salvo, this.loaded);
    }

    @Override
    public void tick() {
        super.tick();
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(this.pos) instanceof RocketPodBlockEntity)) {
            this.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick, float now) {
        this.preview(g, now);
        this.magazine(g);
    }

    /**
     * The magazine, as a dark display like the other panels' readouts: the count large, and
     * under it one rocket per place in the pod, lit for each one loaded.
     */
    private void magazine(GuiGraphics g) {
        int x = this.x(COLUMN_X);
        int y = this.y(this.contentTop());
        int cx = x + COLUMN_W / 2;
        Ui.well(g, x, y, COLUMN_W, PREVIEW);
        Ui.fitCentered(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.rocket_pod.loaded_label"),
                cx,
                y + 6,
                COLUMN_W - 8,
                Ui.TEXT_MUTED,
                false);
        Ui.readout(
                g,
                this.font,
                Component.literal(this.loaded + " / " + capacity()),
                cx,
                y + 28,
                COLUMN_W - 16,
                this.loaded > 0 ? Ui.AMBER : Ui.TEXT_DIM);
        int capacity = capacity();
        if (capacity <= 16) {
            int perRow = Math.min(capacity, 8);
            int rows = (capacity + perRow - 1) / perRow;
            int pitch = 10;
            int rowTop = y + (rows == 1 ? 50 : 44);
            for (int i = 0; i < capacity; i++) {
                int row = i / perRow;
                int inRow = Math.min(perRow, capacity - row * perRow);
                int left = cx - (inRow * pitch - 3) / 2;
                this.rocketPip(g, left + (i % perRow) * pitch, rowTop + row * 16, i < this.loaded);
            }
        } else {
            // Too many places to draw one by one: a filling gauge instead.
            int barX = x + 10;
            int barW = COLUMN_W - 20;
            int barY = y + 52;
            g.fill(barX, barY, barX + barW, barY + 6, Ui.WELL_LINE);
            int filled = Math.round(barW * this.loaded / (float) capacity);
            g.fill(barX, barY, barX + filled, barY + 6, Ui.AMBER);
        }
        Ui.fitCentered(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.rocket_pod.ammo"),
                cx,
                y + PREVIEW - 12,
                COLUMN_W - 8,
                Ui.TEXT_DIM,
                false);
    }

    /** One rocket standing nose up: lit amber when loaded, a dark outline when not. */
    private void rocketPip(GuiGraphics g, int x, int y, boolean loaded) {
        int body = loaded ? Ui.AMBER : Ui.WELL_LINE;
        int shade = loaded ? Ui.BRASS : Ui.WELL_LINE;
        g.fill(x + 2, y, x + 5, y + 2, shade);
        g.fill(x + 1, y + 2, x + 6, y + 10, body);
        g.fill(x, y + 9, x + 7, y + 12, shade);
    }

    /**
     * The tube ring, firing in the order the pod uses, at the chosen pace. With nothing
     * loaded the ring stays dark; a salvo of fewer than eight leaves the rest dark too.
     */
    private void preview(GuiGraphics g, float now) {
        int x0 = this.x(Ui.MARGIN);
        int y0 = this.y(this.contentTop());
        Ui.well(g, x0, y0, PREVIEW, PREVIEW);
        int cx = x0 + PREVIEW / 2;
        int cy = y0 + PREVIEW / 2 - 4;
        int shots = this.firing();
        // One cycle: the salvo, then a pause before it repeats.
        float step = Math.max(1, this.ripple);
        float cycle = shots * step + 30;
        float t = shots == 0 ? -1 : now % cycle;
        for (int i = 0; i < TUBES; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2 / TUBES;
            int tx = cx + (int) Math.round(Math.cos(angle) * 28);
            int ty = cy + (int) Math.round(Math.sin(angle) * 28);
            Ui.roundWell(g, tx, ty, 7);
            int colour = Ui.WELL_LINE;
            if (i < Math.min(shots, TUBES) && t >= 0) {
                float fired = this.ripple <= 0 ? 0 : i * step;
                float since = t - fired;
                if (since >= 0) {
                    // A flash as it goes, then the tube glows down to dark.
                    float glow = Mth.clamp(1 - since / 14f, 0, 1);
                    colour = Ui.mix(Ui.WELL_LINE, Ui.AMBER, glow);
                } else {
                    colour = Ui.BRASS;
                }
            } else if (i < Math.min(this.loaded, TUBES)) {
                colour = Ui.BRASS;
            }
            Ui.disc(g, tx, ty, 5, colour);
        }
        Ui.fitCentered(
                g,
                this.font,
                Component.translatable(
                        "gui.cbc_more_content.rocket_pod.preview",
                        shots,
                        String.format(Locale.ROOT, "%.1f", Math.max(0, shots - 1) * Math.max(0, this.ripple) / 20.0D)),
                cx,
                y0 + PREVIEW - 11,
                PREVIEW - 6,
                shots > 0 ? Ui.AMBER : Ui.TEXT_DIM,
                false);
    }

    @Override
    protected Component status() {
        if (this.loaded <= 0) {
            return Component.translatable("gui.cbc_more_content.rocket_pod.status.empty");
        }
        return Component.translatable(
                "gui.cbc_more_content.rocket_pod.status.ready",
                this.firing(),
                String.format(Locale.ROOT, "%.1f", Math.max(0, this.firing() - 1) * this.ripple / 20.0D));
    }

    @Override
    protected int statusColour() {
        return this.loaded > 0 ? Ui.AMBER : Ui.TEXT_DIM;
    }

    @Override
    protected int lampColour() {
        return this.loaded > 0 ? Ui.AMBER : Ui.CONTROL;
    }

    @Override
    protected boolean primaryAction() {
        this.apply();
        return true;
    }

    private void apply() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new RocketPodSettingsPayload(this.pos, this.ripple, this.salvo));
        UiSounds.confirm();
        this.onClose();
    }
}
