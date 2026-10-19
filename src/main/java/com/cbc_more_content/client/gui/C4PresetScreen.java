package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiButton;
import com.cbc_more_content.client.gui.kit.UiSegmented;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.item.C4Item;
import com.cbc_more_content.item.C4Preset;
import com.cbc_more_content.item.DetonatorItem;
import com.cbc_more_content.network.C4PresetPayload;
import com.cbc_more_content.registry.ModSounds;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Setting a C4 stack up before the throw: when the charge goes live (as it is thrown, or
 * once started by hand where it landed), what sets it off (its own clock or a detonator),
 * the fuse, and the four-digit code that disarms it. Everything the keypad, the trigger
 * page and the timer page would ask at the wall, asked once, here, in safety.
 */
@OnlyIn(Dist.CLIENT)
public class C4PresetScreen extends WarnauticsScreen {
    public static final int PANEL_W = 236;
    public static final int PANEL_H = 232;
    private static final int WHEN_Y = 32;
    private static final int TRIGGER_Y = 68;
    private static final int FUSE_Y = 94;
    private static final int CODE_Y = 136;
    private static final int SLOT_W = 22;
    private static final int SLOT_H = 22;

    private final InteractionHand hand;
    private byte activation;
    private boolean remote;
    private int seconds;
    private final int[] digits = new int[C4BlockEntity.CODE_LENGTH];
    private int entered;
    private boolean sent;

    public C4PresetScreen(InteractionHand hand, C4Preset preset) {
        super(Component.translatable("gui.cbc_more_content.c4.preset.title"), PANEL_W, PANEL_H);
        this.hand = hand;
        this.activation = preset.active() ? preset.activation() : C4Preset.ON_THROW;
        if (this.activation == C4Preset.ON_THROW && !WarnauticsConfig.c4ArmOnThrowAllowed()) {
            this.activation = C4Preset.MANUAL;
        }
        this.remote = preset.remote();
        this.seconds = preset.seconds();
        if (preset.code() >= 0) {
            int code = preset.code();
            for (int i = C4BlockEntity.CODE_LENGTH - 1; i >= 0; i--) {
                this.digits[i] = code % 10;
                code /= 10;
            }
            this.entered = C4BlockEntity.CODE_LENGTH;
        }
    }

    /** Opened by sneaking and using a C4 stack in the air. */
    public static void open(InteractionHand hand) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getItemInHand(hand).getItem() instanceof C4Item) {
            mc.setScreen(new C4PresetScreen(hand, C4Preset.of(mc.player.getItemInHand(hand))));
        }
    }

    @Override
    protected void build() {
        int w = PANEL_W - Ui.MARGIN * 2;
        this.addRenderableWidget(new UiSegmented(
                this.x(Ui.MARGIN),
                this.y(WHEN_Y),
                w,
                Ui.ROW_H,
                List.of(
                        Component.translatable("gui.cbc_more_content.c4.preset.off"),
                        Component.translatable("gui.cbc_more_content.c4.preset.on_throw"),
                        Component.translatable("gui.cbc_more_content.c4.preset.manual")),
                () -> this.activation,
                index -> {
                    if (index == C4Preset.ON_THROW && !WarnauticsConfig.c4ArmOnThrowAllowed()) {
                        UiSounds.deny();
                        return;
                    }
                    this.activation = (byte) index;
                }));
        this.addRenderableWidget(new UiSegmented(
                this.x(Ui.MARGIN),
                this.y(TRIGGER_Y),
                w,
                Ui.ROW_H,
                List.of(
                        Component.translatable("gui.cbc_more_content.c4.mode.timer"),
                        Component.translatable("gui.cbc_more_content.c4.mode.remote")),
                () -> this.remote ? 1 : 0,
                index -> this.remote = index == 1));
        this.addRenderableWidget(new UiSlider(
                this.x(Ui.MARGIN),
                this.y(FUSE_Y),
                w,
                Component.translatable("gui.cbc_more_content.c4.fuse"),
                C4BlockEntity.MAX_SECONDS - C4BlockEntity.MIN_SECONDS,
                8,
                () -> this.seconds - C4BlockEntity.MIN_SECONDS,
                step -> this.seconds = Mth.clamp(
                        C4BlockEntity.MIN_SECONDS + step, C4BlockEntity.MIN_SECONDS, C4BlockEntity.MAX_SECONDS),
                step -> Component.translatable("gui.cbc_more_content.unit.seconds", C4BlockEntity.MIN_SECONDS + step)));
        int randomX = Ui.MARGIN + this.slotsWidth() + 10;
        this.addRenderableWidget(new UiButton(
                this.x(randomX),
                this.y(CODE_Y + (SLOT_H - Ui.BUTTON_H) / 2),
                PANEL_W - randomX - Ui.MARGIN,
                UiButton.Kind.SECONDARY,
                () -> Component.translatable("gui.cbc_more_content.c4.preset.random"),
                this::randomCode));
        this.addCancelApply(Component.translatable("gui.cbc_more_content.c4.preset.apply"), this::apply);
    }

    private int slotsWidth() {
        return C4BlockEntity.CODE_LENGTH * SLOT_W + (C4BlockEntity.CODE_LENGTH - 1) * 4;
    }

    private void randomCode() {
        var random = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.random
                : net.minecraft.util.RandomSource.create();
        for (int i = 0; i < this.digits.length; i++) {
            this.digits[i] = random.nextInt(10);
        }
        this.entered = this.digits.length;
        UiSounds.play(ModSounds.C4_BUTTON.get(), 0.6f, 1.2f);
    }

    @Override
    public void tick() {
        super.tick();
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getItemInHand(this.hand).getItem() instanceof C4Item)) {
            this.sent = true;
            this.onClose();
        }
    }

    private boolean codeComplete() {
        return this.entered == C4BlockEntity.CODE_LENGTH;
    }

    private int code() {
        int value = 0;
        for (int digit : this.digits) {
            value = value * 10 + digit;
        }
        return value;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick, float now) {
        int x = this.x(Ui.MARGIN);
        int w = PANEL_W - Ui.MARGIN * 2;
        Ui.caseLabel(
                g, this.font, Component.translatable("gui.cbc_more_content.c4.preset.when"), x, this.y(WHEN_Y - 10), w);
        Ui.caseLabel(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.c4.mode.title"),
                x,
                this.y(TRIGGER_Y - 10),
                w);
        if (this.remote) {
            this.detonatorWell(g, x, this.y(FUSE_Y), w);
        }
        Ui.caseLabel(
                g, this.font, Component.translatable("gui.cbc_more_content.c4.preset.code"), x, this.y(CODE_Y - 10), w);
        for (int i = 0; i < C4BlockEntity.CODE_LENGTH; i++) {
            int sx = x + i * (SLOT_W + 4);
            int sy = this.y(CODE_Y);
            Ui.well(g, sx, sy, SLOT_W, SLOT_H);
            boolean focus = i == this.entered && (int) (now / 8.0f) % 2 == 0;
            if (i < this.entered) {
                Ui.fitCentered(
                        g,
                        this.font,
                        Component.literal(Integer.toString(this.digits[i])),
                        sx + SLOT_W / 2,
                        sy + 7,
                        SLOT_W - 4,
                        Ui.AMBER,
                        false);
            } else {
                g.fill(sx + 6, sy + SLOT_H - 6, sx + SLOT_W - 6, sy + SLOT_H - 5, focus ? Ui.AMBER : Ui.WELL_LINE);
            }
        }
        Ui.paragraph(
                g,
                this.font,
                Component.translatable(
                        this.activation == C4Preset.ON_THROW
                                ? "gui.cbc_more_content.c4.preset.hint.on_throw"
                                : this.activation == C4Preset.MANUAL
                                        ? "gui.cbc_more_content.c4.preset.hint.manual"
                                        : "gui.cbc_more_content.c4.preset.hint.off"),
                x,
                this.y(CODE_Y + SLOT_H + 6),
                w,
                2,
                Ui.TEXT_MUTED,
                true);
    }

    /** In place of the fuse, for a detonator charge: whether a detonator is at hand to pair. */
    private void detonatorWell(GuiGraphics g, int x, int y, int w) {
        // Covers the slider, which a detonator charge has no use for.
        Ui.well(g, x, y, w, UiSlider.HEIGHT);
        boolean have = false;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            for (ItemStack stack : player.getInventory().items) {
                if (stack.getItem() instanceof DetonatorItem) {
                    have = true;
                    break;
                }
            }
        }
        boolean pairs = have && WarnauticsConfig.c4PresetAutoPair() && this.activation == C4Preset.ON_THROW;
        Ui.lamp(g, x + 5, y + 9, pairs ? Ui.GREEN : have ? Ui.AMBER : Ui.RED, 0);
        Ui.paragraph(
                g,
                this.font,
                Component.translatable(
                        pairs
                                ? "gui.cbc_more_content.c4.preset.detonator.pairs"
                                : have
                                        ? "gui.cbc_more_content.c4.preset.detonator.by_hand"
                                        : "gui.cbc_more_content.c4.preset.detonator.none"),
                x + 15,
                y + 4,
                w - 20,
                2,
                pairs ? Ui.TEXT : Ui.TEXT_MUTED,
                false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The fuse slider has nothing to set on a detonator charge; hide it rather than
        // leave a control that does nothing.
        for (var child : this.children()) {
            if (child instanceof UiSlider slider) {
                slider.visible = !this.remote;
                slider.active = !this.remote;
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint >= '0' && codePoint <= '9') {
            if (this.entered < C4BlockEntity.CODE_LENGTH) {
                this.digits[this.entered++] = codePoint - '0';
                UiSounds.play(ModSounds.C4_BUTTON.get(), 0.5f, 0.97f + (codePoint - '0') * 0.012f);
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (this.entered > 0) {
                this.entered--;
                UiSounds.play(ModSounds.C4_BUTTON.get(), 0.5f, 0.85f);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicking a filled slot rubs the code out from there, to retype it.
        for (int i = 0; i < C4BlockEntity.CODE_LENGTH; i++) {
            int sx = this.x(Ui.MARGIN) + i * (SLOT_W + 4);
            if (this.over(mouseX, mouseY, sx, this.y(CODE_Y), SLOT_W, SLOT_H)) {
                if (i < this.entered) {
                    this.entered = i;
                    UiSounds.play(ModSounds.C4_BUTTON.get(), 0.5f, 0.85f);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected Component status() {
        if (this.activation == C4Preset.OFF) {
            return Component.translatable("gui.cbc_more_content.c4.preset.status.off");
        }
        if (!this.codeComplete()) {
            return Component.translatable("gui.cbc_more_content.c4.preset.status.need_code");
        }
        Component trigger = this.remote
                ? Component.translatable("gui.cbc_more_content.c4.preset.status.remote")
                : Component.translatable(
                        "gui.cbc_more_content.c4.preset.status.timer",
                        String.format(Locale.ROOT, "%02d:%02d", this.seconds / 60, this.seconds % 60));
        return Component.translatable(
                this.activation == C4Preset.ON_THROW
                        ? "gui.cbc_more_content.c4.preset.status.on_throw"
                        : "gui.cbc_more_content.c4.preset.status.manual",
                trigger);
    }

    @Override
    protected int statusColour() {
        if (this.activation == C4Preset.OFF) {
            return Ui.TEXT_DIM;
        }
        if (!this.codeComplete()) {
            return Ui.AMBER;
        }
        return this.activation == C4Preset.ON_THROW ? Ui.RED : Ui.GREEN;
    }

    @Override
    protected int lampColour() {
        return this.activation == C4Preset.ON_THROW ? Ui.RED : this.activation == C4Preset.MANUAL ? Ui.AMBER : 0;
    }

    @Override
    protected boolean primaryAction() {
        this.apply();
        return true;
    }

    private void apply() {
        if (this.sent) {
            return;
        }
        if (this.activation != C4Preset.OFF && !this.codeComplete()) {
            UiSounds.deny();
            return;
        }
        this.sent = true;
        PacketDistributor.sendToServer(new C4PresetPayload(
                this.hand == InteractionHand.OFF_HAND,
                this.activation,
                this.remote,
                this.seconds,
                this.activation == C4Preset.OFF ? -1 : this.code()));
        UiSounds.confirm();
        this.onClose();
    }
}
