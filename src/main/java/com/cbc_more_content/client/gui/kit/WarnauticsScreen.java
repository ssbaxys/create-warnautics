package com.cbc_more_content.client.gui.kit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Base of every Warnautics screen: one case, centred, over a plain dimmed world.
 * <p>
 * Owns what used to be repeated, slightly differently, in each screen: the dim that fades
 * in (no menu blur, so stepping from one panel to the next never changes how the world
 * behind looks), the case and its title, the lid lamp, the status line at the foot, the
 * short settle the case does as it opens, and Enter as the main action. Screens draw
 * their own furniture in {@link #renderContent} using panel-relative coordinates from
 * {@link #x(int)} and {@link #y(int)}.
 */
@OnlyIn(Dist.CLIENT)
public abstract class WarnauticsScreen extends Screen {
    private static final float OPEN_TICKS = 5.0f;
    private static final int DIM_ALPHA = 0x70;

    protected final int panelWidth;
    protected final int panelHeight;
    protected int left;
    protected int top;
    /** Ticks since opening, driving every animation on the screen. */
    protected float age;

    protected WarnauticsScreen(Component title, int panelWidth, int panelHeight) {
        super(title);
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    @Override
    protected final void init() {
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - this.panelHeight) / 2;
        this.build();
    }

    /** Add widgets here; {@link #left} and {@link #top} are already set. */
    protected void build() {}

    protected int x(int offset) {
        return this.left + offset;
    }

    protected int y(int offset) {
        return this.top + offset;
    }

    /** First usable row under the lid. */
    protected int contentTop() {
        return Ui.LID + 7;
    }

    /** Y of the status line, relative to the panel. */
    protected int statusY() {
        return this.panelHeight - Ui.STATUS_H - 6;
    }

    /** Top of the button row that sits just above the status line. */
    protected int footerY() {
        return this.y(this.statusY() - Ui.BUTTON_H - 5);
    }

    /**
     * The standard pair for a settings screen: cancel, then the brass apply button at the
     * right, so the main action is always in the same corner.
     */
    protected void addCancelApply(Component applyLabel, Runnable apply) {
        int buttonW = 78;
        int right = this.left + this.panelWidth - Ui.MARGIN;
        this.addRenderableWidget(
                new UiButton(right - buttonW, this.footerY(), buttonW, UiButton.Kind.PRIMARY, () -> applyLabel, apply));
        this.addRenderableWidget(new UiButton(
                right - buttonW * 2 - 6,
                this.footerY(),
                buttonW,
                UiButton.Kind.SECONDARY,
                () -> CommonComponents.GUI_CANCEL,
                this::onClose));
    }

    @Override
    public void tick() {
        this.age++;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float fade = Ui.easeOut((this.age + partialTick) / OPEN_TICKS);
        int alpha = (int) (DIM_ALPHA * fade) << 24;
        graphics.fillGradient(0, 0, this.width, this.height, alpha | 0x0B0D0A, alpha | 0x050604);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render would dim the world a second time, over the case; draw the
        // background once here and the widgets ourselves.
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        float now = this.age + partialTick;
        float settle = 1 - Ui.easeOut(now / OPEN_TICKS);
        graphics.pose().pushPose();
        graphics.pose().translate(0, Math.round(settle * 4), 0);

        Ui.panel(graphics, this.font, this.left, this.top, this.panelWidth, this.panelHeight, this.title);
        int lamp = this.lampColour();
        if (lamp != 0) {
            Ui.lidLamp(graphics, this.left, this.top, this.panelWidth, lamp, this.lampPulse(now));
        }
        this.renderContent(graphics, mouseX, mouseY, partialTick, now);
        Component status = this.status();
        if (status != null) {
            Ui.statusLine(
                    graphics,
                    this.font,
                    this.x(Ui.MARGIN),
                    this.y(this.statusY()),
                    this.panelWidth - Ui.MARGIN * 2,
                    status,
                    this.statusColour(),
                    this.statusPulse(now));
        }
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        this.renderOverlay(graphics, mouseX, mouseY, partialTick, now);
        graphics.pose().popPose();
    }

    /** Screen-specific drawing, between the case and the widgets. */
    protected abstract void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now);

    /** Drawn above the widgets, for anything that must sit on top of them. */
    protected void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {}

    /** Text of the status line, or null for none. */
    protected Component status() {
        return null;
    }

    protected int statusColour() {
        return Ui.TEXT_DIM;
    }

    protected float statusPulse(float now) {
        return 0;
    }

    /** Colour of the lid lamp, or 0 for no lamp. */
    protected int lampColour() {
        return 0;
    }

    protected float lampPulse(float now) {
        return 0;
    }

    /** The screen's main action, run by Enter. Return false if there is none right now. */
    protected boolean primaryAction() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && !(this.getFocused() instanceof UiWidget widget && widget.handlesEnter())
                && this.primaryAction()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    protected boolean over(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
