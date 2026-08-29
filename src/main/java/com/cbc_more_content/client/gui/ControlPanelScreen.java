package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.ControlPanelPayload;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
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
public class ControlPanelScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;

    /** The switch plate. Most of the panel, because it is most of what the panel is. */
    private static final int SWITCH_X = 20;

    private static final int SWITCH_Y = 34;
    private static final int SWITCH_W = 136;
    private static final int SWITCH_H = 34;
    /** The travelling part, half the plate wide, so its position reads as the state. */
    private static final int KNOB_W = 66;

    private static final int KNOB_INSET = 3;
    /** Ticks the lever takes to slide across. Long enough to be seen moving. */
    private static final float THROW_TICKS = 5.0f;

    private boolean cannonFx;
    private final boolean opened;
    /** 0 at the off end, 1 at the on end. Eased toward the state rather than snapped. */
    private float slide;

    private boolean sent;

    private int guiLeft;
    private int guiTop;

    public ControlPanelScreen(boolean cannonFx) {
        super(Component.translatable("gui.cbc_more_content.panel.title"));
        this.cannonFx = cannonFx;
        this.opened = cannonFx;
        this.slide = cannonFx ? 1.0f : 0.0f;
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void tick() {
        float target = this.cannonFx ? 1.0f : 0.0f;
        this.slide = Mth.approach(this.slide, target, 1.0f / THROW_TICKS);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x60101010, 0x60101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_PANEL.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title, this.guiLeft + PANEL_W / 2, this.guiTop + 6, 0xE8E2CF);
        graphics.drawCenteredString(
                this.font,
                Component.translatable("gui.cbc_more_content.panel.cannon_fx"),
                this.guiLeft + PANEL_W / 2,
                this.guiTop + 21,
                0x9AA08C);

        this.renderSwitch(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(
                this.font,
                Component.translatable("gui.cbc_more_content.panel.scope"),
                this.guiLeft + PANEL_W / 2,
                this.guiTop + PANEL_H - 20,
                0x7A8070);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSwitch(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.guiLeft + SWITCH_X;
        int y = this.guiTop + SWITCH_Y;
        boolean hot = this.overSwitch(mouseX, mouseY);
        // Eased here as well as in tick, so the lever slides smoothly at any frame rate.
        float eased = Mth.approach(this.slide, this.cannonFx ? 1.0f : 0.0f, partialTick / THROW_TICKS);

        // Plate.
        graphics.fill(x, y, x + SWITCH_W, y + SWITCH_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + SWITCH_W - 1, y + SWITCH_H - 1, 0xFF1C1E1B);

        // The lit half fills in behind the lever as it travels, so the throw reads as
        // current arriving rather than as a shape sliding about.
        int litWidth = Math.round(eased * (SWITCH_W - 2));
        if (litWidth > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + litWidth, y + SWITCH_H - 1, 0xFF3A4033);
            graphics.fill(x + 1, y + SWITCH_H - 4, x + 1 + litWidth, y + SWITCH_H - 1, 0xFFB08A3E);
        }

        // Lever.
        int travel = SWITCH_W - KNOB_W - KNOB_INSET * 2;
        int knobX = x + KNOB_INSET + Math.round(eased * travel);
        int knobY = y + KNOB_INSET;
        int knobH = SWITCH_H - KNOB_INSET * 2;
        graphics.fill(knobX, knobY, knobX + KNOB_W, knobY + knobH, 0xFF0C0D0A);
        graphics.fill(knobX + 1, knobY + 1, knobX + KNOB_W - 1, knobY + knobH - 1, hot ? 0xFF6A7060 : 0xFF55594C);
        // Grip ribs, so the lever reads as something to take hold of.
        for (int rib = -1; rib <= 1; rib++) {
            int ribX = knobX + KNOB_W / 2 + rib * 5;
            graphics.fill(ribX, knobY + 5, ribX + 1, knobY + knobH - 5, 0xFF2A2E24);
        }

        graphics.drawCenteredString(
                this.font,
                Component.translatable(
                        this.cannonFx ? "gui.cbc_more_content.panel.on" : "gui.cbc_more_content.panel.off"),
                knobX + KNOB_W / 2,
                knobY + knobH / 2 - 4,
                this.cannonFx ? 0xFFB036 : 0xC8C2AF);
    }

    private boolean overSwitch(double mouseX, double mouseY) {
        int x = this.guiLeft + SWITCH_X;
        int y = this.guiTop + SWITCH_Y;
        return mouseX >= x && mouseX < x + SWITCH_W && mouseY >= y && mouseY < y + SWITCH_H;
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
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.throwSwitch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void throwSwitch() {
        this.cannonFx = !this.cannonFx;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.LEVER_CLICK, 0.9f, this.cannonFx ? 1.0f : 0.75f);
        }
    }

    @Override
    public void onClose() {
        // Sent on the way out rather than on every flick: the switch is server-wide, and
        // waggling it should not spray a packet and a saved-data write per frame.
        if (!this.sent && this.cannonFx != this.opened) {
            this.sent = true;
            PacketDistributor.sendToServer(new ControlPanelPayload(this.cannonFx));
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.playSound(ModSounds.C4_BUTTON.get(), 0.75f, 0.85f);
            }
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
