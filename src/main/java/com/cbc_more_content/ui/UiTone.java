package com.cbc_more_content.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The few meanings a Warnautics message or indicator can carry, each with one colour.
 * <p>
 * Shared by both sides: the server picks a tone when it reports something, and every
 * screen, notice and tooltip on the client draws that tone the same way. Keeping the
 * colours here, rather than reaching for whatever {@code ChatFormatting} looked close,
 * is what stops a "done" from being green in one item and aqua in the next.
 */
public enum UiTone {
    /** Something worked: armed, paired, set. */
    SUCCESS(0x8FD46A),
    /** Information about state: a mode entered, a count, an instruction. */
    INFO(0xE8E2CF),
    /** Attention: lit, live, armed, in progress. */
    ACCENT(0xFFB036),
    /** It did not happen, or it is dangerous. */
    DANGER(0xFF6250),
    /** Quiet: cancelled, cleared, nothing to report. */
    MUTED(0xA6AC98);

    private final int rgb;

    UiTone(int rgb) {
        this.rgb = rgb;
    }

    /** The tone's colour, without alpha. */
    public int rgb() {
        return this.rgb;
    }

    /** The tone's colour, fully opaque. */
    public int argb() {
        return 0xFF000000 | this.rgb;
    }

    public Style style() {
        return Style.EMPTY.withColor(TextColor.fromRgb(this.rgb));
    }

    /** A translated line in this tone. */
    public MutableComponent text(String key, Object... args) {
        return Component.translatable(key, args).withStyle(this.style());
    }

    /** Colours an existing line, keeping any styling of its own children. */
    public MutableComponent apply(Component text) {
        return text.copy().withStyle(this.style());
    }

    public static UiTone byId(int id) {
        UiTone[] values = values();
        return values[Math.floorMod(id, values.length)];
    }
}
