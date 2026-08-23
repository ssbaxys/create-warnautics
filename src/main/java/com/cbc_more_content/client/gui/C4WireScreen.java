package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.C4BlockEntity.WireResult;
import com.cbc_more_content.network.C4WirePayload;
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

/**
 * The wire panel behind a live charge: three wires, one of which kills the fuse, one of
 * which sets it off, and one of which halves whatever time is left. Which is which is
 * decided on the server when the charge is armed and never sent here.
 */
@OnlyIn(Dist.CLIENT)
public class C4WireScreen extends Screen {
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 95;

    private static final int WIRE_X = 24;
    private static final int WIRE_W = 128;
    private static final int WIRE_H = 6;
    private static final int WIRE_TOP = 24;
    private static final int WIRE_GAP = 17;
    /** How far the cut ends spring apart, in pixels. */
    private static final int SNAP_GAP = 9;
    private static final int VERDICT_TICKS = 26;

    private final BlockPos pos;
    private float time;
    private int cutWire = -1;
    private float cutTime;
    private WireResult result = WireResult.NOTHING;
    private boolean sent;

    private int guiLeft;
    private int guiTop;

    public C4WireScreen(BlockPos pos) {
        super(Component.translatable("gui.cbc_more_content.c4.wires"));
        this.pos = pos;
    }

    /** Routed from the network handler once the server has resolved the cut. */
    public void onResult(int wire, WireResult result) {
        this.cutWire = wire;
        this.result = result;
        this.cutTime = this.time;
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - PANEL_W) / 2;
        this.guiTop = (this.height - PANEL_H) / 2;
    }

    @Override
    public void tick() {
        this.time += 1.0f;
        if (!C4Screens.stillThere(this.pos)) {
            super.onClose();
            return;
        }
        if (this.result != WireResult.NOTHING && this.time - this.cutTime >= VERDICT_TICKS) {
            super.onClose();
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

        graphics.drawCenteredString(this.font, this.title,
                this.guiLeft + PANEL_W / 2, this.guiTop + 6, 0xE8E2CF);

        float now = this.time + partialTick;
        for (int wire = 0; wire < C4BlockEntity.WIRE_COUNT; wire++) {
            this.renderWire(graphics, wire, mouseX, mouseY, now);
        }
        this.renderVerdict(graphics, now);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderWire(GuiGraphics graphics, int wire, int mouseX, int mouseY, float now) {
        int x = this.guiLeft + WIRE_X;
        int y = this.guiTop + WIRE_TOP + wire * WIRE_GAP;
        int colour = 0xFF000000 | colourOf(wire);
        // The charge remembers what has been cut, so reopening the panel shows the same
        // board rather than three fresh wires.
        boolean cut = wire == this.cutWire || alreadyCut(wire);
        boolean hot = this.result == WireResult.NOTHING && !cut
                && this.overWire(mouseX, mouseY, wire);

        // Terminals the wire runs between, so it reads as wired into something.
        graphics.fill(x - 8, y - 2, x - 2, y + WIRE_H + 2, 0xFF1C1E1B);
        graphics.fill(x + WIRE_W + 2, y - 2, x + WIRE_W + 8, y + WIRE_H + 2, 0xFF1C1E1B);

        if (!cut) {
            graphics.fill(x, y, x + WIRE_W, y + WIRE_H, colour);
            // A lit strand along the top edge; brighter under the cursor.
            graphics.fill(x, y, x + WIRE_W, y + 1, hot ? 0xFFFFFFFF : 0x60FFFFFF);
            if (hot) {
                graphics.fill(x, y + WIRE_H - 1, x + WIRE_W, y + WIRE_H, 0x80000000);
            }
            return;
        }

        // Cut: the two ends recoil apart and the severed tips glow for a moment.
        float t = Mth.clamp((now - this.cutTime) / 6.0f, 0.0f, 1.0f);
        int gap = Math.round(SNAP_GAP * t);
        int mid = x + WIRE_W / 2;
        int sag = Math.round(t * 2.0f);
        graphics.fill(x, y + sag, mid - gap, y + WIRE_H + sag, colour);
        graphics.fill(mid + gap, y + sag, x + WIRE_W, y + WIRE_H + sag, colour);
        if (t < 1.0f) {
            int spark = (int) ((1.0f - t) * 255.0f) << 24;
            graphics.fill(mid - gap - 2, y + sag, mid - gap, y + WIRE_H + sag, spark | 0xFFE9A0);
            graphics.fill(mid + gap, y + sag, mid + gap + 2, y + WIRE_H + sag, spark | 0xFFE9A0);
        }
    }

    private void renderVerdict(GuiGraphics graphics, float now) {
        if (this.result == WireResult.NOTHING) {
            this.drawFitted(graphics,
                    Component.translatable("gui.cbc_more_content.c4.wires.hint"),
                    this.guiTop + PANEL_H - 17, 0x9AA08C);
            return;
        }

        String key = switch (this.result) {
            case DEFUSED -> "gui.cbc_more_content.c4.wires.defused";
            case DETONATED -> "gui.cbc_more_content.c4.wires.detonated";
            default -> "gui.cbc_more_content.c4.wires.accelerated";
        };
        int colour = switch (this.result) {
            case DEFUSED -> 0x6FD46F;
            case DETONATED -> 0xFF5B4A;
            default -> 0xFFB036;
        };
        // A short pulse on the verdict rather than a flat line of text.
        float pulse = 0.7f + 0.3f * Mth.sin((now - this.cutTime) * 0.6f);
        int shake = this.result == WireResult.DETONATED
                ? Math.round(Mth.sin((now - this.cutTime) * 3.4f) * 2.0f)
                : 0;
        graphics.drawCenteredString(this.font, Component.translatable(key),
                this.guiLeft + PANEL_W / 2 + shake, this.guiTop + PANEL_H - 17,
                (((int) (pulse * 255.0f)) << 24) | colour);
    }

    /**
     * Centred text that never leaves the panel. Translations vary a lot in length, so
     * anything too wide is scaled down rather than allowed to run off both edges.
     */
    private void drawFitted(GuiGraphics graphics, Component text, int y, int colour) {
        int room = PANEL_W - 12;
        int width = this.font.width(text);
        if (width <= room) {
            graphics.drawCenteredString(this.font, text, this.guiLeft + PANEL_W / 2, y, colour);
            return;
        }
        float scale = room / (float) width;
        graphics.pose().pushPose();
        graphics.pose().translate(this.guiLeft + PANEL_W / 2.0f, y, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(this.font, text, -width / 2, 0, colour, false);
        graphics.pose().popPose();
    }

    private boolean alreadyCut(int wire) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null
                && mc.level.getBlockEntity(this.pos) instanceof C4BlockEntity c4
                && c4.isWireCut(wire);
    }

    private int colourOf(int wire) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
                && mc.level.getBlockEntity(this.pos) instanceof C4BlockEntity c4) {
            return c4.wireColour(wire);
        }
        return C4BlockEntity.WIRE_PALETTE[wire % C4BlockEntity.WIRE_PALETTE.length];
    }

    private boolean overWire(double mouseX, double mouseY, int wire) {
        int x = this.guiLeft + WIRE_X;
        int y = this.guiTop + WIRE_TOP + wire * WIRE_GAP;
        return mouseX >= x - 8 && mouseX <= x + WIRE_W + 8
                && mouseY >= y - 3 && mouseY <= y + WIRE_H + 3;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.sent || this.result != WireResult.NOTHING) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (int wire = 0; wire < C4BlockEntity.WIRE_COUNT; wire++) {
            if (this.overWire(mouseX, mouseY, wire) && !this.alreadyCut(wire)) {
                this.sent = true;
                PacketDistributor.sendToServer(new C4WirePayload(this.pos, wire));
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.playSound(ModSounds.C4_BUTTON.get(), 0.9f, 1.5f);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
