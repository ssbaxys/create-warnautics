package com.cbc_more_content.client.gui.kit;

import com.cbc_more_content.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The kit's sounds. Every control in every screen speaks with one of these five, so a
 * switch sounds like a switch wherever it is.
 * <p>
 * Played as UI sounds rather than at the player, so they are not positional, are not
 * heard by anyone else, and follow the master volume the way menu clicks do.
 */
@OnlyIn(Dist.CLIENT)
public final class UiSounds {
    private UiSounds() {}

    /** A key or a button. */
    public static void press(float pitch) {
        play(ModSounds.C4_BUTTON.get(), 0.65f, pitch);
    }

    /** A switch thrown on or off: higher going on, lower going off. */
    public static void toggle(boolean on) {
        play(ModSounds.C4_BUTTON.get(), 0.6f, on ? 1.4f : 1.0f);
    }

    /** One detent of a slider or a dial; pitch rises with the value. */
    public static void detent(float progress) {
        play(SoundEvents.STONE_BUTTON_CLICK_ON, 0.22f, 1.05f + progress * 0.75f);
    }

    /** The screen's main action went through. */
    public static void confirm() {
        play(ModSounds.C4_BUTTON.get(), 0.8f, 0.85f);
    }

    /** Refused: a wrong code, an incomplete form. */
    public static void deny() {
        play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, 0.6f);
    }

    /** A heavy lever, for switches that change something for everyone. */
    public static void lever(boolean on) {
        play(SoundEvents.LEVER_CLICK, 0.8f, on ? 1.0f : 0.75f);
    }

    public static void play(SoundEvent sound, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
