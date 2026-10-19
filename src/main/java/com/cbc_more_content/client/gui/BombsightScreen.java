package com.cbc_more_content.client.gui;

import com.cbc_more_content.bombsight.BombsightBlockEntity;
import com.cbc_more_content.client.BombsightClient;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.gui.kit.UiButton;
import com.cbc_more_content.client.gui.kit.UiSegmented;
import com.cbc_more_content.client.gui.kit.UiSlider;
import com.cbc_more_content.client.gui.kit.UiSounds;
import com.cbc_more_content.client.gui.kit.UiToggle;
import com.cbc_more_content.client.gui.kit.WarnauticsScreen;
import com.cbc_more_content.network.BombsightSettingsPayload;
import com.cbc_more_content.network.BombsightSolutionPayload;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The bombsight's panel.
 * <p>
 * At the top a live scope: the ground below seen from above, track pointing up, with the
 * ring where a bomb would land now and the target, if one is set; beside it the height,
 * ground speed and fall time. Below, the mode, the target's coordinates (typed in, or taken
 * from where the bombs would fall right now), automatic release and the stick: how long the
 * release signal is held, and whether it is centred on the target.
 */
@OnlyIn(Dist.CLIENT)
public class BombsightScreen extends WarnauticsScreen {
    public static final int PANEL_WIDTH = 264;
    public static final int PANEL_HEIGHT = 262;
    private static final int SCOPE = 92;
    private static final int INFO_X = Ui.MARGIN + SCOPE + 8;
    private static final int INFO_W = PANEL_WIDTH - INFO_X - Ui.MARGIN;
    private static final int MODE_Y = 120;
    private static final int FIELDS_Y = 144;
    private static final int FIELD_W = 72;
    private static final int FIELD_H = 18;
    private static final int TOGGLES_Y = 168;
    private static final int SERIES_Y = 192;
    private static final String[] LABELS = {"X", "Z"};

    private final BlockPos pos;
    private byte mode;
    private boolean autoRelease;
    private int series;
    private boolean centred;
    private final String[] fields = {"", ""};
    private int active;
    private boolean sent;

    public BombsightScreen(BlockPos pos, @Nullable BombsightSolutionPayload known) {
        super(Component.translatable("gui.cbc_more_content.bombsight.title"), PANEL_WIDTH, PANEL_HEIGHT);
        this.pos = pos;
        if (known != null) {
            this.mode = known.mode();
            this.autoRelease = known.autoRelease();
            this.series = known.seriesTicks();
            this.centred = known.centred();
            if (known.hasTarget()) {
                this.fields[0] = Integer.toString(Mth.floor(known.target().x));
                this.fields[1] = Integer.toString(Mth.floor(known.target().z));
            }
        } else {
            this.autoRelease = true;
            this.series = BombsightBlockEntity.DEFAULT_SERIES;
            this.centred = true;
        }
        this.series = Mth.clamp(this.series, BombsightBlockEntity.MIN_SERIES, BombsightBlockEntity.MAX_SERIES);
    }

    /** Opened by the settings key, on the client. */
    public static void open(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof BombsightBlockEntity) {
            mc.setScreen(new BombsightScreen(pos, BombsightClient.latest(pos)));
        }
    }

    @Nullable
    private BombsightSolutionPayload live() {
        return BombsightClient.latest(this.pos);
    }

    @Override
    protected void build() {
        int w = PANEL_WIDTH - Ui.MARGIN * 2;
        this.addRenderableWidget(new UiSegmented(
                this.x(Ui.MARGIN),
                this.y(MODE_Y),
                w,
                Ui.ROW_H,
                List.of(
                        Component.translatable("gui.cbc_more_content.bombsight.mode.point"),
                        Component.translatable("gui.cbc_more_content.bombsight.mode.run")),
                () -> this.mode,
                index -> this.mode = (byte) index));
        int takeX = Ui.MARGIN + FIELD_W * 2 + 12;
        this.addRenderableWidget(new UiButton(
                this.x(takeX),
                this.y(FIELDS_Y),
                PANEL_WIDTH - takeX - Ui.MARGIN,
                UiButton.Kind.SECONDARY,
                () -> Component.translatable("gui.cbc_more_content.bombsight.take_impact"),
                this::takeImpact));
        int half = (w - 6) / 2;
        this.addRenderableWidget(new UiToggle(
                this.x(Ui.MARGIN),
                this.y(TOGGLES_Y),
                half,
                () -> Component.translatable("gui.cbc_more_content.bombsight.auto"),
                () -> this.autoRelease,
                () -> this.autoRelease = !this.autoRelease));
        this.addRenderableWidget(new UiToggle(
                this.x(Ui.MARGIN + half + 6),
                this.y(TOGGLES_Y),
                w - half - 6,
                () -> Component.translatable("gui.cbc_more_content.bombsight.centred"),
                () -> this.centred,
                () -> this.centred = !this.centred));
        this.addRenderableWidget(new UiSlider(
                this.x(Ui.MARGIN),
                this.y(SERIES_Y),
                w,
                Component.translatable("gui.cbc_more_content.bombsight.series_label"),
                BombsightBlockEntity.MAX_SERIES - BombsightBlockEntity.MIN_SERIES,
                9,
                () -> this.series - BombsightBlockEntity.MIN_SERIES,
                step -> this.series = BombsightBlockEntity.MIN_SERIES + step,
                step -> Component.translatable(
                        "gui.cbc_more_content.bombsight.series_value",
                        String.format(Locale.ROOT, "%.2f", (BombsightBlockEntity.MIN_SERIES + step) / 20.0D),
                        BombsightBlockEntity.MIN_SERIES + step)));
        this.addCancelApply(Component.translatable("gui.cbc_more_content.bombsight.apply"), this::apply);
    }

    /** The target becomes the spot the bombs would hit if let go right now. */
    private void takeImpact() {
        var s = this.live();
        if (s == null) {
            UiSounds.deny();
            return;
        }
        this.fields[0] = Integer.toString(Mth.floor(s.impact().x));
        this.fields[1] = Integer.toString(Mth.floor(s.impact().z));
        UiSounds.confirm();
    }

    @Override
    public void tick() {
        super.tick();
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(this.pos) instanceof BombsightBlockEntity)) {
            this.onClose();
        }
    }

    private boolean hasTarget() {
        return isNumber(this.fields[0]) && isNumber(this.fields[1]);
    }

    private static boolean isNumber(String field) {
        return !field.isEmpty() && !field.equals("-");
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick, float now) {
        var s = this.live();
        this.scope(g, s, now);
        this.info(g, s);
        for (int i = 0; i < 2; i++) {
            this.renderField(g, i, mouseX, mouseY, now);
        }
    }

    /**
     * The scope, as from above, the carrier's track pointing up: the impact ring at the
     * centre, the target where it lies from it, the scale fitted to keep it in view.
     */
    private void scope(GuiGraphics g, @Nullable BombsightSolutionPayload s, float now) {
        int x0 = this.x(Ui.MARGIN);
        int y0 = this.y(this.contentTop());
        Ui.well(g, x0, y0, SCOPE, SCOPE);
        int cx = x0 + SCOPE / 2;
        int cy = y0 + SCOPE / 2;
        int r = SCOPE / 2 - 6;
        // Range rings and the track line.
        for (int ring = 1; ring <= 2; ring++) {
            int rr = r * ring / 2;
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI * 2 / 48;
                g.fill(
                        cx + (int) Math.round(Math.cos(a) * rr),
                        cy + (int) Math.round(Math.sin(a) * rr),
                        cx + (int) Math.round(Math.cos(a) * rr) + 1,
                        cy + (int) Math.round(Math.sin(a) * rr) + 1,
                        Ui.PHOSPHOR_DIM);
            }
        }
        g.fill(cx, y0 + 4, cx + 1, y0 + SCOPE - 4, Ui.PHOSPHOR_DIM);
        if (s == null) {
            Ui.fitCentered(
                    g,
                    this.font,
                    Component.translatable("gui.cbc_more_content.bombsight.no_signal"),
                    cx,
                    cy - 4,
                    SCOPE - 8,
                    Ui.TEXT_DIM,
                    false);
            return;
        }
        // The sweep, for the look of a live display.
        double sweep = now * 0.12D;
        for (int i = 0; i < r; i += 2) {
            g.fill(
                    cx + (int) Math.round(Math.cos(sweep) * i),
                    cy + (int) Math.round(Math.sin(sweep) * i),
                    cx + (int) Math.round(Math.cos(sweep) * i) + 1,
                    cy + (int) Math.round(Math.sin(sweep) * i) + 1,
                    Ui.withAlpha(Ui.PHOSPHOR, 0.45f));
        }
        int ring = s.releasing() ? Ui.RED : s.landed() ? Ui.AMBER : Ui.TEXT_DIM;
        Ui.disc(g, cx, cy, 3, ring);
        g.fill(cx - 5, cy, cx - 3, cy + 1, ring);
        g.fill(cx + 4, cy, cx + 6, cy + 1, ring);

        Vec3 target = this.typedTarget(s);
        if (target != null) {
            double dx = target.x - s.impact().x;
            double dz = target.z - s.impact().z;
            double speed = Math.hypot(s.velocity().x, s.velocity().z);
            double ux = speed > 0.05D ? s.velocity().x / speed : 0;
            double uz = speed > 0.05D ? s.velocity().z / speed : -1;
            double ahead = dx * ux + dz * uz;
            double right = dx * -uz + dz * ux;
            double distance = Math.hypot(ahead, right);
            // Scale: the target on the outer ring, or closer, but no finer than 1 px per 2 blocks.
            double scale = Math.min(0.5D, r / Math.max(1, distance));
            int tx = cx + (int) Math.round(right * scale);
            int ty = cy - (int) Math.round(ahead * scale);
            boolean onTrack = Math.abs(right) <= BombsightBlockEntity.CROSS_TOLERANCE;
            int colour = onTrack ? Ui.GREEN : Ui.AMBER;
            g.fill(tx - 3, ty, tx + 4, ty + 1, colour);
            g.fill(tx, ty - 3, tx + 1, ty + 4, colour);
            Ui.fitCentered(
                    g,
                    this.font,
                    Component.translatable("gui.cbc_more_content.unit.meters", Math.round(distance)),
                    cx,
                    y0 + SCOPE - 11,
                    SCOPE - 6,
                    Ui.TEXT_MUTED,
                    false);
        }
    }

    /** The target as typed, at the height of the impact point, or null if incomplete. */
    @Nullable
    private Vec3 typedTarget(BombsightSolutionPayload s) {
        if (!this.hasTarget()) {
            return null;
        }
        return new Vec3(parse(this.fields[0]) + 0.5D, s.impact().y, parse(this.fields[1]) + 0.5D);
    }

    private void info(GuiGraphics g, @Nullable BombsightSolutionPayload s) {
        int x = this.x(INFO_X);
        int y = this.y(this.contentTop());
        Ui.well(g, x, y, INFO_W, SCOPE);
        if (s == null) {
            Ui.paragraph(
                    g,
                    this.font,
                    Component.translatable("gui.cbc_more_content.bombsight.waiting"),
                    x + 6,
                    y + 6,
                    INFO_W - 12,
                    8,
                    Ui.TEXT_DIM,
                    false);
            return;
        }
        double height = s.sightWorld().y - s.impact().y;
        double speed = Math.hypot(s.velocity().x, s.velocity().z) * 20;
        int row = y + 6;
        row = this.infoRow(g, x, row, "height", unit("meters", Math.round(height)), Ui.TEXT);
        row = this.infoRow(g, x, row, "speed", unit("mps", Math.round(speed)), Ui.TEXT);
        row = this.infoRow(
                g, x, row, "fall", unit("seconds", String.format(Locale.ROOT, "%.1f", s.fallTicks() / 20.0D)), Ui.TEXT);
        row = this.infoRow(
                g,
                x,
                row,
                "impact",
                Mth.floor(s.impact().x) + " / " + Mth.floor(s.impact().z),
                s.landed() ? Ui.AMBER : Ui.TEXT_DIM);
        row = this.infoRow(
                g,
                x,
                row,
                "rack",
                Component.translatable(
                                s.rack()
                                        ? "gui.cbc_more_content.bombsight.rack.found"
                                        : "gui.cbc_more_content.bombsight.rack.none")
                        .getString(),
                s.rack() ? Ui.GREEN : Ui.TEXT_DIM);
        Ui.paragraph(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.bombsight.key_hint"),
                x + 6,
                row + 3,
                INFO_W - 12,
                2,
                Ui.TEXT_DIM,
                false);
    }

    private static String unit(String key, Object value) {
        return Component.translatable("gui.cbc_more_content.unit." + key, value).getString();
    }

    private int infoRow(GuiGraphics g, int x, int y, String key, String value, int colour) {
        Ui.fitLeft(
                g,
                this.font,
                Component.translatable("gui.cbc_more_content.bombsight.info." + key),
                x + 6,
                y,
                INFO_W / 2 - 4,
                Ui.TEXT_MUTED,
                false);
        Ui.fitRight(g, this.font, Component.literal(value), x + INFO_W - 6, y, INFO_W / 2 - 4, colour, false);
        return y + 11;
    }

    private void renderField(GuiGraphics g, int index, int mouseX, int mouseY, float now) {
        int x = this.fieldX(index);
        int y = this.y(FIELDS_Y);
        boolean focused = index == this.active;
        boolean hot = this.over(mouseX, mouseY, x, y, FIELD_W, FIELD_H);
        Ui.well(g, x, y, FIELD_W, FIELD_H);
        if (hot && !focused) {
            g.fill(x + 1, y + 2, x + FIELD_W - 1, y + FIELD_H - 1, Ui.WELL_LINE);
        }
        g.fill(x + 2, y + FIELD_H - 3, x + FIELD_W - 2, y + FIELD_H - 2, focused ? Ui.AMBER : Ui.WELL_LINE);
        g.drawString(this.font, LABELS[index], x + 5, y + 5, focused ? Ui.AMBER : Ui.TEXT_DIM, false);
        String text = this.fields[index];
        boolean caret = focused && (int) (now / 8.0f) % 2 == 0;
        String shown = text.isEmpty() ? (focused ? "" : "—") : text;
        int textX = x + FIELD_W - 6 - this.font.width(shown + (focused ? "_" : ""));
        g.drawString(this.font, shown, textX, y + 5, focused ? Ui.AMBER : Ui.TEXT, false);
        if (caret) {
            g.drawString(this.font, "_", textX + this.font.width(shown), y + 5, Ui.AMBER, false);
        }
    }

    private int fieldX(int index) {
        return this.x(Ui.MARGIN + index * (FIELD_W + 6));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < 2; i++) {
            if (this.over(mouseX, mouseY, this.fieldX(i), this.y(FIELDS_Y), FIELD_W, FIELD_H)) {
                this.active = i;
                UiSounds.press(1.2f);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
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
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            String field = this.fields[this.active];
            if (!field.isEmpty()) {
                this.fields[this.active] = field.substring(0, field.length() - 1);
                UiSounds.press(0.9f);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            this.active = (this.active + 1) % 2;
            UiSounds.press(1.2f);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void append(String digit) {
        if (this.fields[this.active].length() < 9) {
            this.fields[this.active] += digit;
            UiSounds.press(1.0f + this.active * 0.06f);
        }
    }

    @Override
    protected Component status() {
        if (this.mode == 1 && !this.hasTarget()) {
            return Component.translatable("gui.cbc_more_content.bombsight.status.need_target");
        }
        if (this.mode == 1) {
            return Component.translatable(
                    this.autoRelease
                            ? "gui.cbc_more_content.bombsight.status.run_auto"
                            : "gui.cbc_more_content.bombsight.status.run_manual");
        }
        return Component.translatable("gui.cbc_more_content.bombsight.status.point");
    }

    @Override
    protected int statusColour() {
        return this.mode == 1 && !this.hasTarget() ? Ui.AMBER : Ui.GREEN;
    }

    @Override
    protected int lampColour() {
        var s = this.live();
        if (s != null && s.releasing()) {
            return Ui.RED;
        }
        return s == null ? Ui.CONTROL : this.mode == 1 ? Ui.AMBER : Ui.GREEN;
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
        this.sent = true;
        boolean target = this.hasTarget();
        PacketDistributor.sendToServer(new BombsightSettingsPayload(
                this.pos,
                this.mode,
                target,
                target ? parse(this.fields[0]) : 0,
                target ? parse(this.fields[1]) : 0,
                this.autoRelease,
                this.series,
                this.centred));
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
