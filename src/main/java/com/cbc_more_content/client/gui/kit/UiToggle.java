package com.cbc_more_content.client.gui.kit;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * A labelled on/off switch: a row in a well, the label on the left and a sliding toggle
 * on the right whose track lights amber when it is on. The whole row is the hit area.
 * <p>
 * A row can be "dead" — present and readable, but not operable, because something else
 * on the screen makes it irrelevant. It stays where it is rather than vanishing, so the
 * setting is somewhere to come back to.
 */
@OnlyIn(Dist.CLIENT)
public class UiToggle extends UiWidget {
    private static final int TRACK_W = 26;
    private static final int TRACK_H = 12;
    private static final int KNOB_W = 12;

    private final Supplier<Component> label;
    private final BooleanSupplier state;
    private final Runnable toggle;
    private final BooleanSupplier live;
    private float slide;
    private long lastFrame;

    public UiToggle(
            int x,
            int y,
            int width,
            Supplier<Component> label,
            BooleanSupplier state,
            Runnable toggle,
            BooleanSupplier live) {
        super(x, y, width, Ui.ROW_H, label.get());
        this.label = label;
        this.state = state;
        this.toggle = toggle;
        this.live = live;
        this.slide = state.getAsBoolean() ? 1 : 0;
    }

    public UiToggle(int x, int y, int width, Supplier<Component> label, BooleanSupplier state, Runnable toggle) {
        this(x, y, width, label, state, toggle, () -> true);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.active = this.live.getAsBoolean();
        boolean on = this.state.getAsBoolean();
        long now = System.nanoTime();
        float seconds = this.lastFrame == 0 ? 1 : Math.min(.1f, (now - this.lastFrame) * 1e-9f);
        this.lastFrame = now;
        this.slide = Mth.lerp(1 - (float) Math.exp(-seconds / .06f), this.slide, on ? 1 : 0);

        int x = this.getX();
        int y = this.getY();
        Ui.well(g, x, y, this.width, this.height);
        if (this.hot()) {
            g.fill(x + 1, y + 2, x + this.width - 1, y + this.height - 1, Ui.WELL_LINE);
        }
        Component text = this.label.get();
        this.setMessage(text);
        int colour = !this.active ? Ui.TEXT_DIM : on ? Ui.TEXT : Ui.TEXT_MUTED;
        Ui.fitLeft(g, font(), text, x + 6, y + 5, this.width - TRACK_W - 26, colour, false);

        int tx = x + this.width - TRACK_W - 4;
        int ty = y + (this.height - TRACK_H) / 2;
        // Lamp beside the switch: the kit's one way of saying "this is live".
        Ui.lamp(g, tx - 11, ty + 3, on && this.active ? Ui.AMBER : Ui.CONTROL, 0);
        g.fill(tx, ty, tx + TRACK_W, ty + TRACK_H, Ui.OUTLINE);
        int lit = this.active ? Ui.mix(Ui.CONTROL_SHADE, Ui.BRASS, this.slide) : Ui.CONTROL_SHADE;
        g.fill(tx + 1, ty + 1, tx + TRACK_W - 1, ty + TRACK_H - 1, lit);
        g.fill(tx + 1, ty + 1, tx + TRACK_W - 1, ty + 2, Ui.darken(lit, 0.7f));
        int knob = tx + 1 + Math.round(this.slide * (TRACK_W - KNOB_W - 2));
        Ui.plate(
                g,
                knob,
                ty + 1,
                KNOB_W,
                TRACK_H - 2,
                this.active ? (this.hot() ? Ui.Face.HOT : Ui.Face.NORMAL) : Ui.Face.DEAD);
        g.fill(knob + 4, ty + 3, knob + 5, ty + TRACK_H - 3, Ui.CONTROL_SHADE);
        g.fill(knob + 7, ty + 3, knob + 8, ty + TRACK_H - 3, Ui.CONTROL_SHADE);
        if (this.keyboardFocused()) {
            Ui.focusRing(g, x, y, this.width, this.height);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.flip();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.active
                && (keyCode == GLFW.GLFW_KEY_SPACE
                        || keyCode == GLFW.GLFW_KEY_ENTER
                        || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            this.flip();
            return true;
        }
        return false;
    }

    @Override
    public boolean handlesEnter() {
        return true;
    }

    private void flip() {
        this.toggle.run();
        UiSounds.toggle(this.state.getAsBoolean());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(
                NarratedElementType.TITLE,
                CommonComponents.optionNameValue(
                        this.label.get(), CommonComponents.optionStatus(this.state.getAsBoolean())));
    }
}
