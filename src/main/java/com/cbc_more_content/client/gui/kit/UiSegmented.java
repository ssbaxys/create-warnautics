package com.cbc_more_content.client.gui.kit;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * A row of mutually exclusive choices in one well — a selector switch rather than a
 * set of buttons. The chosen segment stands up as a raised plate with an amber legend;
 * the others lie flat in the well. Left and right step through it from the keyboard.
 */
@OnlyIn(Dist.CLIENT)
public class UiSegmented extends UiWidget {
    private final List<Component> options;
    private final IntSupplier selected;
    private final IntConsumer select;

    public UiSegmented(
            int x, int y, int width, int height, List<Component> options, IntSupplier selected, IntConsumer select) {
        super(x, y, width, height, options.get(Math.max(0, selected.getAsInt())));
        this.options = options;
        this.selected = selected;
        this.select = select;
    }

    private int segmentX(int index) {
        int inner = this.width - 2;
        return this.getX() + 1 + Math.round(index * inner / (float) this.options.size());
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        Ui.well(g, x, y, this.width, this.height);
        int chosen = this.selected.getAsInt();
        for (int i = 0; i < this.options.size(); i++) {
            int sx = this.segmentX(i);
            int sw = this.segmentX(i + 1) - sx;
            boolean over = this.isHovered() && mouseX >= sx && mouseX < sx + sw;
            int colour;
            if (i == chosen) {
                Ui.plate(g, sx + 1, y + 1, sw - 2, this.height - 3, Ui.Face.HOT);
                g.fill(sx + 4, y + this.height - 5, sx + sw - 4, y + this.height - 4, Ui.AMBER);
                colour = Ui.AMBER;
            } else {
                if (over) {
                    g.fill(sx + 1, y + 2, sx + sw - 1, y + this.height - 2, Ui.WELL_LINE);
                }
                if (i > 0 && i != chosen + 1) {
                    g.fill(sx, y + 4, sx + 1, y + this.height - 4, Ui.WELL_LINE);
                }
                colour = over ? Ui.TEXT : Ui.TEXT_MUTED;
            }
            Ui.fitCentered(
                    g,
                    font(),
                    this.options.get(i),
                    sx + sw / 2,
                    y + (this.height - 8) / 2 - (i == chosen ? 1 : 0),
                    sw - 6,
                    colour,
                    i == chosen);
        }
        if (this.keyboardFocused()) {
            Ui.focusRing(g, x, y, this.width, this.height);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        for (int i = 0; i < this.options.size(); i++) {
            if (mouseX >= this.segmentX(i) && mouseX < this.segmentX(i + 1)) {
                this.choose(i);
                return;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int chosen = this.selected.getAsInt();
        if (keyCode == GLFW.GLFW_KEY_LEFT && chosen > 0) {
            this.choose(chosen - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT && chosen < this.options.size() - 1) {
            this.choose(chosen + 1);
            return true;
        }
        return false;
    }

    private void choose(int index) {
        if (index == this.selected.getAsInt()) {
            return;
        }
        this.select.accept(index);
        UiSounds.press(1.2f + index * 0.1f);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(
                NarratedElementType.TITLE,
                CommonComponents.joinForNarration(this.options.get(this.selected.getAsInt())));
    }
}
