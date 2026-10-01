package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.network.Aim9SettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/** A readable interceptor panel: preview, two switches and a keyboard-accessible range control. */
public class Aim9SettingsScreen extends Screen {
    public static final int PANEL_WIDTH = 300;
    public static final int PANEL_HEIGHT = 222;
    private final BlockPos pos;
    private boolean enabled;
    private boolean interceptCruise;
    private int range;
    private int left, top;
    private float age, glow;

    public Aim9SettingsScreen(BlockPos pos, boolean enabled, boolean interceptCruise, int range) {
        super(Component.translatable("gui.cbc_more_content.aim9.title"));
        this.pos = pos;
        this.enabled = enabled;
        this.interceptCruise = interceptCruise;
        this.range = Mth.clamp(range, Aim9BlockEntity.MIN_RANGE, Aim9BlockEntity.MAX_RANGE);
        this.glow = enabled ? 1 : 0;
    }

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        addRenderableWidget(new SwitchButton(left + 112, top + 49, "power", () -> enabled, () -> enabled = !enabled));
        addRenderableWidget(new SwitchButton(
                left + 112, top + 91, "cruise", () -> interceptCruise, () -> interceptCruise = !interceptCruise));
        addRenderableWidget(new RangeSlider(left + 14, top + 159, 272));
        addRenderableWidget(Button.builder(Component.translatable("gui.cbc_more_content.aim9.apply"), button -> {
                    PacketDistributor.sendToServer(new Aim9SettingsPayload(pos, enabled, interceptCruise, range));
                    onClose();
                })
                .bounds(left + 156, top + 193, 130, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 14, top + 193, 130, 20)
                .build());
    }

    private static Component switchLabel(String label, boolean active) {
        return Component.translatable(
                "gui.cbc_more_content.aim9." + label,
                Component.translatable("gui.cbc_more_content.aim9." + (active ? "on" : "off")));
    }

    @Override
    public void tick() {
        age++;
        glow = Mth.lerp(.2f, glow, enabled && interceptCruise ? 1 : 0);
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof Aim9BlockEntity be) || !be.isLiveAirframe()) {
            onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {
        int alpha = (int) (100 * Mth.clamp((age + partial) / 6, 0, 1));
        graphics.fillGradient(0, 0, width, height, alpha << 24 | 0x10151C, alpha << 24 | 0x04070C);
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float partial) {
        renderBackground(g, x, y, partial);
        g.fill(left - 2, top - 2, left + PANEL_WIDTH + 2, top + PANEL_HEIGHT + 2, 0xFF97785A);
        g.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF15212B);
        g.fillGradient(left + 2, top + 2, left + PANEL_WIDTH - 2, top + 30, 0xFF354B57, 0xFF24333D);
        g.drawString(font, title, left + 14, top + 10, 0xF3E5C9, false);
        g.drawString(
                font,
                Component.translatable("gui.cbc_more_content.aim9.automatic"),
                left + 112,
                top + 36,
                0x92ADAF,
                false);
        g.drawString(
                font,
                Component.translatable("gui.cbc_more_content.aim9.targets"),
                left + 112,
                top + 78,
                0x92ADAF,
                false);
        g.drawWordWrap(
                font, Component.translatable("gui.cbc_more_content.aim9.hint"), left + 112, top + 119, 174, 0xBDC4BA);
        g.fill(left + 14, top + 143, left + 286, top + 144, 0xFF425158);
        g.drawString(
                font,
                Component.translatable("gui.cbc_more_content.aim9.radius", range),
                left + 14,
                top + 148,
                0xF3E5C9,
                false);
        preview(g, partial);
        super.render(g, x, y, partial);
    }

    private void preview(GuiGraphics g, float partial) {
        int cx = left + 58, cy = top + 86;
        g.fill(left + 13, top + 38, left + 103, top + 137, 0xFF0B141C);
        for (int radius : new int[] {15, 29, 41}) {
            for (int i = 0; i < 96; i++) {
                double angle = i * Math.PI * 2 / 96;
                int px = cx + (int) (Math.cos(angle) * radius);
                int py = cy + (int) (Math.sin(angle) * radius);
                g.fill(px, py, px + 1, py + 1, 0xFF344A4D);
            }
        }
        double sweep = (age + partial) * .045;
        for (int i = 0; i < 40; i++) {
            int px = cx + (int) (Math.cos(sweep) * i), py = cy + (int) (Math.sin(sweep) * i);
            g.fill(px, py, px + 1, py + 1, 0xFF3F655F);
        }
        int green = Mth.color(.28f + .22f * glow, .38f + .4f * glow, .37f + .22f * glow) | 0xFF000000;
        g.fill(cx - 2, cy - 7, cx + 2, cy + 5, 0xFFEAD9B7);
        g.fill(cx - 5, cy + 2, cx + 5, cy + 5, 0xFF97785A);
        int tx = cx + 23, ty = cy - 19;
        g.fill(tx - 3, ty - 2, tx + 4, ty + 1, green);
        g.fill(tx - 1, ty - 4, tx + 1, ty + 3, green);
        g.drawCenteredString(
                font,
                Component.translatable(
                        "gui.cbc_more_content.aim9." + (enabled && interceptCruise ? "ready" : "standby")),
                cx,
                top + 124,
                green & 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class SwitchButton extends Button {
        private final String label;
        private final java.util.function.BooleanSupplier state;
        private float progress;
        private long previousFrameNanos;

        SwitchButton(int x, int y, String label, java.util.function.BooleanSupplier state, Runnable toggle) {
            super(
                    x,
                    y,
                    174,
                    22,
                    switchLabel(label, state.getAsBoolean()),
                    button -> {
                        toggle.run();
                        button.setMessage(switchLabel(label, state.getAsBoolean()));
                    },
                    DEFAULT_NARRATION);
            this.label = label;
            this.state = state;
            progress = state.getAsBoolean() ? 1 : 0;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            long now = System.nanoTime();
            double seconds = previousFrameNanos == 0 ? 1.0 / 60 : Math.min(.1, (now - previousFrameNanos) * 1e-9);
            previousFrameNanos = now;
            float smoothing = 1 - (float) Math.exp(-seconds / .08);
            progress = Mth.lerp(smoothing, progress, state.getAsBoolean() ? 1 : 0);
            int x = getX(), y = getY();
            g.fill(x, y, x + width, y + height, isHoveredOrFocused() ? 0xFFAD9874 : 0xFF4B5E67);
            g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF1A2A35);
            g.drawString(
                    font,
                    Component.translatable("gui.cbc_more_content.aim9." + label + "_label"),
                    x + 7,
                    y + 7,
                    state.getAsBoolean() ? 0xE8E1CF : 0x93A1A6,
                    false);
            int trackX = x + width - 41;
            g.fill(trackX, y + 5, trackX + 33, y + 17, state.getAsBoolean() ? 0xFF497E6C : 0xFF0C151C);
            int knobX = trackX + 2 + Math.round(progress * 19);
            g.fill(knobX, y + 6, knobX + 10, y + 16, 0xFFE4D7B8);
        }
    }

    private final class RangeSlider extends AbstractSliderButton {
        RangeSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), (range - Aim9BlockEntity.MIN_RANGE) / 180.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.cbc_more_content.aim9.radius", range));
        }

        @Override
        protected void applyValue() {
            range = Aim9BlockEntity.MIN_RANGE + (int) Math.round(value * 180);
        }

        @Override
        public void renderWidget(GuiGraphics g, int x, int y, float partial) {
            int knob = getX() + 4 + (int) Math.round(value * (width - 8));
            g.fill(getX(), getY() + 8, getX() + width, getY() + 11, 0xFF081119);
            g.fill(getX(), getY() + 8, knob, getY() + 11, 0xFF729E90);
            for (int i = 0; i <= 6; i++) {
                int tick = getX() + i * (width - 1) / 6;
                g.fill(tick, getY() + 12, tick + 1, getY() + 15, 0xFF465963);
            }
            g.fill(knob - 4, getY() + 4, knob + 4, getY() + 16, isHoveredOrFocused() ? 0xFFF4E6C5 : 0xFFC5B593);
            g.drawString(font, "40", getX(), getY() + 20, 0x829399, false);
            g.drawString(font, "220", getX() + width - font.width("220"), getY() + 20, 0x829399, false);
        }
    }
}
