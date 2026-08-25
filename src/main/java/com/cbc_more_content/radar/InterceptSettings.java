package com.cbc_more_content.radar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * What a radar network is allowed to hand a missile.
 * <p>
 * Kept on our side rather than read out of Create Radar's own filter slots. Those slots
 * hold Radar's filter items in a private inventory with no published accessor, so reading
 * them would mean reflecting into fields that are free to change on any update — the kind
 * of compat that breaks silently and blames the wrong mod. These are our conditions, they
 * are ours to keep working, and they sit alongside Radar's filters rather than fighting
 * them: Radar decides what its network sees at all, this decides what is worth a missile.
 */
public record InterceptSettings(float minSpeed, int maxRange, boolean hullsOnly) {
    /** Blocks per tick. Below this a contact is parked or drifting, not worth a missile. */
    public static final float MIN_SPEED_FLOOR = 0.0f;
    public static final float MIN_SPEED_CEILING = 2.0f;
    public static final int RANGE_FLOOR = 32;
    public static final int RANGE_CEILING = 400;

    public static final InterceptSettings DEFAULT = new InterceptSettings(0.05f, 400, false);

    public InterceptSettings {
        minSpeed = Mth.clamp(minSpeed, MIN_SPEED_FLOOR, MIN_SPEED_CEILING);
        maxRange = Mth.clamp(maxRange, RANGE_FLOOR, RANGE_CEILING);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("MinSpeed", this.minSpeed);
        tag.putInt("MaxRange", this.maxRange);
        tag.putBoolean("HullsOnly", this.hullsOnly);
        return tag;
    }

    public static InterceptSettings load(CompoundTag tag) {
        return new InterceptSettings(
                tag.contains("MinSpeed") ? tag.getFloat("MinSpeed") : DEFAULT.minSpeed(),
                tag.contains("MaxRange") ? tag.getInt("MaxRange") : DEFAULT.maxRange(),
                tag.getBoolean("HullsOnly"));
    }
}
