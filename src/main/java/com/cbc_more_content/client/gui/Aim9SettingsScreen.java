package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.UiToggle;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.Aim9SettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The interceptor rack: a live scope on the left, the two switches that decide whether
 * it fires and at what, and the acquisition radius under both.
 */
@OnlyIn(Dist.CLIENT)
public class Aim9SettingsScreen extends WarnauticsScreen {
    public static final int PANEL_WIDTH = 248;
    public static final int PANEL_HEIGHT = 196;
    /** Layout, public so the client check can operate the real controls. */
    public static final int SCOPE = 92;

    public static final int COLUMN_X = Ui.MARGIN + SCOPE + 8;
    public static final int COLUMN_W = PANEL_WIDTH - COLUMN_X - Ui.MARGIN;
    public static final int POWER_Y = 32;
    public static final int CRUISE_Y = 66;
    public static final int SLIDER_X = Ui.MARGIN;
    public static final int SLIDER_Y = 126;
    public static final int SLIDER_W = PANEL_WIDTH - Ui.MARGIN * 2;

    private final BlockPos pos;
    private boolean enabled;
    private boolean interceptCruise;
    private int range;
    private float glow;
    private boolean sent;
    /** What the server last said about the rack; null until it has answered. */
    private com.cbc_more_content.block.Aim9Block.Readiness live;

    public Aim9SettingsScreen(BlockPos pos, boolean enabled, boolean interceptCruise, int range) {
        super(Component.translatable("gui.cbc_more_content.aim9.title"), PANEL_WIDTH, PANEL_HEIGHT);
        this.pos = pos;
        this.enabled = enabled;
        this.interceptCruise = interceptCruise;
        this.range = Mth.clamp(range, Aim9BlockEntity.MIN_RANGE, Aim9BlockEntity.MAX_RANGE);
        this.glow = enabled ? 1 : 0;
    }

    /** Applies the click point the check needs to land on a given radius. */
    public static double sliderX(int left, int range) {
        double t =
                (range - Aim9BlockEntity.MIN_RANGE) / (double) (Aim9BlockEntity.MAX_RANGE - Aim9BlockEntity.MIN_RANGE);
        return left + SLIDER_X + 3.5 + t * (SLIDER_W - 7);
    }

    @Override
    protected void build() {
        this.addRenderableWidget(new UiToggle(
                this.x(COLUMN_X),
                this.y(POWER_Y),
                COLUMN_W,
                () -> Component.translatable("gui.cbc_more_content.aim9.power_label"),
                () -> this.enabled,
                () -> this.enabled = !this.enabled));
        this.addRenderableWidget(new UiToggle(
                this.x(COLUMN_X),
                this.y(CRUISE_Y),
                COLUMN_W,
                () -> Component.translatable("gui.cbc_more_content.aim9.cruise_label"),
                () -> this.interceptCruise,
                () -> this.interceptCruise = !this.interceptCruise));
        this.addRenderableWidget(new UiSlider(
                this.x(SLIDER_X),
                this.y(SLIDER_Y),
                SLIDER_W,
                Component.translatable("gui.cbc_more_content.aim9.radius_label"),
                Aim9BlockEntity.MAX_RANGE - Aim9BlockEntity.MIN_RANGE,
                6,
                () -> this.range - Aim9BlockEntity.MIN_RANGE,
                step -> this.range = Aim9BlockEntity.MIN_RANGE + step,
                step -> Component.translatable("gui.cbc_more_content.unit.blocks", Aim9BlockEntity.MIN_RANGE + step)));
        this.addCancelApply(Component.translatable("gui.cbc_more_content.aim9.apply"), this::apply);
    }

    /** Footer button centres, for the client check. */
    public static double applyX(int left) {
        return left + PANEL_WIDTH - Ui.MARGIN - 39;
    }

    public static double footerCentreY(int top) {
        return top + PANEL_HEIGHT - Ui.STATUS_H - 6 - Ui.BUTTON_H - 5 + Ui.BUTTON_H / 2.0;
    }

    /** The server's answer: the rack's readiness as it stands right now. */
    public void onStatus(com.cbc_more_content.network.Aim9StatusPayload payload) {
        var all = com.cbc_more_content.block.Aim9Block.Readiness.values();
        if (!payload.pos().equals(this.pos) || payload.readiness() < 0 || payload.readiness() >= all.length) {
            return;
        }
        if (this.live == null) {
            // The first answer also carries the rack's real settings: on a ship the client's
            // copy can be out of date, and the switches should open showing the truth.
            this.enabled = payload.enabled();
            this.interceptCruise = payload.interceptCruise();
            this.range = Mth.clamp(payload.range(), Aim9BlockEntity.MIN_RANGE, Aim9BlockEntity.MAX_RANGE);
        }
        this.live = all[payload.readiness()];
    }

    @Override
    public void tick() {
        super.tick();
        // Ask every half second while the panel is open, so a ship turned over or a roof
        // built over the rack shows up at once.
        if (((int) this.age) % 10 == 0) {
            PacketDistributor.sendToServer(com.cbc_more_content.network.Aim9StatusPayload.ask(this.pos));
        }
        this.glow = Mth.lerp(.2f, this.glow, this.enabled && this.interceptCruise ? 1 : 0);
        var mc = Minecraft.getInstance();
        if (mc.level == null
                || !(mc.level.getBlockEntity(this.pos) instanceof Aim9BlockEntity be)
                || !be.isLiveAirframe()) {
            this.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick, float now) {
        Ui.caseLabel(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.aim9.automatic"),
                this.x(COLUMN_X),
                this.y(POWER_Y - 10),
                COLUMN_W);
        Ui.caseLabel(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.aim9.targets"),
                this.x(COLUMN_X),
                this.y(CRUISE_Y - 10),
                COLUMN_W);
        Ui.paragraph(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.aim9.hint"),
                this.x(COLUMN_X),
                this.y(CRUISE_Y + Ui.ROW_H + 5),
                COLUMN_W,
                3,
                Ui.TEXT,
                true);
        this.scope(g, now);
    }

    /** The scope: range rings, a sweeping beam, the rack in the centre and a contact. */
    private void scope(GuiGraphics g, float now) {
        int x0 = this.x(Ui.MARGIN);
        int y0 = this.y(this.contentTop());
        Ui.well(g, x0, y0, SCOPE, SCOPE);
        int cx = x0 + SCOPE / 2;
        int cy = y0 + SCOPE / 2 - 4;
        boolean live = this.enabled;
        int ring = live ? Ui.PHOSPHOR_DIM : Ui.WELL_LINE;
        for (int radius : new int[] {13, 25, 37}) {
            for (int i = 0; i < 96; i++) {
                double angle = i * Math.PI * 2 / 96;
                int px = cx + (int) Math.round(Math.cos(angle) * radius);
                int py = cy + (int) Math.round(Math.sin(angle) * radius);
                g.fill(px, py, px + 1, py + 1, ring);
            }
        }
        g.fill(cx - 37, cy, cx + 38, cy + 1, ring);
        g.fill(cx, cy - 37, cx + 1, cy + 38, ring);
        if (live) {
            // The beam leaves a fading trail behind it, the way a phosphor scope does.
            double sweep = now * .06;
            for (int trail = 0; trail < 10; trail++) {
                double angle = sweep - trail * .05;
                int colour = Ui.withAlpha(Ui.PHOSPHOR, (1 - trail / 10f) * .8f);
                for (int i = 2; i < 38; i++) {
                    int px = cx + (int) Math.round(Math.cos(angle) * i);
                    int py = cy + (int) Math.round(Math.sin(angle) * i);
                    g.fill(px, py, px + 1, py + 1, colour);
                }
            }
        }
        // The rack itself, in brass.
        g.fill(cx - 1, cy - 4, cx + 2, cy + 4, Ui.BRASS_LIGHT);
        g.fill(cx - 3, cy + 2, cx + 4, cy + 4, Ui.BRASS);
        // A contact, lit only when the rack would actually engage it.
        int contact = Ui.mix(Ui.WELL_LINE, Ui.AMBER, this.glow);
        int tx = cx + 19;
        int ty = cy - 15;
        g.fill(tx - 2, ty - 1, tx + 3, ty + 2, contact);
        g.fill(tx - 1, ty - 2, tx + 2, ty + 3, contact);
        Component state = Component.translatable(
                "gui.cbc_more_content.aim9." + (this.enabled && this.interceptCruise ? "ready" : "standby"));
        Ui.fitCentered(
                g,
                this.font,
                state,
                cx,
                y0 + SCOPE - 11,
                SCOPE - 6,
                this.enabled && this.interceptCruise ? Ui.PHOSPHOR : Ui.TEXT_DIM,
                false);
    }

    /** What the rack itself last reported, so a silent rack says why it is silent. */
    private com.cbc_more_content.block.Aim9Block.Readiness readiness() {
        if (this.live != null) {
            return this.live;
        }
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(this.pos) instanceof Aim9BlockEntity be
                ? be.readiness()
                : com.cbc_more_content.block.Aim9Block.Readiness.READY;
    }

    @Override
    protected Component status() {
        if (!this.enabled) {
            return Component.translatable("gui.cbc_more_content.aim9.status.off");
        }
        var readiness = this.readiness();
        if (readiness != com.cbc_more_content.block.Aim9Block.Readiness.READY) {
            return Component.translatable(
                    "gui.cbc_more_content.aim9.status." + readiness.name().toLowerCase(java.util.Locale.ROOT));
        }
        return Component.translatable(
                this.interceptCruise
                        ? "gui.cbc_more_content.aim9.status.live"
                        : "gui.cbc_more_content.aim9.status.no_targets",
                this.range);
    }

    @Override
    protected int statusColour() {
        if (this.enabled && this.readiness() != com.cbc_more_content.block.Aim9Block.Readiness.READY) {
            return Ui.RED;
        }
        return this.enabled ? Ui.PHOSPHOR : Ui.CONTROL;
    }

    @Override
    protected int lampColour() {
        return this.enabled ? Ui.GREEN : Ui.CONTROL;
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
        PacketDistributor.sendToServer(
                new Aim9SettingsPayload(this.pos, this.enabled, this.interceptCruise, this.range));
        UiSounds.confirm();
        this.onClose();
    }
}
