package com.cbc_more_content.client.gui.kit;

import com.cbc_more_content.ui.UiTone;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The Warnautics interface kit: one palette and the handful of shapes every screen is
 * built from.
 * <p>
 * The look is a piece of field equipment. An olive case with a bevelled lid and rivets,
 * the colours read straight off the original panel atlas; dark recessed wells for
 * anything that shows a value; raised controls that sit on the case or inside a well;
 * and amber for whatever is lit. Every screen, the HUD and the notices use these and
 * nothing else, so they read as parts of the same kit rather than as ten separate ideas.
 */
@OnlyIn(Dist.CLIENT)
public final class Ui {
    // Case, from the atlas.
    public static final int OUTLINE = 0xFF121411;
    public static final int CASE = 0xFF4E5940;
    public static final int CASE_LIGHT = 0xFF7F8672;
    public static final int CASE_SHADE = 0xFF263638;
    public static final int CASE_CORNER = 0xFF414B30;
    public static final int RIVET = 0xFF8C928C;
    public static final int RIVET_SHADE = 0xFF2E3526;

    // Recessed wells.
    public static final int WELL = 0xFF141613;
    public static final int WELL_LIP = 0xFF1F231D;
    public static final int WELL_LINE = 0xFF2A3026;

    // Raised controls.
    public static final int CONTROL = 0xFF3A3F34;
    public static final int CONTROL_HOT = 0xFF4A5042;
    public static final int CONTROL_LIGHT = 0xFF5F6853;
    public static final int CONTROL_SHADE = 0xFF23271F;
    public static final int CONTROL_DEAD = 0xFF292D25;
    public static final int BRASS = 0xFFB08A3E;
    public static final int BRASS_LIGHT = 0xFFD9B66A;
    public static final int BRASS_SHADE = 0xFF6E5424;
    public static final int ALARM = 0xFF6A2A20;
    public static final int ALARM_HOT = 0xFF83362A;
    public static final int ALARM_LIGHT = 0xFFA4523F;

    // Text.
    public static final int TEXT = 0xFFE8E2CF;
    public static final int TEXT_MUTED = 0xFF9AA08C;
    public static final int TEXT_DIM = 0xFF5F6656;
    public static final int TEXT_ON_BRASS = 0xFF1A160C;
    public static final int AMBER = UiTone.ACCENT.argb();
    public static final int GREEN = UiTone.SUCCESS.argb();
    public static final int RED = UiTone.DANGER.argb();
    /** Scope phosphor, for screens that draw a radar picture. */
    public static final int PHOSPHOR = 0xFF8EBD94;

    public static final int PHOSPHOR_DIM = 0xFF2F4A3A;

    /** Height of the lid the title sits on. */
    public static final int LID = 15;
    /** Inner margin of the case body. */
    public static final int MARGIN = 8;
    /** Standard heights, so rows line up from one screen to the next. */
    public static final int BUTTON_H = 18;

    public static final int ROW_H = 18;
    public static final int STATUS_H = 13;

    private Ui() {}

    // ---------------------------------------------------------------- case

    /**
     * The whole case: lid with title, body, bevels and rivets. The lid overhangs the
     * body by a pixel on each side, the way the atlas draws it.
     */
    public static void panel(GuiGraphics g, Font font, int x, int y, int w, int h, Component title) {
        // Body.
        int bx = x + 1;
        int bw = w - 2;
        g.fill(bx, y + LID - 1, bx + bw, y + h, OUTLINE);
        g.fill(bx + 1, y + LID, bx + bw - 1, y + h - 1, CASE);
        g.fill(bx + 1, y + LID, bx + 2, y + h - 2, CASE_LIGHT);
        g.fill(bx + bw - 2, y + LID, bx + bw - 1, y + h - 2, CASE_LIGHT);
        g.fill(bx + 2, y + h - 2, bx + bw - 2, y + h - 1, CASE_SHADE);
        g.fill(bx + 1, y + h - 2, bx + 2, y + h - 1, CASE_CORNER);
        g.fill(bx + bw - 2, y + h - 2, bx + bw - 1, y + h - 1, CASE_CORNER);
        g.fill(bx + 2, y + LID, bx + bw - 2, y + LID + 1, CASE_SHADE);

        // Lid.
        g.fill(x, y, x + w, y + LID, OUTLINE);
        g.fill(x + 1, y + 1, x + w - 1, y + LID - 1, CASE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, CASE_LIGHT);
        g.fill(x + 1, y + 1, x + 2, y + LID - 1, CASE_LIGHT);
        g.fill(x + w - 2, y + 2, x + w - 1, y + LID - 1, CASE_SHADE);
        g.fill(x + 2, y + LID - 2, x + w - 1, y + LID - 1, CASE_SHADE);

        rivet(g, bx + 4, y + LID + 3);
        rivet(g, bx + bw - 6, y + LID + 3);
        rivet(g, bx + 4, y + h - 6);
        rivet(g, bx + bw - 6, y + h - 6);

        fitCentered(g, font, title, x + w / 2, y + 4, w - 28, TEXT, true);
    }

    public static void rivet(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 2, y + 2, RIVET);
        g.fill(x + 1, y + 2, x + 2, y + 3, RIVET_SHADE);
        g.fill(x + 2, y + 1, x + 3, y + 3, RIVET_SHADE);
    }

    /** A small indicator lamp on the lid, top right: the screen's state at a glance. */
    public static void lidLamp(GuiGraphics g, int panelX, int panelY, int panelW, int colour, float pulse) {
        int x = panelX + panelW - 11;
        int y = panelY + 4;
        lamp(g, x, y, colour, pulse);
    }

    /**
     * A 5×5 lamp. {@code pulse} 0..1 adds a soft halo, so a live state can breathe
     * without the lamp itself flickering.
     */
    public static void lamp(GuiGraphics g, int x, int y, int colour, float pulse) {
        g.fill(x - 1, y - 1, x + 6, y + 6, OUTLINE);
        g.fill(x, y, x + 5, y + 5, darken(colour, 0.55f));
        g.fill(x + 1, y + 1, x + 4, y + 4, colour);
        g.fill(x + 1, y + 1, x + 2, y + 2, lighten(colour, 0.5f));
        if (pulse > 0) {
            int halo = ((int) (Mth.clamp(pulse, 0, 1) * 70) << 24) | (colour & 0xFFFFFF);
            g.fill(x - 2, y + 1, x - 1, y + 4, halo);
            g.fill(x + 6, y + 1, x + 7, y + 4, halo);
            g.fill(x + 1, y - 2, x + 4, y - 1, halo);
            g.fill(x + 1, y + 6, x + 4, y + 7, halo);
        }
    }

    // ---------------------------------------------------------------- wells

    /** A recessed well: shadowed top and left, a lit lip at the bottom. */
    public static void well(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, OUTLINE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, WELL);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, WELL_LIP);
        g.fill(x, y + h - 1, x + w, y + h, CASE_LIGHT);
        g.fill(x + w - 1, y + 1, x + w, y + h, CASE_LIGHT);
    }

    /** A round well, for dials. */
    public static void roundWell(GuiGraphics g, int cx, int cy, int radius) {
        disc(g, cx, cy + 1, radius + 1, CASE_LIGHT);
        disc(g, cx, cy, radius + 1, OUTLINE);
        disc(g, cx, cy, radius, WELL_LIP);
        disc(g, cx, cy + 1, radius - 1, WELL);
    }

    /** A filled disc drawn as horizontal spans; crisp at any GUI scale. */
    public static void disc(GuiGraphics g, int cx, int cy, int radius, int colour) {
        for (int dy = -radius; dy <= radius; dy++) {
            int span = (int) Math.floor(Math.sqrt(radius * radius - dy * dy + radius * 0.8));
            g.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, colour);
        }
    }

    /** A hairline divider etched into the case. */
    public static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, CASE_SHADE);
        g.fill(x, y + 1, x + w, y + 2, CASE_LIGHT);
    }

    /**
     * The status line along the foot of a screen: a narrow well with a lamp and one line
     * of text. Every screen has one, in the same place, so the eye learns where to look
     * for "what happens next".
     */
    public static void statusLine(
            GuiGraphics g, Font font, int x, int y, int w, Component text, int lampColour, float pulse) {
        well(g, x, y, w, STATUS_H);
        lamp(g, x + 4, y + 4, lampColour, pulse);
        fitLeft(g, font, text, x + 14, y + 3, w - 18, TEXT_MUTED, false);
    }

    // ---------------------------------------------------------------- HUD

    /**
     * The HUD plate shared by notices and the designator readout: a dark translucent strip
     * with a lamp in the tone colour, a tone-coloured foot and one line of text.
     *
     * @param progress  0..1 fills the foot as a progress bar; negative for a plain foot
     * @param flare     0..1 halo around the lamp, for a moment after something changes
     * @return the plate's width
     */
    public static int hudPlate(
            GuiGraphics g,
            Font font,
            int centreX,
            int y,
            Component text,
            int tone,
            float alpha,
            float progress,
            float flare,
            int maxWidth) {
        int textWidth = Math.min(font.width(text), maxWidth);
        int width = textWidth + 22;
        int x = centreX - width / 2;
        int h = 15;
        g.fill(x, y, x + width, y + h, withAlpha(OUTLINE, alpha * 0.92f));
        g.fill(x + 1, y + 1, x + width - 1, y + h - 1, withAlpha(WELL, alpha * 0.88f));
        if (progress < 0) {
            g.fill(x + 1, y + h - 2, x + width - 1, y + h - 1, withAlpha(tone, alpha * 0.55f));
        } else {
            g.fill(x + 1, y + h - 2, x + width - 1, y + h - 1, withAlpha(WELL_LINE, alpha));
            int fill = Math.round((width - 2) * Mth.clamp(progress, 0, 1));
            g.fill(x + 1, y + h - 2, x + 1 + fill, y + h - 1, withAlpha(tone, alpha));
        }
        int lampX = x + 5;
        int lampY = y + 5;
        g.fill(lampX - 1, lampY - 1, lampX + 6, lampY + 6, withAlpha(OUTLINE, alpha));
        g.fill(lampX, lampY, lampX + 5, lampY + 5, withAlpha(darken(tone, 0.55f), alpha));
        g.fill(lampX + 1, lampY + 1, lampX + 4, lampY + 4, withAlpha(tone, alpha));
        if (flare > 0) {
            int halo = withAlpha(tone, alpha * Mth.clamp(flare, 0, 1) * 0.5f);
            g.fill(lampX - 2, lampY + 1, lampX - 1, lampY + 4, halo);
            g.fill(lampX + 6, lampY + 1, lampX + 7, lampY + 4, halo);
            g.fill(lampX + 1, lampY - 2, lampX + 4, lampY - 1, halo);
            g.fill(lampX + 1, lampY + 6, lampX + 4, lampY + 7, halo);
        }
        fitLeft(g, font, text, x + 16, y + 4, textWidth, withAlpha(TEXT, alpha), false);
        return width;
    }

    // ---------------------------------------------------------------- controls

    /** Visual state of a raised control. */
    public enum Face {
        NORMAL,
        HOT,
        PRESSED,
        DEAD
    }

    /** Raised plate in the neutral control colour. */
    public static void plate(GuiGraphics g, int x, int y, int w, int h, Face face) {
        int body =
                switch (face) {
                    case HOT -> CONTROL_HOT;
                    case DEAD -> CONTROL_DEAD;
                    default -> CONTROL;
                };
        raised(g, x, y, w, h, body, face == Face.DEAD ? CONTROL : CONTROL_LIGHT, CONTROL_SHADE, face == Face.PRESSED);
    }

    /** Raised plate in brass: the primary action on a screen. */
    public static void brassPlate(GuiGraphics g, int x, int y, int w, int h, Face face) {
        if (face == Face.DEAD) {
            plate(g, x, y, w, h, Face.DEAD);
            return;
        }
        raised(
                g,
                x,
                y,
                w,
                h,
                face == Face.HOT ? lighten(BRASS, 0.12f) : BRASS,
                BRASS_LIGHT,
                BRASS_SHADE,
                face == Face.PRESSED);
    }

    /** Raised plate in alarm red: an action that cannot be taken back. */
    public static void alarmPlate(GuiGraphics g, int x, int y, int w, int h, Face face) {
        if (face == Face.DEAD) {
            plate(g, x, y, w, h, Face.DEAD);
            return;
        }
        raised(g, x, y, w, h, face == Face.HOT ? ALARM_HOT : ALARM, ALARM_LIGHT, OUTLINE, face == Face.PRESSED);
    }

    private static void raised(
            GuiGraphics g, int x, int y, int w, int h, int body, int light, int shade, boolean pressed) {
        g.fill(x, y, x + w, y + h, OUTLINE);
        if (pressed) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, darken(body, 0.85f));
            g.fill(x + 1, y + 1, x + w - 1, y + 2, shade);
            return;
        }
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, body);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        g.fill(x + 1, y + 2, x + 2, y + h - 2, light);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, shade);
    }

    /** One-pixel focus frame, drawn only when the keyboard put the focus there. */
    public static void focusRing(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 2, y - 2, x + w + 2, y - 1, AMBER);
        g.fill(x - 2, y + h + 1, x + w + 2, y + h + 2, AMBER);
        g.fill(x - 2, y - 1, x - 1, y + h + 1, AMBER);
        g.fill(x + w + 1, y - 1, x + w + 2, y + h + 1, AMBER);
    }

    // ---------------------------------------------------------------- text

    /** Label printed on the case: bone with a dark stencil shadow. */
    public static void caseLabel(GuiGraphics g, Font font, Component text, int x, int y, int maxWidth) {
        fitLeft(g, font, text, x, y, maxWidth, TEXT, true);
    }

    /**
     * Draws a line left-aligned, scaled down rather than clipped when a translation runs
     * longer than the room it was given.
     */
    public static void fitLeft(
            GuiGraphics g, Font font, Component text, int x, int y, int maxWidth, int colour, boolean shadow) {
        int width = font.width(text);
        if (width <= maxWidth || maxWidth <= 0) {
            g.drawString(font, text, x, y, colour, shadow);
            return;
        }
        float scale = maxWidth / (float) width;
        g.pose().pushPose();
        g.pose().translate(x, y + (1 - scale) * 4, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, colour, shadow);
        g.pose().popPose();
    }

    public static void fitRight(
            GuiGraphics g, Font font, Component text, int right, int y, int maxWidth, int colour, boolean shadow) {
        int width = Math.min(font.width(text), maxWidth);
        fitLeft(g, font, text, right - width, y, maxWidth, colour, shadow);
    }

    public static void fitCentered(
            GuiGraphics g, Font font, Component text, int cx, int y, int maxWidth, int colour, boolean shadow) {
        int width = Math.min(font.width(text), maxWidth);
        fitLeft(g, font, text, cx - width / 2, y, maxWidth, colour, shadow);
    }

    /**
     * Wrapped paragraph, at most {@code lines} lines; returns the height used. Text printed
     * straight on the case gets the stencil shadow, text inside a well does not.
     */
    public static int paragraph(
            GuiGraphics g, Font font, Component text, int x, int y, int width, int lines, int colour, boolean shadow) {
        var split = font.split(text, width);
        int shown = Math.min(lines, split.size());
        for (int i = 0; i < shown; i++) {
            FormattedCharSequence line = split.get(i);
            g.drawString(font, line, x, y + i * 10, colour, shadow);
        }
        return shown * 10;
    }

    /** A large readout: the font at twice its size, centred, for the one number that matters. */
    public static void readout(GuiGraphics g, Font font, Component text, int cx, int cy, int maxWidth, int colour) {
        float scale = Math.min(2.0f, maxWidth / (float) Math.max(1, font.width(text)));
        g.pose().pushPose();
        g.pose().translate(cx, cy - 4 * scale, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, colour, false);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- colour

    public static int darken(int argb, float factor) {
        int a = argb >>> 24;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int gr = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    public static int lighten(int argb, float amount) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int gr = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        gr += (int) ((255 - gr) * amount);
        b += (int) ((255 - b) * amount);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    public static int withAlpha(int argb, float alpha) {
        return ((int) (Mth.clamp(alpha, 0, 1) * 255) << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int from, int to, float t) {
        t = Mth.clamp(t, 0, 1);
        int a = (int) Mth.lerp(t, from >>> 24, to >>> 24);
        int r = (int) Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int gr = (int) Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    /** Smoothstep-style ease out, for anything that slides into place. */
    public static float easeOut(float t) {
        t = Mth.clamp(t, 0, 1);
        float inv = 1 - t;
        return 1 - inv * inv * inv;
    }
}
