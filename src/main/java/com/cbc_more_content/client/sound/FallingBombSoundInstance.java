package com.cbc_more_content.client.sound;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.munitions.SeaBombProjectile;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Positional, continuously-looped bomb whistle. Distance is attenuated here so
 * the sample recedes for a pilot above the bomb and approaches for somebody
 * near the impact point.
 */
public final class FallingBombSoundInstance extends AbstractTickableSoundInstance {
    private static final int FADE_OUT_TICKS = 6;

    private final LocalPlayer player;
    private final DropBombProjectile bomb;
    private final float detune;

    private float crowdGain = 1.0f;
    private float voiceGain = 1.0f;
    private boolean selected = true;
    private int age;
    private int fadeTicks;

    public FallingBombSoundInstance(LocalPlayer player, DropBombProjectile bomb) {
        super(
                ModSounds.BOMB_FALLING.get(),
                SoundSource.BLOCKS,
                RandomSource.create((long) bomb.getId() * 31L + 0x5741524eL));
        this.player = player;
        this.bomb = bomb;
        this.detune = (((bomb.getId() * 37) & 15) - 7.5f) / 240.0f;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.01f;
        this.pitch = basePitch(bomb.bombSize()) + this.detune;
        this.updatePosition();
    }

    public DropBombProjectile bomb() {
        return this.bomb;
    }

    public void select(float crowdGain, float voiceGain) {
        this.selected = true;
        this.crowdGain = Mth.clamp(crowdGain, 0.18f, 1.0f);
        this.voiceGain = Mth.clamp(voiceGain, 0.55f, 1.0f);
    }

    public void deselect() {
        this.selected = false;
    }

    @Override
    public void tick() {
        this.age++;
        this.updatePosition();

        if (!this.selected
                || !isAirborne(this.bomb)
                || this.player.isRemoved()
                || this.player.level() != this.bomb.level()) {
            this.fadeOut();
            return;
        }

        Vec3 listener = this.player.getEyePosition();
        Vec3 bombPos = this.bomb.position();
        Vec3 toListener = listener.subtract(bombPos);
        double distance = toListener.length();
        double range = range(this.bomb.bombSize());
        if (distance > range * 1.08D) {
            this.fadeOut();
            return;
        }

        this.fadeTicks = 0;
        Vec3 relativeVelocity = this.bomb.getDeltaMovement().subtract(this.player.getDeltaMovement());
        double speed = this.bomb.getDeltaMovement().length();
        double radialSpeed = distance > 1.0E-4D ? relativeVelocity.dot(toListener.scale(1.0D / distance)) : 0.0D;

        float proximity = Mth.clamp(1.0f - (float) (distance / range), 0.0f, 1.0f);
        float distanceEnvelope = 0.035f + 0.965f * (float) Math.pow(proximity, 0.72D);
        float speedEnvelope = Mth.clamp((float) (0.24D + speed * 0.72D), 0.24f, 1.0f);
        float attackBase = Mth.clamp((this.age + 1) / 4.0f, 0.45f, 1.0f);
        // A pilot dropping a bomb hears it immediately; a distant ground
        // observer still gets a gentle onset that grows with approach.
        float attack = Mth.lerp(proximity, attackBase, 1.0f);
        float approachPresence = Mth.clamp(1.0f + (float) radialSpeed * 0.10f, 0.78f, 1.16f);
        float targetVolume = maxVolume(this.bomb.bombSize())
                * distanceEnvelope
                * speedEnvelope
                * approachPresence
                * this.crowdGain
                * this.voiceGain
                * attack;
        targetVolume = Mth.clamp(targetVolume, 0.0f, 0.86f);

        // Positive radial speed means the bomb is moving toward the listener.
        float doppler = Mth.clamp((float) radialSpeed * 0.075f, -0.12f, 0.15f);
        float speedPitch = Mth.clamp((float) speed * 0.025f, 0.0f, 0.08f);
        float targetPitch =
                Mth.clamp(basePitch(this.bomb.bombSize()) + this.detune + doppler + speedPitch, 0.55f, 1.35f);

        this.volume = Mth.lerp(0.24f, this.volume, targetVolume);
        this.pitch = Mth.lerp(0.18f, this.pitch, targetPitch);
        this.selected = false; // The manager must renew each voice every tick.
    }

    private void fadeOut() {
        this.fadeTicks++;
        this.volume *= 0.62f;
        if (this.fadeTicks >= FADE_OUT_TICKS || this.volume < 0.002f) {
            this.stop();
        }
    }

    private void updatePosition() {
        this.x = (float) this.bomb.getX();
        this.y = (float) this.bomb.getY();
        this.z = (float) this.bomb.getZ();
    }

    public static boolean isAirborne(DropBombProjectile bomb) {
        if (bomb.isRemoved() || bomb.isInGround() || bomb.getDeltaMovement().lengthSqr() < 1.0E-4D) {
            return false;
        }
        return !(bomb instanceof SeaBombProjectile sea) || sea.phase() == SeaBombProjectile.PHASE_AIR;
    }

    public static double range(BombSize size) {
        return switch (size) {
            case SMALL -> 145.0D;
            case SEA -> 170.0D;
            case MEDIUM -> 225.0D;
            case LARGE -> 290.0D;
        };
    }

    public static float maxVolume(BombSize size) {
        return switch (size) {
            case SMALL -> 0.38f;
            case SEA -> 0.46f;
            case MEDIUM -> 0.61f;
            case LARGE -> 0.78f;
        };
    }

    private static float basePitch(BombSize size) {
        return switch (size) {
            case SMALL -> 1.08f;
            case SEA -> 1.0f;
            case MEDIUM -> 0.89f;
            case LARGE -> 0.78f;
        };
    }
}
