package com.cbc_more_content.client.gui;

import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiButton;
import com.cbc_more_content.client.gui.kit.UiOptionCard;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.ConfigureC4Payload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Second page of planting a charge: what actually sets it off.
 * <p>
 * Ahead of the timer, because the timer is only a question at all on one of the two
 * answers. A charge waiting on a detonator has no fuse to set, so picking that one arms
 * it here and the timer page never opens. Each option says plainly what it does rather
 * than leaving it to be discovered by blowing yourself up.
 */
@OnlyIn(Dist.CLIENT)
public class C4ModeScreen extends WarnauticsScreen {
    public static final int PANEL_W = 196;
    public static final int PANEL_H = 127;
    private static final int CARD_H = 26;

    private final BlockPos pos;
    private boolean remote;
    private boolean sent;

    public C4ModeScreen(BlockPos pos) {
        super(Component.translatable("gui.cbc_more_content.c4.mode.title"), PANEL_W, PANEL_H);
        this.pos = pos;
    }

    @Override
    protected void build() {
        int width = PANEL_W - Ui.MARGIN * 2;
        this.addRenderableWidget(new UiOptionCard(
                this.x(Ui.MARGIN),
                this.y(this.contentTop()),
                width,
                CARD_H,
                Component.translatable("gui.cbc_more_content.c4.mode.timer"),
                Component.translatable("gui.cbc_more_content.c4.mode.timer.desc"),
                () -> !this.remote,
                () -> this.remote = false));
        this.addRenderableWidget(new UiOptionCard(
                this.x(Ui.MARGIN),
                this.y(this.contentTop() + CARD_H + 4),
                width,
                CARD_H,
                Component.translatable("gui.cbc_more_content.c4.mode.remote"),
                Component.translatable("gui.cbc_more_content.c4.mode.remote.desc"),
                () -> this.remote,
                () -> this.remote = true));
        // A timer charge still has a page to go; a remote one goes live from here, and
        // the button has to say which of the two is about to happen.
        int buttonW = 84;
        this.addRenderableWidget(new UiButton(
                this.x(PANEL_W - Ui.MARGIN - buttonW),
                this.y(this.statusY() - Ui.BUTTON_H - 5),
                buttonW,
                UiButton.Kind.PRIMARY,
                () -> Component.translatable(
                        this.remote ? "gui.cbc_more_content.c4.arm" : "gui.cbc_more_content.c4.next"),
                this::confirm));
    }

    @Override
    public void tick() {
        super.tick();
        if (!C4Screens.stillThere(this.pos)) {
            this.sent = true;
            this.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        Ui.caseLabel(
                graphics,
                this.font,
                Component.translatable("gui.cbc_more_content.c4.step", 2, this.remote ? 2 : 3),
                this.x(Ui.MARGIN),
                this.y(this.statusY() - Ui.BUTTON_H + 1),
                PANEL_W - Ui.MARGIN * 2 - 92);
    }

    @Override
    protected Component status() {
        return Component.translatable(
                this.remote ? "gui.cbc_more_content.c4.mode.status.remote" : "gui.cbc_more_content.c4.mode.status");
    }

    @Override
    protected int statusColour() {
        return Ui.AMBER;
    }

    @Override
    protected int lampColour() {
        return Ui.AMBER;
    }

    @Override
    protected boolean primaryAction() {
        this.confirm();
        return true;
    }

    private void confirm() {
        if (this.sent) {
            return;
        }
        this.sent = true;
        UiSounds.confirm();
        if (!this.remote) {
            // On to the fuse, which is the page that arms it.
            C4SettingsClient.open(this.pos, this.fuseSeconds());
            return;
        }
        // Nothing left to ask. The fuse carried here is the one the charge already had:
        // a remote charge never runs it, and overwriting it would quietly lose the
        // setting for anyone who went back and picked the timer instead.
        PacketDistributor.sendToServer(new ConfigureC4Payload(this.pos, this.fuseSeconds(), true, true));
        this.onClose();
    }

    /** Whatever fuse the charge is already carrying, or the default if it has none. */
    private int fuseSeconds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
                && mc.level.getBlockEntity(this.pos) instanceof com.cbc_more_content.block.C4BlockEntity charge) {
            return charge.fuseSeconds();
        }
        return com.cbc_more_content.block.C4BlockEntity.DEFAULT_SECONDS;
    }
}
