package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.MissileTargetPayload;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Flight plan for a placed missile: three coordinates typed in, then confirmed.
 * <p>
 * Kept to the keypad's manner rather than vanilla text boxes — one field lit at a time,
 * digits typed straight in, and the confirm only lights once all three read something.
 */
@OnlyIn(Dist.CLIENT)
public class MissileTargetScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 112;

    private static final int MODE_W = 50;
    private static final int MODE_H = 16;
    private static final int MODE_Y = 20;

    private static final int FIELD_W = 46;
    private static final int FIELD_H = 20;
    private static final int FIELD_GAP = 6;
    private static final int FIELDS_Y = 50;

    private static final int BUTTON_W = 72;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_X = 52;
    private static final int BUTTON_Y = 78;

    private static final String[] LABELS = {"X", "Y", "Z"};

    /**
     * Intercept only exists when Create Radar does. Without it the button led to a mode
     * with nothing behind it: the missile armed, waited on a picture nobody was painting,
     * and sat on the rack.
     */
    private static final boolean INTERCEPT_AVAILABLE = com.cbc_more_content.compat.RadarCompat.loaded();

    private static final int MODES = INTERCEPT_AVAILABLE ? 3 : 2;

    private final BlockPos pos;
    private final String[] fields = {"", "", ""};
    private int active;
    private float time;
    private boolean sent;
    /** 0 typed coordinates, 1 handed to a designator, 2 handed to a radar set. */
    private int mode;

    private int guiLeft;
    private int guiTop;

    /**
     * @param current the aim point already set, or null when there is none
     * @param mode    the guidance already chosen, so reopening the screen shows what the
     *                missile is actually set to rather than resetting it to coordinates
     */
    public MissileTargetScreen(BlockPos pos, BlockPos current, int mode) {
        super(Component.translatable("gui.cbc_more_content.missile.target"));
        this.pos = pos;
        this.mode = Mth.clamp(mode, 0, MODES - 1);
        if (current != null) {
            this.fields[0] = Integer.toString(current.getX());
            this.fields[1] = Integer.toString(current.getY());
            this.fields[2] = Integer.toString(current.getZ());
        }
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void tick() {
        this.time += 1.0f;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null
                || !(mc.level.getBlockState(this.pos).getBlock()
                        instanceof com.cbc_more_content.block.CruiseMissileBlock)) {
            this.onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int alpha = ((int) (0x50 * Math.min(1.0f, (this.time + partialTick) / 8.0f))) << 24;
        graphics.fillGradient(0, 0, this.width, this.height, 0x101010 | alpha, 0x101010 | alpha);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_PANEL.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title, this.guiLeft + PANEL_W / 2, this.guiTop + 6, 0xE8E2CF);

        float now = this.time + partialTick;
        this.renderMode(graphics, mouseX, mouseY);
        if (this.mode != 0) {
            graphics.drawCenteredString(
                    this.font,
                    Component.translatable(
                            this.mode == 1
                                    ? "gui.cbc_more_content.missile.remote.armed"
                                    : "gui.cbc_more_content.missile.intercept.armed"),
                    this.guiLeft + PANEL_W / 2,
                    this.guiTop + FIELDS_Y + 6,
                    0xFFB036);
        } else {
            for (int i = 0; i < 3; i++) {
                this.renderField(graphics, i, mouseX, mouseY, now);
            }
        }
        this.renderConfirm(graphics, mouseX, mouseY);

        graphics.drawCenteredString(
                this.font,
                Component.translatable(
                        switch (this.mode) {
                            case 1 -> "gui.cbc_more_content.missile.remote.hint";
                            case 2 -> "gui.cbc_more_content.missile.intercept.hint";
                            default -> "gui.cbc_more_content.missile.target.hint";
                        }),
                this.guiLeft + PANEL_W / 2,
                this.guiTop + PANEL_H - 16,
                0x9AA08C);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Two exclusive modes, because a missile cannot both hold a point and wait on a remote. */
    private void renderMode(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = 0; i < MODES; i++) {
            boolean chosen = i == this.mode;
            int x = this.modeX(i);
            int y = this.guiTop + MODE_Y;
            boolean hot = mouseX >= x && mouseX < x + MODE_W && mouseY >= y && mouseY < y + MODE_H;
            graphics.fill(x, y, x + MODE_W, y + MODE_H, 0xFF12140F);
            graphics.fill(
                    x + 1,
                    y + 1,
                    x + MODE_W - 1,
                    y + MODE_H - 1,
                    chosen ? 0xFF4A5042 : (hot ? 0xFF31362B : 0xFF23271E));
            graphics.drawCenteredString(
                    this.font,
                    Component.translatable(
                            switch (i) {
                                case 1 -> "gui.cbc_more_content.missile.mode.remote";
                                case 2 -> "gui.cbc_more_content.missile.mode.intercept";
                                default -> "gui.cbc_more_content.missile.mode.coords";
                            }),
                    x + MODE_W / 2,
                    y + 4,
                    chosen ? 0xFFB036 : 0x9AA08C);
        }
    }

    private int modeX(int index) {
        int total = MODES * MODE_W + (MODES - 1) * 4;
        return this.guiLeft + (PANEL_W - total) / 2 + index * (MODE_W + 4);
    }

    private void renderField(GuiGraphics graphics, int index, int mouseX, int mouseY, float now) {
        int x = this.fieldX(index);
        int y = this.guiTop + FIELDS_Y;
        boolean focused = index == this.active;

        graphics.fill(x, y, x + FIELD_W, y + FIELD_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + FIELD_W - 1, y + FIELD_H - 1, 0xFF1C1E1B);
        graphics.fill(x + 1, y + FIELD_H - 2, x + FIELD_W - 1, y + FIELD_H - 1, focused ? 0xFFB08A3E : 0xFF5C6450);

        graphics.drawString(this.font, LABELS[index], x + 3, y - 10, 0x9AA08C, false);

        String text = this.fields[index];
        if (focused && (int) (now / 8.0f) % 2 == 0) {
            text = text + "_";
        }
        graphics.drawCenteredString(
                this.font,
                text.isEmpty() ? "-" : text,
                x + FIELD_W / 2,
                y + FIELD_H / 2 - 4,
                focused ? 0xFFB036 : 0xE8E2CF);
    }

    private void renderConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        boolean ready = this.complete();
        boolean hot = ready && this.overConfirm(mouseX, mouseY);
        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, 0xFF12140F);
        graphics.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1, hot ? 0xFF4A5042 : 0xFF3A3F34);
        graphics.drawCenteredString(
                this.font,
                Component.translatable("gui.cbc_more_content.missile.target.set"),
                x + BUTTON_W / 2,
                y + 5,
                ready ? (hot ? 0xFFB036 : 0xE8E2CF) : 0x6A6F60);
    }

    private int fieldX(int index) {
        int total = 3 * FIELD_W + 2 * FIELD_GAP;
        return this.guiLeft + (PANEL_W - total) / 2 + index * (FIELD_W + FIELD_GAP);
    }

    private boolean complete() {
        if (this.mode != 0) {
            return true;
        }
        for (String field : this.fields) {
            if (field.isEmpty() || field.equals("-")) {
                return false;
            }
        }
        return true;
    }

    private boolean overConfirm(double mouseX, double mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < MODES; i++) {
            int mx = this.modeX(i);
            int my = this.guiTop + MODE_Y;
            if (mouseX >= mx && mouseX < mx + MODE_W && mouseY >= my && mouseY < my + MODE_H) {
                this.mode = i;
                this.click(1.35f);
                return true;
            }
        }
        if (this.mode != 0) {
            if (this.overConfirm(mouseX, mouseY)) {
                this.confirm();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (int i = 0; i < 3; i++) {
            int x = this.fieldX(i);
            int y = this.guiTop + FIELDS_Y;
            if (mouseX >= x && mouseX < x + FIELD_W && mouseY >= y && mouseY < y + FIELD_H) {
                this.active = i;
                this.click(1.2f);
                return true;
            }
        }
        if (this.overConfirm(mouseX, mouseY) && this.complete()) {
            this.confirm();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.mode != 0) {
            return super.charTyped(codePoint, modifiers);
        }
        if (codePoint >= '0' && codePoint <= '9') {
            this.append(String.valueOf(codePoint));
            return true;
        }
        if (codePoint == '-' && this.fields[this.active].isEmpty()) {
            this.append("-");
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                String field = this.fields[this.active];
                if (!field.isEmpty()) {
                    this.fields[this.active] = field.substring(0, field.length() - 1);
                    this.click(0.9f);
                }
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                this.active = (this.active + 1) % 3;
                this.click(1.2f);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (this.complete()) {
                    this.confirm();
                }
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    private void append(String digit) {
        // Six characters is more than the world is wide, and keeps the field readable.
        if (this.fields[this.active].length() < 6) {
            this.fields[this.active] += digit;
            this.click(1.0f + this.active * 0.06f);
        }
    }

    private void confirm() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new MissileTargetPayload(
                this.pos, parse(this.fields[0]), parse(this.fields[1]), parse(this.fields[2]), this.mode));
        this.click(1.25f);
        this.onClose();
    }

    private static int parse(String field) {
        try {
            return Mth.clamp(Integer.parseInt(field), -30_000_000, 30_000_000);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void click(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(ModSounds.C4_BUTTON.get(), 0.7f, pitch);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
