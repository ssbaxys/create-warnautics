package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.ConfigureC4Payload;
import com.cbc_more_content.registry.ModSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * Second page of planting a charge: what actually sets it off.
 * <p>
 * Ahead of the timer, because the timer is only a question at all on one of the two
 * answers. A charge waiting on a detonator has no fuse to set, so picking that one arms
 * it here and the timer page never opens. Each option says plainly what it does rather
 * than leaving it to be discovered by blowing yourself up.
 */
@OnlyIn(Dist.CLIENT)
public class C4ModeScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;

    private static final int OPTION_X = 12;
    private static final int OPTION_W = 152;
    private static final int OPTION_H = 24;
    private static final int OPTION_A_Y = 18;
    private static final int OPTION_B_Y = 45;

    private static final int BUTTON_W = 72;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_X = 52;
    private static final int BUTTON_Y = 73;

    private final BlockPos pos;
    private boolean remote;
    private boolean sent;

    private int guiLeft;
    private int guiTop;

    public C4ModeScreen(BlockPos pos) {
        super(Component.translatable("gui.cbc_more_content.c4.mode.title"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x50101010, 0x50101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_PANEL.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title,
                this.guiLeft + PANEL_W / 2, this.guiTop + 5, 0xE8E2CF);

        this.renderOption(graphics, mouseX, mouseY, OPTION_A_Y, false,
                "gui.cbc_more_content.c4.mode.timer",
                "gui.cbc_more_content.c4.mode.timer.desc");
        this.renderOption(graphics, mouseX, mouseY, OPTION_B_Y, true,
                "gui.cbc_more_content.c4.mode.remote",
                "gui.cbc_more_content.c4.mode.remote.desc");
        this.renderConfirm(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOption(
            GuiGraphics graphics, int mouseX, int mouseY, int offsetY, boolean isRemote,
            String title, String description) {
        int x = this.guiLeft + OPTION_X;
        int y = this.guiTop + offsetY;
        boolean chosen = isRemote == this.remote;
        boolean hot = mouseX >= x && mouseX < x + OPTION_W && mouseY >= y && mouseY < y + OPTION_H;

        graphics.fill(x, y, x + OPTION_W, y + OPTION_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + OPTION_W - 1, y + OPTION_H - 1,
                chosen ? 0xFF3A4033 : (hot ? 0xFF2A2E24 : 0xFF1C1E1B));
        // A lit edge on the chosen one, so the pick reads at a glance.
        graphics.fill(x + 1, y + 1, x + 3, y + OPTION_H - 1, chosen ? 0xFFB08A3E : 0xFF5C6450);

        graphics.drawString(this.font, Component.translatable(title),
                x + 8, y + 4, chosen ? 0xFFB036 : 0xE8E2CF, false);
        drawFitted(graphics, Component.translatable(description).getString(),
                x + 8, y + 15, OPTION_W - 16, 0x9AA08C);
    }

    private void renderConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        boolean hot = this.overConfirm(mouseX, mouseY);
        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1,
                hot ? 0xFF4A5042 : 0xFF3A3F34);
        // A timer charge still has a page to go; a remote one goes live from here, and
        // the button has to say which of the two is about to happen.
        graphics.drawCenteredString(this.font,
                Component.translatable(this.remote
                        ? "gui.cbc_more_content.c4.arm"
                        : "gui.cbc_more_content.c4.next"),
                x + BUTTON_W / 2, y + 5, hot ? 0xFFB036 : 0xE8E2CF);
    }

    /** Shrinks a line rather than letting it run off the panel in longer languages. */
    private void drawFitted(GuiGraphics graphics, String text, int x, int y, int maxWidth, int colour) {
        int width = this.font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(this.font, text, x, y, colour, false);
            return;
        }
        float scale = maxWidth / (float) width;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + (1.0f - scale) * 4.0f, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(this.font, text, 0, 0, colour, false);
        graphics.pose().popPose();
    }

    private boolean overConfirm(double mouseX, double mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.guiLeft + OPTION_X;
        for (int i = 0; i < 2; i++) {
            int y = this.guiTop + (i == 0 ? OPTION_A_Y : OPTION_B_Y);
            if (mouseX >= x && mouseX < x + OPTION_W && mouseY >= y && mouseY < y + OPTION_H) {
                this.remote = i == 1;
                this.click(1.3f);
                return true;
            }
        }
        if (this.overConfirm(mouseX, mouseY)) {
            this.confirm();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        this.click(0.85f);
        if (!this.remote) {
            // On to the fuse, which is the page that arms it.
            C4SettingsClient.open(this.pos, this.fuseSeconds());
            return;
        }
        // Nothing left to ask. The fuse carried here is the one the charge already had:
        // a remote charge never runs it, and overwriting it would quietly lose the
        // setting for anyone who went back and picked the timer instead.
        PacketDistributor.sendToServer(
                new ConfigureC4Payload(this.pos, this.fuseSeconds(), true, true));
        this.onClose();
    }

    /** Whatever fuse the charge is already carrying, or the default if it has none. */
    private int fuseSeconds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(this.pos)
                instanceof com.cbc_more_content.block.C4BlockEntity charge) {
            return charge.fuseSeconds();
        }
        return com.cbc_more_content.block.C4BlockEntity.DEFAULT_SECONDS;
    }

    private void click(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(ModSounds.C4_BUTTON.get(), 0.75f, pitch);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
