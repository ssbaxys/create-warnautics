package com.cbc_more_content.client.gui.kit;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * A push button. Three kinds, and every screen uses them the same way: brass for the one
 * action the screen exists for, plain for backing out, alarm red for an action that arms
 * or fires something and cannot be undone.
 */
@OnlyIn(Dist.CLIENT)
public class UiButton extends UiWidget {
    public enum Kind {
        PRIMARY,
        SECONDARY,
        ALARM
    }

    private final Kind kind;
    private final Supplier<Component> label;
    private final Runnable onPress;
    private final BooleanSupplier enabled;
    private long pressedAt;

    public UiButton(int x, int y, int width, Kind kind, Supplier<Component> label, Runnable onPress) {
        this(x, y, width, Ui.BUTTON_H, kind, label, onPress, () -> true);
    }

    public UiButton(
            int x,
            int y,
            int width,
            int height,
            Kind kind,
            Supplier<Component> label,
            Runnable onPress,
            BooleanSupplier enabled) {
        super(x, y, width, height, label.get());
        this.kind = kind;
        this.label = label;
        this.onPress = onPress;
        this.enabled = enabled;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.active = this.enabled.getAsBoolean();
        Component text = this.label.get();
        this.setMessage(text);
        boolean pressed = System.currentTimeMillis() - this.pressedAt < 110;
        Ui.Face face =
                !this.active ? Ui.Face.DEAD : pressed ? Ui.Face.PRESSED : this.hot() ? Ui.Face.HOT : Ui.Face.NORMAL;
        int x = this.getX();
        int y = this.getY();
        switch (this.kind) {
            case PRIMARY -> Ui.brassPlate(g, x, y, this.width, this.height, face);
            case ALARM -> Ui.alarmPlate(g, x, y, this.width, this.height, face);
            default -> Ui.plate(g, x, y, this.width, this.height, face);
        }
        int colour;
        if (!this.active) {
            colour = Ui.TEXT_DIM;
        } else if (this.kind == Kind.PRIMARY) {
            colour = Ui.TEXT_ON_BRASS;
        } else {
            colour = this.hot() ? Ui.AMBER : Ui.TEXT;
        }
        int textY = y + (this.height - 8) / 2 + (pressed ? 1 : 0);
        Ui.fitCentered(
                g,
                font(),
                text,
                x + this.width / 2,
                textY,
                this.width - 8,
                colour,
                this.active && this.kind != Kind.PRIMARY);
        if (this.keyboardFocused()) {
            Ui.focusRing(g, x, y, this.width, this.height);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.press();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.active
                && (keyCode == GLFW.GLFW_KEY_SPACE
                        || keyCode == GLFW.GLFW_KEY_ENTER
                        || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            this.press();
            return true;
        }
        return false;
    }

    @Override
    public boolean handlesEnter() {
        return true;
    }

    private void press() {
        if (!this.enabled.getAsBoolean()) {
            UiSounds.deny();
            return;
        }
        this.pressedAt = System.currentTimeMillis();
        this.onPress.run();
    }
}
