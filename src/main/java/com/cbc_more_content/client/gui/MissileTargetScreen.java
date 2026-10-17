package com.cbc_more_content.client.gui;

import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiButton;
import com.cbc_more_content.client.gui.kit.UiSegmented;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.MissileTargetPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Flight plan for a placed missile: how it is guided, the path it flies, and — for typed
 * coordinates — where it is going.
 * <p>
 * Kept to the keypad's manner rather than vanilla text boxes — one field lit at a time,
 * digits typed straight in, and the confirm only lights once all three read something.
 */
@OnlyIn(Dist.CLIENT)
public class MissileTargetScreen extends WarnauticsScreen {
    public static final int PANEL_W = 252;
    public static final int PANEL_H = 214;

    private static final int MODE_Y = 22;
    private static final int MODE_H = 18;

    public static final int PROFILE_X = 9;
    public static final int PROFILE_Y = 58;
    public static final int PROFILE_W = 74;
    public static final int PROFILE_H = 58;
    public static final int PROFILE_GAP = 6;

    private static final int FIELDS_Y = 148;
    private static final int FIELD_W = 74;
    private static final int FIELD_H = 18;
    private static final int FIELD_GAP = 6;

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
    private boolean sent;
    /** 0 typed coordinates, 1 handed to a designator, 2 handed to a radar set. */
    private int mode;

    private int flightProfile;
    private final float[] profileGlow = new float[3];

    /**
     * @param current the aim point already set, or null when there is none
     * @param mode    the guidance already chosen, so reopening the screen shows what the
     *                missile is actually set to rather than resetting it to coordinates
     */
    public MissileTargetScreen(BlockPos pos, BlockPos current, int mode, int flightProfile) {
        super(Component.translatable("gui.cbc_more_content.missile.target"), PANEL_W, PANEL_H);
        this.pos = pos;
        this.mode = Mth.clamp(mode, 0, MODES - 1);
        this.flightProfile = Mth.clamp(flightProfile, 0, 2);
        this.profileGlow[this.flightProfile] = 1;
        if (current != null) {
            this.fields[0] = Integer.toString(current.getX());
            this.fields[1] = Integer.toString(current.getY());
            this.fields[2] = Integer.toString(current.getZ());
        }
    }

    /** Centre of the confirm button, for the client check. */
    public static double confirmX(int left) {
        return left + PANEL_W - Ui.MARGIN - 39;
    }

    public static double confirmY(int top) {
        return top + PANEL_H - Ui.STATUS_H - 6 - Ui.BUTTON_H - 5 + Ui.BUTTON_H / 2.0;
    }

    @Override
    protected void build() {
        List<Component> modes = new ArrayList<>();
        modes.add(Component.translatable("gui.cbc_more_content.missile.mode.coords"));
        modes.add(Component.translatable("gui.cbc_more_content.missile.mode.remote"));
        if (INTERCEPT_AVAILABLE) {
            modes.add(Component.translatable("gui.cbc_more_content.missile.mode.intercept"));
        }
        this.addRenderableWidget(new UiSegmented(
                this.x(Ui.MARGIN),
                this.y(MODE_Y),
                PANEL_W - Ui.MARGIN * 2,
                MODE_H,
                modes,
                () -> this.mode,
                index -> this.mode = index));
        int buttonW = 78;
        int right = this.x(PANEL_W - Ui.MARGIN);
        this.addRenderableWidget(new UiButton(
                right - buttonW,
                this.footerY(),
                buttonW,
                Ui.BUTTON_H,
                UiButton.Kind.PRIMARY,
                () -> Component.translatable("gui.cbc_more_content.missile.target.set"),
                this::confirm,
                this::complete));
        this.addRenderableWidget(new UiButton(
                right - buttonW * 2 - 6,
                this.footerY(),
                buttonW,
                Ui.BUTTON_H,
                UiButton.Kind.SECONDARY,
                () -> CommonComponents.GUI_CANCEL,
                this::onClose,
                () -> true));
    }

    @Override
    public void tick() {
        super.tick();
        for (int i = 0; i < 3; i++) {
            this.profileGlow[i] = Mth.lerp(.25f, this.profileGlow[i], i == this.flightProfile ? 1f : 0f);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null
                || !(mc.level.getBlockState(this.pos).getBlock()
                        instanceof com.cbc_more_content.block.CruiseMissileBlock)) {
            this.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        Ui.caseLabel(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.missile.profile.header"),
                this.x(Ui.MARGIN + 1),
                this.y(PROFILE_Y - 11),
                PANEL_W - Ui.MARGIN * 2);
        this.renderProfiles(graphics, mouseX, mouseY, now);
        Ui.fitCentered(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.missile.profile." + this.flightProfile + ".detail"),
                this.x(PANEL_W / 2),
                this.y(PROFILE_Y + PROFILE_H + 5),
                PANEL_W - Ui.MARGIN * 2,
                Ui.TEXT,
                true);
        Ui.caseLabel(
                graphics,
                this.font,
                Component.translatable(
                        this.mode == 0
                                ? "gui.cbc_more_content.missile.target.header"
                                : "gui.cbc_more_content.missile.guidance.header"),
                this.x(Ui.MARGIN + 1),
                this.y(FIELDS_Y - 11),
                PANEL_W - Ui.MARGIN * 2);
        if (this.mode != 0) {
            int x = this.x(Ui.MARGIN);
            int y = this.y(FIELDS_Y);
            int w = PANEL_W - Ui.MARGIN * 2;
            Ui.well(graphics, x, y, w, FIELD_H);
            Ui.lamp(graphics, x + 6, y + 6, this.mode == 1 ? Ui.AMBER : Ui.PHOSPHOR, 0.5f + 0.5f * Mth.sin(now * 0.3f));
            Ui.fitLeft(
                    graphics,
                    this.font,
                    Component.translatable(
                            this.mode == 1
                                    ? "gui.cbc_more_content.missile.remote.armed"
                                    : "gui.cbc_more_content.missile.intercept.armed"),
                    x + 18,
                    y + 5,
                    w - 24,
                    this.mode == 1 ? Ui.AMBER : Ui.PHOSPHOR,
                    false);
        } else {
            for (int i = 0; i < 3; i++) {
                this.renderField(graphics, i, mouseX, mouseY, now);
            }
        }
    }

    /** Three instrument cards, each drawing the path it flies with a marker running along it. */
    private void renderProfiles(GuiGraphics graphics, int mouseX, int mouseY, float now) {
        for (int profile = 0; profile < 3; profile++) {
            int x = this.profileX(profile);
            int y = this.y(PROFILE_Y);
            boolean chosen = this.flightProfile == profile;
            boolean hot = this.over(mouseX, mouseY, x, y, PROFILE_W, PROFILE_H);
            float glow = this.profileGlow[profile];
            Ui.well(graphics, x, y, PROFILE_W, PROFILE_H);
            if (glow > 0.01f) {
                graphics.fill(
                        x + 1, y + 2, x + PROFILE_W - 1, y + PROFILE_H - 1, Ui.withAlpha(Ui.BRASS_SHADE, glow * 0.45f));
            } else if (hot) {
                graphics.fill(x + 1, y + 2, x + PROFILE_W - 1, y + PROFILE_H - 1, Ui.WELL_LINE);
            }
            String name =
                    switch (profile) {
                        case 1 -> "gui.cbc_more_content.missile.profile.arc";
                        case 2 -> "gui.cbc_more_content.missile.profile.evasive";
                        default -> "gui.cbc_more_content.missile.profile.direct";
                    };
            Ui.fitCentered(
                    graphics,
                    this.font,
                    Component.translatable(name),
                    x + PROFILE_W / 2,
                    y + 5,
                    PROFILE_W - 6,
                    chosen ? Ui.AMBER : hot ? Ui.TEXT : Ui.TEXT_MUTED,
                    false);
            // Ground, then the path.
            graphics.fill(x + 6, y + 41, x + PROFILE_W - 6, y + 42, Ui.WELL_LINE);
            int path = Ui.mix(Ui.TEXT_DIM, Ui.AMBER, glow);
            for (int step = 0; step <= 43; step++) {
                double t = step / 43.0;
                int px = x + 10 + step * 54 / 43;
                int py = y + 31 + pathOffset(profile, t);
                graphics.fill(px, py, px + 2, py + 1, path);
            }
            double moving = (now * (profile == 2 ? .021 : .014)) % 1.0;
            int markerX = x + 10 + (int) (moving * 54);
            int markerY = y + 31 + pathOffset(profile, moving);
            graphics.fill(markerX - 2, markerY - 1, markerX + 3, markerY + 2, chosen ? Ui.AMBER : Ui.TEXT_MUTED);
            Ui.fitCentered(
                    graphics,
                    this.font,
                    Component.translatable("gui.cbc_more_content.missile.profile." + profile + ".stats"),
                    x + PROFILE_W / 2,
                    y + 46,
                    PROFILE_W - 6,
                    chosen ? Ui.TEXT : Ui.TEXT_DIM,
                    false);
            if (chosen) {
                graphics.fill(x + 6, y + PROFILE_H - 3, x + PROFILE_W - 6, y + PROFILE_H - 2, Ui.AMBER);
            }
        }
    }

    private static int pathOffset(int profile, double t) {
        return switch (profile) {
            case 1 -> (int) (-14 * Math.sin(t * Math.PI));
            case 2 -> (int) (5 * Math.sin(t * 5 * Math.PI) * Math.sin(t * Math.PI));
            default -> 0;
        };
    }

    private int profileX(int index) {
        return this.x(PROFILE_X + index * (PROFILE_W + PROFILE_GAP));
    }

    private void renderField(GuiGraphics graphics, int index, int mouseX, int mouseY, float now) {
        int x = this.fieldX(index);
        int y = this.y(FIELDS_Y);
        boolean focused = index == this.active;
        boolean hot = this.over(mouseX, mouseY, x, y, FIELD_W, FIELD_H);
        Ui.well(graphics, x, y, FIELD_W, FIELD_H);
        if (hot && !focused) {
            graphics.fill(x + 1, y + 2, x + FIELD_W - 1, y + FIELD_H - 1, Ui.WELL_LINE);
        }
        graphics.fill(x + 2, y + FIELD_H - 3, x + FIELD_W - 2, y + FIELD_H - 2, focused ? Ui.AMBER : Ui.WELL_LINE);
        graphics.drawString(this.font, LABELS[index], x + 5, y + 5, focused ? Ui.AMBER : Ui.TEXT_DIM, false);

        String text = this.fields[index];
        boolean caret = focused && (int) (now / 8.0f) % 2 == 0;
        String shown = text.isEmpty() ? (focused ? "" : "—") : text;
        int textX = x + FIELD_W - 6 - this.font.width(shown + (focused ? "_" : ""));
        graphics.drawString(this.font, shown, textX, y + 5, focused ? Ui.AMBER : Ui.TEXT, false);
        if (caret) {
            graphics.drawString(this.font, "_", textX + this.font.width(shown), y + 5, Ui.AMBER, false);
        }
    }

    private int fieldX(int index) {
        int total = 3 * FIELD_W + 2 * FIELD_GAP;
        return this.x((PANEL_W - total) / 2 + index * (FIELD_W + FIELD_GAP));
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

    @Override
    protected Component status() {
        return Component.translatable(
                switch (this.mode) {
                    case 1 -> "gui.cbc_more_content.missile.remote.hint";
                    case 2 -> "gui.cbc_more_content.missile.intercept.hint";
                    default -> "gui.cbc_more_content.missile.target.hint";
                });
    }

    @Override
    protected int statusColour() {
        return this.complete() ? Ui.GREEN : Ui.AMBER;
    }

    @Override
    protected int lampColour() {
        return this.complete() ? Ui.GREEN : Ui.AMBER;
    }

    @Override
    protected boolean primaryAction() {
        if (this.complete()) {
            this.confirm();
        } else {
            UiSounds.deny();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < 3; i++) {
            if (this.over(mouseX, mouseY, this.profileX(i), this.y(PROFILE_Y), PROFILE_W, PROFILE_H)) {
                if (this.flightProfile != i) {
                    this.flightProfile = i;
                    UiSounds.press(1.1f + i * .14f);
                }
                return true;
            }
        }
        if (this.mode == 0) {
            for (int i = 0; i < 3; i++) {
                if (this.over(mouseX, mouseY, this.fieldX(i), this.y(FIELDS_Y), FIELD_W, FIELD_H)) {
                    this.active = i;
                    UiSounds.press(1.2f);
                    return true;
                }
            }
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
        if (this.mode == 0) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                String field = this.fields[this.active];
                if (!field.isEmpty()) {
                    this.fields[this.active] = field.substring(0, field.length() - 1);
                    UiSounds.press(0.9f);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                this.active = (this.active + (hasShiftDown() ? 2 : 1)) % 3;
                UiSounds.press(1.2f);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void append(String digit) {
        // A signed border coordinate needs nine characters (-30000000).
        if (this.fields[this.active].length() < 9) {
            this.fields[this.active] += digit;
            UiSounds.press(1.0f + this.active * 0.06f);
        }
    }

    private void confirm() {
        if (this.sent || !this.complete()) {
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new MissileTargetPayload(
                this.pos,
                parse(this.fields[0]),
                parse(this.fields[1]),
                parse(this.fields[2]),
                this.mode,
                this.flightProfile));
        UiSounds.confirm();
        this.onClose();
    }

    private static int parse(String field) {
        try {
            return Mth.clamp(Integer.parseInt(field), -30_000_000, 30_000_000);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
