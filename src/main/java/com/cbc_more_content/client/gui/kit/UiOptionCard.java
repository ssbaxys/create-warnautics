package com.cbc_more_content.client.gui.kit;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * One answer of a radio choice, with a line saying what it does. A lamp on the left
 * lights on the chosen card, so the pick reads at a glance from across the screen.
 */
@OnlyIn(Dist.CLIENT)
public class UiOptionCard extends UiWidget {
    private final Component description;
    private final BooleanSupplier chosen;
    private final Runnable choose;

    public UiOptionCard(
            int x,
            int y,
            int width,
            int height,
            Component title,
            Component description,
            BooleanSupplier chosen,
            Runnable choose) {
        super(x, y, width, height, title);
        this.description = description;
        this.chosen = chosen;
        this.choose = choose;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        boolean on = this.chosen.getAsBoolean();
        Ui.well(g, x, y, this.width, this.height);
        if (on) {
            g.fill(x + 1, y + 2, x + this.width - 1, y + this.height - 1, Ui.mix(Ui.WELL, Ui.BRASS_SHADE, 0.35f));
        } else if (this.hot()) {
            g.fill(x + 1, y + 2, x + this.width - 1, y + this.height - 1, Ui.WELL_LINE);
        }
        Ui.lamp(g, x + 6, y + (this.height - 5) / 2, on ? Ui.AMBER : Ui.CONTROL, 0);
        int textX = x + 18;
        int room = this.width - 24;
        Ui.fitLeft(g, font(), this.getMessage(), textX, y + 4, room, on ? Ui.AMBER : Ui.TEXT, false);
        Ui.fitLeft(g, font(), this.description, textX, y + 14, room, Ui.TEXT_MUTED, false);
        if (this.keyboardFocused()) {
            Ui.focusRing(g, x, y, this.width, this.height);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.pick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            this.pick();
            return true;
        }
        return false;
    }

    private void pick() {
        if (!this.chosen.getAsBoolean()) {
            this.choose.run();
            UiSounds.press(1.3f);
        }
    }
}
