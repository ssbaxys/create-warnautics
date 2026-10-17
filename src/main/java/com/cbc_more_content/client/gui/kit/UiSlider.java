package com.cbc_more_content.client.gui.kit;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * A stepped slider: the label printed on the case with the value in amber at the right,
 * then a recessed track that fills in brass up to the knob. Works on whole steps, which
 * the owning screen maps onto its own units, so every slider in the mod detents the
 * same way — by drag, by the wheel, or by the arrow keys (Shift for ten at a time).
 */
@OnlyIn(Dist.CLIENT)
public class UiSlider extends UiWidget {
    private static final int TRACK_Y = 12;
    private static final int KNOB_W = 7;
    private static final int KNOB_H = 11;
    public static final int HEIGHT = 25;

    private final Component label;
    private final int steps;
    private final IntSupplier current;
    private final IntConsumer set;
    private final IntFunction<Component> valueText;
    private final int ticks;

    /**
     * @param steps     number of positions minus one; the value runs 0..steps
     * @param ticks     scale marks under the track, 0 for none
     */
    public UiSlider(
            int x,
            int y,
            int width,
            Component label,
            int steps,
            int ticks,
            IntSupplier current,
            IntConsumer set,
            IntFunction<Component> valueText) {
        super(x, y, width, HEIGHT, label);
        this.label = label;
        this.steps = Math.max(1, steps);
        this.ticks = ticks;
        this.current = current;
        this.set = set;
        this.valueText = valueText;
    }

    private float progress() {
        return Mth.clamp(this.current.getAsInt() / (float) this.steps, 0, 1);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        Component value = this.valueText.apply(this.current.getAsInt());
        int valueWidth = Math.min(font().width(value), this.width / 2);
        Ui.caseLabel(g, font(), this.label, x, y, this.width - valueWidth - 6);
        Ui.fitRight(g, font(), value, x + this.width, y, this.width / 2, Ui.AMBER, true);

        int ty = y + TRACK_Y;
        int travel = this.width - KNOB_W;
        int knob = x + Math.round(this.progress() * travel);
        Ui.well(g, x, ty + 3, this.width, 6);
        g.fill(x + 1, ty + 5, knob + KNOB_W / 2, ty + 7, Ui.BRASS_SHADE);
        g.fill(x + 1, ty + 5, knob + KNOB_W / 2, ty + 6, Ui.BRASS);
        if (this.ticks > 0) {
            for (int i = 0; i <= this.ticks; i++) {
                int tick = x + KNOB_W / 2 + Math.round(i * (travel) / (float) this.ticks);
                g.fill(tick, ty + 10, tick + 1, ty + 12, Ui.CASE_SHADE);
            }
        }
        Ui.brassPlate(g, knob, ty, KNOB_W, KNOB_H, this.hot() ? Ui.Face.HOT : Ui.Face.NORMAL);
        g.fill(knob + 3, ty + 3, knob + 4, ty + KNOB_H - 3, Ui.BRASS_SHADE);
        if (this.keyboardFocused()) {
            Ui.focusRing(g, x, y, this.width, this.height);
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // Only the track takes the mouse; the label line is just print.
        return this.active
                && this.visible
                && mouseX >= this.getX() - 2
                && mouseX < this.getX() + this.width + 2
                && mouseY >= this.getY() + TRACK_Y - 2
                && mouseY < this.getY() + this.height;
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return this.isMouseOver(mouseX, mouseY);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.slideTo(mouseX);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        this.slideTo(mouseX);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        this.step(scrollY > 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int stride = Screen.hasShiftDown() ? 10 : 1;
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_DOWN) {
            this.step(-stride);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP) {
            this.step(stride);
            return true;
        }
        return false;
    }

    private void slideTo(double mouseX) {
        float t = (float) ((mouseX - this.getX() - KNOB_W / 2.0) / (this.width - KNOB_W));
        this.apply(Math.round(Mth.clamp(t, 0, 1) * this.steps));
    }

    private void step(int delta) {
        this.apply(this.current.getAsInt() + delta);
    }

    private void apply(int value) {
        int clamped = Mth.clamp(value, 0, this.steps);
        if (clamped == this.current.getAsInt()) {
            return;
        }
        this.set.accept(clamped);
        UiSounds.detent(this.progress());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(
                NarratedElementType.TITLE,
                CommonComponents.optionNameValue(this.label, this.valueText.apply(this.current.getAsInt())));
    }
}
