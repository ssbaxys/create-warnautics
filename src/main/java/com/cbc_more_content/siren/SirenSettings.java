package com.cbc_more_content.siren;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * What sets a siren off, how far it watches, and how long it keeps wailing afterwards.
 * <p>
 * All of it is per-siren rather than a config: a post on a forward battery and one over a
 * dockyard are watching for different things at different ranges, and both are placed by
 * the same person with the same key in hand.
 */
public record SirenSettings(boolean auto, int radius, int lingerSeconds, boolean watchMissiles, boolean watchBombs) {
    /** Below this it would not hear anything before the blast arrived anyway. */
    public static final int RADIUS_FLOOR = 16;
    /** Past this a siren watches further than the server keeps chunks loaded. */
    public static final int RADIUS_CEILING = 256;

    public static final int LINGER_FLOOR = 0;
    /** Five minutes of all-clear is already a long time to stand under a siren. */
    public static final int LINGER_CEILING = 300;

    public static final SirenSettings DEFAULT = new SirenSettings(true, 96, 45, true, true);

    public SirenSettings {
        radius = Mth.clamp(radius, RADIUS_FLOOR, RADIUS_CEILING);
        lingerSeconds = Mth.clamp(lingerSeconds, LINGER_FLOOR, LINGER_CEILING);
    }

    /** Whether anything at all would trip this siren on its own. */
    public boolean watchesAnything() {
        return this.auto && (this.watchMissiles || this.watchBombs);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Auto", this.auto);
        tag.putInt("Radius", this.radius);
        tag.putInt("Linger", this.lingerSeconds);
        tag.putBoolean("WatchMissiles", this.watchMissiles);
        tag.putBoolean("WatchBombs", this.watchBombs);
        return tag;
    }

    public static SirenSettings load(CompoundTag tag) {
        if (!tag.contains("Radius")) {
            return DEFAULT;
        }
        return new SirenSettings(
                tag.getBoolean("Auto"),
                tag.getInt("Radius"),
                tag.getInt("Linger"),
                tag.getBoolean("WatchMissiles"),
                tag.getBoolean("WatchBombs"));
    }
}
