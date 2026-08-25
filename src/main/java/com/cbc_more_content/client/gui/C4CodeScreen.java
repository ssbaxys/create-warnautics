package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.network.C4CodePayload;
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
 * The keypad on a C4 charge. Planting one sets a code; the same code is what stops it
 * again, so walking away from a live charge is a commitment.
 */
@OnlyIn(Dist.CLIENT)
public class C4CodeScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;

    private static final int SLOT_W = 22;
    private static final int SLOT_H = 26;
    private static final int SLOT_GAP = 6;
    private static final int SLOTS_Y = 20;

    private static final int KEY_W = 26;
    private static final int KEY_H = 16;
    private static final int KEY_GAP = 4;
    private static final int KEYS_Y = 54;

    /** Ticks the accept/reject flash is held before the screen reacts. */
    private static final int VERDICT_TICKS = 14;

    private final BlockPos pos;
    private final boolean disarming;
    private final int[] digits = new int[C4BlockEntity.CODE_LENGTH];
    /** Render clock, in ticks plus partials, driving every animation here. */
    private float time;
    private int entered;
    /** Frame the newest digit landed on, for its pop-in. */
    private float lastEntryTime = -100.0f;
    private int pressedKey = -1;
    private float pressedTime = -100.0f;
    /** 0 none, 1 accepted, -1 rejected. */
    private int verdict;
    private float verdictTime;
    private boolean sent;
    /** Set when the charge vanished, so closing does not chain into the timer. */
    private boolean abandoned;

    private int guiLeft;
    private int guiTop;

    public C4CodeScreen(BlockPos pos, boolean disarming) {
        super(Component.translatable(disarming
                ? "gui.cbc_more_content.c4.code.disarm"
                : "gui.cbc_more_content.c4.code.arm"));
        this.pos = pos;
        this.disarming = disarming;
    }

    /** Called from the network handler when the server has judged a disarm attempt. */
    public void onVerdict(boolean accepted) {
        this.verdict = accepted ? 1 : -1;
        this.verdictTime = this.time;
        if (!accepted) {
            this.playClick(0.8f, 0.55f);
        }
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    /**
     * Plain darkening rather than the vanilla menu background, which runs the blur post
     * pass over the whole frame. Matches {@link C4SettingsScreen}, so stepping from the
     * keypad to the timer does not change how the world behind them looks.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int alpha = ((int) (0x50 * Math.min(1.0f, (this.time + partialTick) / 8.0f))) << 24;
        graphics.fillGradient(0, 0, this.width, this.height, 0x101010 | alpha, 0x101010 | alpha);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        WarnauticsGuiTextures.C4_PANEL.render(graphics, this.guiLeft, this.guiTop);

        graphics.drawCenteredString(this.font, this.title,
                this.guiLeft + PANEL_W / 2, this.guiTop + 6, 0xE8E2CF);

        this.renderSlots(graphics, partialTick);
        this.renderKeys(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        this.time += 1.0f;
        if (!C4Screens.stillThere(this.pos)) {
            this.abandoned = true;
            this.onClose();
            return;
        }
        if (this.verdict != 0 && this.time - this.verdictTime >= VERDICT_TICKS) {
            if (this.verdict == 1) {
                this.onClose();
            } else {
                // Wrong code: wipe the entry and let them try again.
                this.verdict = 0;
                this.entered = 0;
                this.sent = false;
            }
        }
    }

    private void renderSlots(GuiGraphics graphics, float partialTick) {
        int total = C4BlockEntity.CODE_LENGTH * SLOT_W + (C4BlockEntity.CODE_LENGTH - 1) * SLOT_GAP;
        int x0 = this.guiLeft + (PANEL_W - total) / 2;
        int y = this.guiTop + SLOTS_Y;
        float now = this.time + partialTick;

        // A rejected code shakes the whole row rather than each slot, so it reads as the
        // panel refusing rather than the digits wobbling.
        int shake = 0;
        if (this.verdict == -1) {
            float t = (now - this.verdictTime) / VERDICT_TICKS;
            shake = Math.round(Mth.sin(t * 42.0f) * 3.0f * (1.0f - Mth.clamp(t, 0.0f, 1.0f)));
        }

        for (int i = 0; i < C4BlockEntity.CODE_LENGTH; i++) {
            int x = x0 + i * (SLOT_W + SLOT_GAP) + shake;
            boolean filled = i < this.entered;
            int border = this.verdict == 1 ? 0xFF6FD46F
                    : this.verdict == -1 ? 0xFFD46F6F
                    : filled ? 0xFFB08A3E : 0xFF5C6450;

            graphics.fill(x, y, x + SLOT_W, y + SLOT_H, 0xFF12140F);
            graphics.fill(x + 1, y + 1, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF1C1E1B);
            graphics.fill(x + 1, y + SLOT_H - 2, x + SLOT_W - 1, y + SLOT_H - 1, border);

            if (!filled) {
                // Idle caret pulsing in the next empty slot.
                if (i == this.entered && this.verdict == 0) {
                    int a = (int) ((0.35f + 0.35f * Mth.sin(now * 0.25f)) * 255.0f);
                    graphics.fill(x + SLOT_W / 2 - 4, y + SLOT_H - 8,
                            x + SLOT_W / 2 + 4, y + SLOT_H - 7, (a << 24) | 0xB08A3E);
                }
                continue;
            }

            // The newest digit drops in and settles instead of appearing outright.
            float age = i == this.entered - 1 ? now - this.lastEntryTime : 99.0f;
            int lift = age < 4.0f ? Math.round((4.0f - age) * 1.6f) : 0;
            int colour = this.verdict == 1 ? 0xA0FFA0 : this.verdict == -1 ? 0xFFA0A0 : 0xFFB036;
            graphics.drawCenteredString(this.font, String.valueOf(this.digits[i]),
                    x + SLOT_W / 2, y + SLOT_H / 2 - 4 - lift, colour);
        }
    }

    private void renderKeys(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float now = this.time + partialTick;
        for (int key = 0; key <= 9; key++) {
            int[] box = keyBox(key);
            boolean hot = inside(mouseX, mouseY, box);
            float press = key == this.pressedKey ? Mth.clamp(1.0f - (now - this.pressedTime) / 4.0f, 0.0f, 1.0f) : 0.0f;

            int face = press > 0.0f ? 0xFFB08A3E : hot ? 0xFF4A5042 : 0xFF3A3F34;
            graphics.fill(box[0], box[1], box[0] + KEY_W, box[1] + KEY_H, 0xFF12140F);
            graphics.fill(box[0] + 1, box[1] + 1, box[0] + KEY_W - 1, box[1] + KEY_H - 1, face);
            graphics.drawCenteredString(this.font, String.valueOf(key),
                    box[0] + KEY_W / 2, box[1] + 4, press > 0.0f ? 0x12140F : 0xE8E2CF);
        }
    }

    /** 0-9 laid out as two rows of five, which fits the panel without crowding. */
    private int[] keyBox(int key) {
        int row = key / 5;
        int col = key % 5;
        int total = 5 * KEY_W + 4 * KEY_GAP;
        int x = this.guiLeft + (PANEL_W - total) / 2 + col * (KEY_W + KEY_GAP);
        int y = this.guiTop + KEYS_Y + row * (KEY_H + KEY_GAP);
        return new int[] {x, y};
    }

    private boolean inside(double mouseX, double mouseY, int[] box) {
        return mouseX >= box[0] && mouseX < box[0] + KEY_W
                && mouseY >= box[1] && mouseY < box[1] + KEY_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int key = 0; key <= 9; key++) {
            if (inside(mouseX, mouseY, keyBox(key))) {
                this.press(key);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            this.press(keyCode - GLFW.GLFW_KEY_0);
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            this.press(keyCode - GLFW.GLFW_KEY_KP_0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            this.backspace();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void backspace() {
        if (this.entered > 0 && this.verdict == 0) {
            this.entered--;
            this.playClick(0.5f, 0.85f);
        }
    }

    private void press(int digit) {
        if (this.verdict != 0 || this.entered >= C4BlockEntity.CODE_LENGTH) {
            return;
        }
        this.digits[this.entered++] = digit;
        this.lastEntryTime = this.time;
        this.pressedKey = digit;
        this.pressedTime = this.time;
        // A touch of detune per key, so a code has its own rhythm without turning
        // the keypad into an instrument.
        this.playClick(0.9f, 0.97f + digit * 0.012f);

        if (this.entered == C4BlockEntity.CODE_LENGTH) {
            this.submit();
        }
    }

    private void submit() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new C4CodePayload(this.pos, this.code(), this.disarming));

        if (this.disarming) {
            // Wait for the server's verdict; onVerdict drives the rest.
            return;
        }
        // Planting: the code is accepted by definition, so go straight to the timer.
        this.verdict = 1;
        this.verdictTime = this.time;
        this.playClick(0.9f, 1.25f);
    }

    private int code() {
        int value = 0;
        for (int digit : this.digits) {
            value = value * 10 + digit;
        }
        return value;
    }

    private void playClick(float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(ModSounds.C4_BUTTON.get(), volume, pitch);
        }
    }

    @Override
    public void onClose() {
        // Planting continues into the trigger; disarming or backing out just closes.
        if (this.verdict == 1 && !this.disarming && !this.abandoned) {
            C4ModeClient.open(this.pos);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
