package com.cbc_more_content.client.gui;

import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.ControlPanelPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * The server switchboard: one big lever, thrown for everybody at once.
 * <p>
 * Drawn as a physical switch rather than a checkbox, and deliberately large. It is not a
 * preference — throwing it changes what every player on the server sees the next time
 * anything goes off — and something with that reach should look like it takes a hand to
 * move, not like a tick box you might catch with the mouse on the way past.
 */
@OnlyIn(Dist.CLIENT)
public class ControlPanelScreen extends WarnauticsScreen {
    public static final int PANEL_W = 196;
    public static final int PANEL_H = 104;

    /** The switch plate. Most of the case, because it is most of what the case is. */
    private static final int SWITCH_X = 20;

    private static final int SWITCH_Y = 36;
    private static final int SWITCH_W = PANEL_W - SWITCH_X * 2;
    private static final int SWITCH_H = 32;
    /** The travelling part, half the plate wide, so its position reads as the state. */
    private static final int KNOB_W = 74;

    private static final int KNOB_INSET = 3;
    /** Ticks the lever takes to slide across. Long enough to be seen moving. */
    private static final float THROW_TICKS = 5.0f;

    private boolean cannonFx;
    private final boolean opened;
    /** 0 at the off end, 1 at the on end. Eased toward the state rather than snapped. */
    private float slide;

    private boolean sent;

    public ControlPanelScreen(boolean cannonFx) {
        super(Component.translatable("gui.cbc_more_content.panel.title"), PANEL_W, PANEL_H);
        this.cannonFx = cannonFx;
        this.opened = cannonFx;
        this.slide = cannonFx ? 1.0f : 0.0f;
    }

    @Override
    public void tick() {
        super.tick();
        this.slide = Mth.approach(this.slide, this.cannonFx ? 1.0f : 0.0f, 1.0f / THROW_TICKS);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        Ui.caseLabel(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.panel.cannon_fx"),
                this.x(SWITCH_X),
                this.y(this.contentTop()),
                SWITCH_W);
        this.renderSwitch(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSwitch(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.x(SWITCH_X);
        int y = this.y(SWITCH_Y);
        boolean hot = this.overSwitch(mouseX, mouseY);
        // Eased here as well as in tick, so the lever slides smoothly at any frame rate.
        float eased = Mth.approach(this.slide, this.cannonFx ? 1.0f : 0.0f, partialTick / THROW_TICKS);

        Ui.well(graphics, x, y, SWITCH_W, SWITCH_H);
        // The lit half fills in behind the lever as it travels, so the throw reads as
        // current arriving rather than as a shape sliding about.
        int litWidth = Math.round(eased * (SWITCH_W - 2));
        if (litWidth > 0) {
            graphics.fill(x + 1, y + 2, x + 1 + litWidth, y + SWITCH_H - 1, Ui.mix(Ui.WELL, Ui.BRASS_SHADE, 0.6f));
            graphics.fill(x + 1, y + SWITCH_H - 4, x + 1 + litWidth, y + SWITCH_H - 2, Ui.AMBER);
        }
        // Legends at both ends, under the lever's path.
        int third = (SWITCH_W - KNOB_W) / 2;
        Ui.fitCentered(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.panel.off"),
                x + SWITCH_W - third,
                y + SWITCH_H / 2 - 4,
                SWITCH_W - KNOB_W - 8,
                Ui.TEXT,
                true);
        Ui.fitCentered(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.panel.on"),
                x + third,
                y + SWITCH_H / 2 - 4,
                SWITCH_W - KNOB_W - 8,
                Ui.AMBER,
                true);

        int travel = SWITCH_W - KNOB_W - KNOB_INSET * 2;
        int knobX = x + KNOB_INSET + Math.round(eased * travel);
        int knobY = y + KNOB_INSET;
        int knobH = SWITCH_H - KNOB_INSET * 2;
        Ui.plate(graphics, knobX, knobY, KNOB_W, knobH, hot ? Ui.Face.HOT : Ui.Face.NORMAL);
        // Grip ribs, so the lever reads as something to take hold of; the legend it
        // uncovers says which way it is thrown.
        for (int rib = 0; rib < 7; rib++) {
            int ribX = knobX + KNOB_W / 2 - 9 + rib * 3;
            graphics.fill(ribX, knobY + 5, ribX + 1, knobY + knobH - 5, Ui.CONTROL_SHADE);
            graphics.fill(ribX + 1, knobY + 5, ribX + 2, knobY + knobH - 5, Ui.CONTROL_LIGHT);
        }
        if (this.focusedByKeyboard()) {
            Ui.focusRing(graphics, x, y, SWITCH_W, SWITCH_H);
        }
    }

    private boolean focusedByKeyboard() {
        return this.minecraft != null && this.minecraft.getLastInputType().isKeyboard();
    }

    @Override
    protected Component status() {
        return Component.translatable("gui.cbc_more_content.panel.scope");
    }

    @Override
    protected int statusColour() {
        return Ui.AMBER;
    }

    @Override
    protected int lampColour() {
        return this.cannonFx ? Ui.GREEN : Ui.CONTROL;
    }

    private boolean overSwitch(double mouseX, double mouseY) {
        return this.over(mouseX, mouseY, this.x(SWITCH_X), this.y(SWITCH_Y), SWITCH_W, SWITCH_H);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.overSwitch(mouseX, mouseY)) {
            this.throwSwitch();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            this.throwSwitch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean primaryAction() {
        this.throwSwitch();
        return true;
    }

    private void throwSwitch() {
        this.cannonFx = !this.cannonFx;
        UiSounds.lever(this.cannonFx);
    }

    @Override
    public void onClose() {
        // Sent on the way out rather than on every flick: the switch is server-wide, and
        // waggling it should not spray a packet and a saved-data write per frame.
        if (!this.sent && this.cannonFx != this.opened) {
            this.sent = true;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ControlPanelPayload(this.cannonFx));
            UiSounds.confirm();
        }
        super.onClose();
    }
}
