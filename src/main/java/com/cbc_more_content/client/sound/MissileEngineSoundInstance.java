package com.cbc_more_content.client.sound;

import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * One looping engine voice riding on a cruise missile: the booster roar or the turbofan.
 * <p>
 * Distance is attenuated here rather than by the engine, so the booster carries across a
 * valley while the much quieter sustainer is only heard as the round goes past. Pitch
 * follows the closing speed, which is what makes an inbound missile rise and an outbound
 * one sag.
 */
public final class MissileEngineSoundInstance extends AbstractTickableSoundInstance {
    private static final int FADE_OUT_TICKS = 10;

    private final CruiseMissileProjectile missile;
    private final boolean booster;
    private final float detune;
    private Vec3 previous;
    private int fadeTicks;

    public MissileEngineSoundInstance(CruiseMissileProjectile missile, boolean booster) {
        super(
                booster ? ModSounds.CRUISE_MISSILE_BOOSTER.get() : ModSounds.CRUISE_MISSILE_TURBOFAN.get(),
                SoundSource.HOSTILE,
                RandomSource.create(missile.getId() * 977L + (booster ? 1 : 0)));
        this.missile = missile;
        this.booster = booster;
        this.detune = (((missile.getId() * 41) & 15) - 7.5f) / 300.0f;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.01f;
        this.pitch = 1.0f;
        this.previous = missile.position();
        this.x = missile.getX();
        this.y = missile.getY();
        this.z = missile.getZ();
    }

    public CruiseMissileProjectile missile() {
        return this.missile;
    }

    public boolean isBooster() {
        return this.booster;
    }

    /** Whether this voice belongs to the phase the missile is in right now. */
    public static boolean wanted(CruiseMissileProjectile missile, boolean booster) {
        byte phase = missile.phase();
        if (!missile.isPowered() || missile.isEjecting()) {
            return false;
        }
        return booster
                ? phase == CruiseMissileProjectile.PHASE_BOOST
                : phase == CruiseMissileProjectile.PHASE_CRUISE || phase == CruiseMissileProjectile.PHASE_TERMINAL;
    }

    public static double range(boolean booster) {
        return booster ? 300.0D : 170.0D;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || this.missile.isRemoved()
                || this.missile.level() != mc.player.level()
                || !wanted(this.missile, this.booster)) {
            this.fadeOut();
            return;
        }
        Vec3 at = this.missile.position();
        Vec3 velocity = at.subtract(this.previous);
        this.previous = at;
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;

        Vec3 listener = mc.player.getEyePosition();
        Vec3 toListener = listener.subtract(at);
        double distance = toListener.length();
        double range = range(this.booster);
        if (distance > range) {
            this.fadeOut();
            return;
        }
        this.fadeTicks = 0;
        float proximity = (float) Mth.clamp(1.0D - distance / range, 0.0D, 1.0D);
        float target = (this.booster ? 1.0f : 0.85f) * (float) Math.pow(proximity, this.booster ? 1.35D : 1.8D);
        // Positive when closing on the listener.
        double closing = distance > 1.0E-3D
                ? velocity.subtract(mc.player.getDeltaMovement()).dot(toListener.scale(1.0D / distance))
                : 0.0D;
        float doppler = (float) Mth.clamp(1.0D + closing * 0.055D, 0.78D, 1.28D);
        float base = this.booster ? 0.92f : 1.0f + (float) Mth.clamp((velocity.length() - 3.5D) * 0.06D, -0.12D, 0.08D);
        this.volume = Mth.lerp(0.3f, this.volume, Mth.clamp(target, 0.0f, 1.0f));
        this.pitch = Mth.lerp(0.25f, this.pitch, Mth.clamp((base + this.detune) * doppler, 0.5f, 1.8f));
    }

    private void fadeOut() {
        this.fadeTicks++;
        this.volume *= 0.7f;
        if (this.fadeTicks >= FADE_OUT_TICKS || this.volume < 0.002f) {
            this.stop();
        }
    }
}
