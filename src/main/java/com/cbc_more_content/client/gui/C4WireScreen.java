package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.C4BlockEntity.WireResult;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.C4WirePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
public class C4WireScreen extends WarnauticsScreen {
    public static final int PANEL_W = 196;
    public static final int PANEL_H = 112;

    private static final int BAY_H = 64;
    private static final int WIRE_X = 22;
    private static final int WIRE_W = PANEL_W - WIRE_X * 2;
    private static final int WIRE_H = 6;
    private static final int WIRE_TOP = 13;
    private static final int WIRE_GAP = 17;
    /** How far the cut ends spring apart, in pixels. */
    private static final int SNAP_GAP = 9;

    private static final int VERDICT_TICKS = 26;

    private final BlockPos pos;
    private int cutWire = -1;
    private float cutTime;
    private WireResult result = WireResult.NOTHING;
    private boolean sent;

    public C4WireScreen(BlockPos pos) {
        super(Component.translatable("gui.cbc_more_content.c4.wires"), PANEL_W, PANEL_H);
        this.pos = pos;
    }

    /** Routed from the network handler once the server has resolved the cut. */
    public void onResult(int wire, WireResult result) {
        this.cutWire = wire;
        this.result = result;
        this.cutTime = this.age;
        if (result == WireResult.DEFUSED) {
            UiSounds.press(1.6f);
        } else if (result == WireResult.DETONATED) {
            UiSounds.deny();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!C4Screens.stillThere(this.pos)) {
            super.onClose();
            return;
        }
        if (this.result != WireResult.NOTHING && this.age - this.cutTime >= VERDICT_TICKS) {
            super.onClose();
        }
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, float now) {
        int shake = this.result == WireResult.DETONATED
                ? Math.round(Mth.sin((now - this.cutTime) * 3.4f) * 2.0f * Math.max(0, 1 - (now - this.cutTime) / 20))
                : 0;
        Ui.well(graphics, this.x(Ui.MARGIN) + shake, this.y(this.contentTop()), PANEL_W - Ui.MARGIN * 2, BAY_H);
        for (int wire = 0; wire < C4BlockEntity.WIRE_COUNT; wire++) {
            this.renderWire(graphics, wire, mouseX, mouseY, now, shake);
        }
    }

    private int wireY(int wire) {
        return this.y(this.contentTop() + WIRE_TOP + wire * WIRE_GAP);
    }

    private void renderWire(GuiGraphics graphics, int wire, int mouseX, int mouseY, float now, int shake) {
        int x = this.x(WIRE_X) + shake;
        int y = this.wireY(wire);
        int colour = 0xFF000000 | this.colourOf(wire);
        // The charge remembers what has been cut, so reopening the panel shows the same
        // board rather than three fresh wires.
        boolean cut = wire == this.cutWire || this.alreadyCut(wire);
        boolean hot = this.result == WireResult.NOTHING && !cut && this.overWire(mouseX, mouseY, wire);

        // Brass terminals the wire runs between, so it reads as wired into something.
        Ui.brassPlate(graphics, x - 9, y - 3, 7, WIRE_H + 6, Ui.Face.NORMAL);
        Ui.brassPlate(graphics, x + WIRE_W + 2, y - 3, 7, WIRE_H + 6, Ui.Face.NORMAL);

        if (!cut) {
            graphics.fill(x, y + 1, x + WIRE_W, y + WIRE_H + 1, Ui.withAlpha(0, 0.5f));
            graphics.fill(x, y, x + WIRE_W, y + WIRE_H, colour);
            // A lit strand along the top edge; brighter under the cursor.
            graphics.fill(x, y, x + WIRE_W, y + 1, hot ? 0xFFFFFFFF : 0x60FFFFFF);
            if (hot) {
                graphics.fill(x, y + WIRE_H - 1, x + WIRE_W, y + WIRE_H, 0x80000000);
                // Cutter marks where the snip would go.
                int mid = x + WIRE_W / 2;
                graphics.fill(mid - 1, y - 4, mid + 1, y - 2, Ui.AMBER);
                graphics.fill(mid - 1, y + WIRE_H + 2, mid + 1, y + WIRE_H + 4, Ui.AMBER);
            }
            return;
        }

        // Cut: the two ends recoil apart and the severed tips glow for a moment.
        float t = Mth.clamp((now - this.cutTime) / 6.0f, 0.0f, 1.0f);
        if (wire != this.cutWire) {
            t = 1;
        }
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

    @Override
    protected Component status() {
        return Component.translatable(
                switch (this.result) {
                    case DEFUSED -> "gui.cbc_more_content.c4.wires.defused";
                    case DETONATED -> "gui.cbc_more_content.c4.wires.detonated";
                    case NOTHING -> "gui.cbc_more_content.c4.wires.hint";
                    default -> "gui.cbc_more_content.c4.wires.accelerated";
                });
    }

    @Override
    protected int statusColour() {
        return switch (this.result) {
            case DEFUSED -> Ui.GREEN;
            case DETONATED -> Ui.RED;
            case NOTHING -> Ui.RED;
            default -> Ui.AMBER;
        };
    }

    @Override
    protected float statusPulse(float now) {
        return this.result == WireResult.NOTHING ? 0.5f + 0.5f * Mth.sin(now * 0.4f) : 1;
    }

    @Override
    protected int lampColour() {
        return this.result == WireResult.DEFUSED ? Ui.GREEN : Ui.RED;
    }

    @Override
    protected float lampPulse(float now) {
        return this.result == WireResult.DEFUSED ? 0 : 0.5f + 0.5f * Mth.sin(now * 0.4f);
    }

    private boolean alreadyCut(int wire) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(this.pos) instanceof C4BlockEntity c4 && c4.isWireCut(wire);
    }

    private int colourOf(int wire) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(this.pos) instanceof C4BlockEntity c4) {
            return c4.wireColour(wire);
        }
        return C4BlockEntity.WIRE_PALETTE[wire % C4BlockEntity.WIRE_PALETTE.length];
    }

    private boolean overWire(double mouseX, double mouseY, int wire) {
        int x = this.x(WIRE_X);
        int y = this.wireY(wire);
        return mouseX >= x - 9 && mouseX <= x + WIRE_W + 9 && mouseY >= y - 4 && mouseY <= y + WIRE_H + 4;
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
                UiSounds.press(1.5f);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
