package com.cbc_more_content.client.sound;

import com.cbc_more_content.registry.ModSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Sound carried by a Breaker of Skies record lying on the ground or falling through the
 * air. Distance is attenuated here rather than by the engine so the theme reaches a
 * little further than a normal block sound while staying quiet up close.
 */
@OnlyIn(Dist.CLIENT)
public final class DroppedDiscSoundInstance extends AbstractTickableSoundInstance {
    private static final int FADE_OUT_TICKS = 8;

    private final ItemEntity disc;
    private final Role role;

    private int fadeTicks;

    public DroppedDiscSoundInstance(ItemEntity disc, Role role) {
        super(role.sound(), SoundSource.RECORDS,
                RandomSource.create((long) disc.getId() * 31L + role.ordinal()));
        this.disc = disc;
        this.role = role;
        this.looping = role.loops;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.01f;
        this.pitch = role.pitch;
        this.updatePosition();
    }

    public ItemEntity disc() {
        return this.disc;
    }

    public Role role() {
        return this.role;
    }

    @Override
    public void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || this.disc.isRemoved()
                || this.disc.level() != player.level()
                || (this.role == Role.WHISTLE && !isFalling(this.disc))) {
            this.fadeOut();
            return;
        }

        this.updatePosition();
        double distance = player.getEyePosition().distanceTo(this.disc.position());
        if (distance > this.role.range) {
            this.fadeOut();
            return;
        }

        this.fadeTicks = 0;
        float proximity = Mth.clamp(1.0f - (float) (distance / this.role.range), 0.0f, 1.0f);
        // Squared falloff: audible from across a clearing, never loud enough to
        // compete with a jukebox standing next to it.
        float target = this.role.maxVolume * proximity * proximity;
        this.volume = Mth.lerp(0.2f, this.volume, target);
    }

    private void fadeOut() {
        this.fadeTicks++;
        this.volume *= 0.7f;
        if (this.fadeTicks >= FADE_OUT_TICKS || this.volume < 0.002f) {
            this.stop();
        }
    }

    private void updatePosition() {
        this.x = (float) this.disc.getX();
        this.y = (float) this.disc.getY();
        this.z = (float) this.disc.getZ();
    }

    /** A record still dropping through the air, not one resting on the ground. */
    public static boolean isFalling(ItemEntity disc) {
        return !disc.onGround() && !disc.isInWater() && disc.getDeltaMovement().y < -0.08D;
    }

    public enum Role {
        /** The main theme. Audible only standing right over the record. */
        THEME(0.30f, 3.0D, 1.0f, true),
        /** Bomb whistle while it falls — a touch louder, and carries a little further. */
        WHISTLE(0.42f, 6.0D, 1.22f, true);

        private final float maxVolume;
        private final double range;
        private final float pitch;
        private final boolean loops;

        Role(float maxVolume, double range, float pitch, boolean loops) {
            this.maxVolume = maxVolume;
            this.range = range;
            this.pitch = pitch;
            this.loops = loops;
        }

        SoundEvent sound() {
            return this == THEME
                    ? ModSounds.MUSIC_BREAKER_OF_SKIES.get()
                    : ModSounds.BOMB_FALLING.get();
        }
    }
}
