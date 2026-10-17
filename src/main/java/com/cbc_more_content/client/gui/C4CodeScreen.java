package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.C4CodePayload;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.client.gui.GuiGraphics;
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
public class C4CodeScreen extends WarnauticsScreen {
    public static final int PANEL_W = 196;
    public static final int PANEL_H = 128;

    private static final int SLOT_W = 24;
    private static final int SLOT_H = 26;
    private static final int SLOT_GAP = 6;

    private static final int KEY_W = 30;
    private static final int KEY_H = 16;
    private static final int KEY_GAP = 4;
    private static final int KEYS_Y = 52;

    /** Ticks the accept/reject flash is held before the screen reacts. */
    private static final int VERDICT_TICKS = 14;

    private final BlockPos pos;
    private final boolean disarming;
    private final int[] digits = new int[C4BlockEntity.CODE_LENGTH];

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

    public C4CodeScreen(BlockPos pos, boolean disarming) {
        super(
                Component.translatable(
                        disarming ? "gui.cbc_more_content.c4.code.disarm" : "gui.cbc_more_content.c4.code.arm"),
                PANEL_W,
                PANEL_H);
        this.pos = pos;
        this.disarming = disarming;
    }

    /** Called from the network handler when the server has judged a disarm attempt. */
    public void onVerdict(boolean accepted) {
        this.verdict = accepted ? 1 : -1;
        this.verdictTime = this.age;
        if (accepted) {
            UiSounds.press(1.6f);
        } else {
            UiSounds.deny();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!C4Screens.stillThere(this.pos)) {
            this.abandoned = true;
            this.onClose();
            return;
        }
        if (this.verdict != 0 && this.age - this.verdictTime >= VERDICT_TICKS) {
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

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        this.renderSlots(graphics, now);
        this.renderKeys(graphics, mouseX, mouseY, now);
        if (!this.disarming) {
            Ui.caseLabel(
                    graphics,
                    this.font,
                    Component.translatable("gui.cbc_more_content.c4.step", 1, 3),
                    this.x(Ui.MARGIN),
                    this.y(KEYS_Y + KEY_H * 2 + KEY_GAP + 5),
                    PANEL_W - Ui.MARGIN * 2);
        }
    }

    private void renderSlots(GuiGraphics graphics, float now) {
        int total = C4BlockEntity.CODE_LENGTH * SLOT_W + (C4BlockEntity.CODE_LENGTH - 1) * SLOT_GAP;
        int x0 = this.x((PANEL_W - total) / 2);
        int y = this.y(this.contentTop());

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
            Ui.well(graphics, x, y, SLOT_W, SLOT_H);
            int line = this.verdict == 1 ? Ui.GREEN : this.verdict == -1 ? Ui.RED : filled ? Ui.BRASS : Ui.WELL_LINE;
            graphics.fill(x + 3, y + SLOT_H - 4, x + SLOT_W - 3, y + SLOT_H - 3, line);

            if (!filled) {
                // Idle caret pulsing in the next empty slot.
                if (i == this.entered && this.verdict == 0) {
                    float a = 0.35f + 0.35f * Mth.sin(now * 0.25f);
                    graphics.fill(
                            x + SLOT_W / 2 - 4,
                            y + SLOT_H - 9,
                            x + SLOT_W / 2 + 4,
                            y + SLOT_H - 8,
                            Ui.withAlpha(Ui.AMBER, a));
                }
                continue;
            }

            // The newest digit drops in and settles instead of appearing outright.
            float digitAge = i == this.entered - 1 ? now - this.lastEntryTime : 99.0f;
            int lift = digitAge < 4.0f ? Math.round((4.0f - digitAge) * 1.6f) : 0;
            int colour = this.verdict == 1 ? Ui.GREEN : this.verdict == -1 ? Ui.RED : Ui.AMBER;
            graphics.drawCenteredString(
                    this.font, String.valueOf(this.digits[i]), x + SLOT_W / 2, y + SLOT_H / 2 - 5 - lift, colour);
        }
    }

    private void renderKeys(GuiGraphics graphics, int mouseX, int mouseY, float now) {
        for (int key = 0; key <= 9; key++) {
            int[] box = this.keyBox(key);
            boolean hot = this.verdict == 0 && this.over(mouseX, mouseY, box[0], box[1], KEY_W, KEY_H);
            boolean pressed = key == this.pressedKey && now - this.pressedTime < 3.0f;
            Ui.plate(
                    graphics,
                    box[0],
                    box[1],
                    KEY_W,
                    KEY_H,
                    pressed ? Ui.Face.PRESSED : hot ? Ui.Face.HOT : Ui.Face.NORMAL);
            graphics.drawCenteredString(
                    this.font,
                    String.valueOf(key),
                    box[0] + KEY_W / 2,
                    box[1] + 4 + (pressed ? 1 : 0),
                    pressed || hot ? Ui.AMBER : Ui.TEXT);
        }
    }

    /** 0-9 laid out as two rows of five, which fits the case without crowding. */
    private int[] keyBox(int key) {
        int row = key / 5;
        int col = key % 5;
        int total = 5 * KEY_W + 4 * KEY_GAP;
        int x = this.x((PANEL_W - total) / 2 + col * (KEY_W + KEY_GAP));
        int y = this.y(KEYS_Y + row * (KEY_H + KEY_GAP));
        return new int[] {x, y};
    }

    @Override
    protected Component status() {
        if (this.verdict == 1) {
            return Component.translatable("gui.cbc_more_content.c4.code.accepted");
        }
        if (this.verdict == -1) {
            return Component.translatable("gui.cbc_more_content.c4.code.wrong");
        }
        return Component.translatable(
                this.disarming
                        ? "gui.cbc_more_content.c4.code.status.disarm"
                        : "gui.cbc_more_content.c4.code.status.arm");
    }

    @Override
    protected int statusColour() {
        return this.verdict == 1 ? Ui.GREEN : this.verdict == -1 ? Ui.RED : Ui.AMBER;
    }

    @Override
    protected float statusPulse(float now) {
        return this.verdict != 0 ? 1 : 0;
    }

    @Override
    protected int lampColour() {
        return this.disarming ? Ui.RED : Ui.AMBER;
    }

    @Override
    protected float lampPulse(float now) {
        return this.disarming ? 0.5f + 0.5f * Mth.sin(now * 0.4f) : 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int key = 0; key <= 9; key++) {
            int[] box = this.keyBox(key);
            if (this.over(mouseX, mouseY, box[0], box[1], KEY_W, KEY_H)) {
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
            UiSounds.play(ModSounds.C4_BUTTON.get(), 0.5f, 0.85f);
        }
    }

    private void press(int digit) {
        if (this.verdict != 0 || this.entered >= C4BlockEntity.CODE_LENGTH) {
            return;
        }
        this.digits[this.entered++] = digit;
        this.lastEntryTime = this.age;
        this.pressedKey = digit;
        this.pressedTime = this.age;
        // A touch of detune per key, so a code has its own rhythm without turning
        // the keypad into an instrument.
        UiSounds.press(0.97f + digit * 0.012f);

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
        // Planting: the code is accepted by definition, so go straight to the trigger.
        this.verdict = 1;
        this.verdictTime = this.age;
        UiSounds.press(1.25f);
    }

    private int code() {
        int value = 0;
        for (int digit : this.digits) {
            value = value * 10 + digit;
        }
        return value;
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
}
