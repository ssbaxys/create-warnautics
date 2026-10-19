package com.cbc_more_content.item;

import com.cbc_more_content.block.C4BlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Settings carried on a C4 stack, chosen before the throw (sneak and use it in the air), so
 * a charge thrown at a wall needs nobody to walk up to it and work its keypad.
 *
 * @param activation {@link #OFF} plain charge, {@link #ON_THROW} live the moment it is
 *                   thrown, {@link #MANUAL} lands set up but idle, started with one click
 *                   of the settings key
 * @param remote     waits on a detonator rather than running its own fuse
 * @param seconds    the fuse, for a charge on its own clock
 * @param code       the four-digit code that disarms it, 0..9999
 */
public record C4Preset(byte activation, boolean remote, int seconds, int code) {
    public static final byte OFF = 0;
    public static final byte ON_THROW = 1;
    public static final byte MANUAL = 2;
    public static final int MAX_CODE = 9999;
    public static final C4Preset NONE = new C4Preset(OFF, false, C4BlockEntity.DEFAULT_SECONDS, -1);
    private static final String KEY = "C4Preset";

    public C4Preset {
        activation = (byte) Mth.clamp(activation, OFF, MANUAL);
        seconds = Mth.clamp(seconds, C4BlockEntity.MIN_SECONDS, C4BlockEntity.MAX_SECONDS);
        code = code < 0 || code > MAX_CODE ? -1 : code;
        // A preset that would leave a live charge nobody can disarm is no preset.
        if (code < 0) {
            activation = OFF;
        }
    }

    public boolean active() {
        return this.activation != OFF;
    }

    public static C4Preset of(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) {
            return NONE;
        }
        CompoundTag tag = data.copyTag().getCompound(KEY);
        return new C4Preset(
                tag.getByte("Activation"),
                tag.getBoolean("Remote"),
                tag.contains("Seconds") ? tag.getInt("Seconds") : C4BlockEntity.DEFAULT_SECONDS,
                tag.contains("Code") ? tag.getInt("Code") : -1);
    }

    /** Puts this preset on the stack, or takes any preset off it when this one is off. */
    public void store(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = data == null ? new CompoundTag() : data.copyTag();
        if (!this.active()) {
            root.remove(KEY);
        } else {
            CompoundTag tag = new CompoundTag();
            tag.putByte("Activation", this.activation);
            tag.putBoolean("Remote", this.remote);
            tag.putInt("Seconds", this.seconds);
            tag.putInt("Code", this.code);
            root.put(KEY, tag);
        }
        if (root.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
    }
}
