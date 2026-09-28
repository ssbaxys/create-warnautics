package com.cbc_more_content.client.sound;

import com.cbc_more_content.network.SirenWailPayload;
import com.cbc_more_content.registry.ModSounds;
import com.cbc_more_content.siren.SirenSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * One layer of a wailing post, held open for as long as the post is sounding.
 * <p>
 * Both layers run the whole time and are mixed here by distance rather than left to the
 * sound engine's own rolloff. Fired as two one-shots the way it was, the near layer went
 * silent at a fixed range while the far one was still at full strength, so walking away
 * from a siren did not fade it — it flipped, mid-note, from a wail into a rumble. Here the
 * two curves overlap across sixty blocks and neither ever reaches an edge.
 * <p>
 * The server renews a bounded lease and explicitly stops the sound when power or
 * drive is lost. This also works when the listener does not have the ship's blocks loaded.
 */
public final class SirenSoundInstance extends AbstractTickableSoundInstance {
    /** Loudest either layer gets at the listener. Attenuation is done here, not by the engine. */
    private static final float NEAR_MAX = 0.95f;

    private static final float FAR_MAX = 0.72f;
    /** The near layer is whole out to here, and gone by {@link #NEAR_EDGE}. */
    private static final float NEAR_FULL = 40.0f;

    private static final float NEAR_EDGE = 120.0f;
    /** The far layer is present under the wail from the start and takes over out here. */
    private static final float FAR_UNDER = 0.22f;

    private static final float FAR_FULL = 150.0f;
    private static final float FAR_FADE = 200.0f;
    private static final float FAR_EDGE = 330.0f;
    /** How fast the mix follows the listener. Slow enough that walking never steps. */
    private static final float GLIDE = 0.12f;
    /** Cut short: the post is gone, or its chunk is. A handful of ticks, not a snap. */
    private static final int FADE_OUT_TICKS = 8;

    private SirenSource source;
    private final boolean far;
    private boolean closing;
    private int fadeTicks;
    /**
     * How much wail the post said it had left, counted down here.
     * <p>
     * The voice used to live or die on {@code getBlockState} alone, which meant it died
     * the moment the listener walked past their own render distance — the chunk goes, the
     * lookup reads air, and a raid two hundred blocks off fell silent even though the far
     * layer is mixed to carry three hundred and thirty.
     */
    private int ticksLeft;
    /**
     * How much voice the rotor has, 0 to 1, eased toward whatever the post last reported.
     * <p>
     * A siren is a rotor in a housing, so the note follows the shaft: spinning it up winds
     * the wail up with it and letting it slow lets the wail down. Eased rather than
     * stepped, because the post only reports when the speed has actually moved.
     */
    private float voice;

    private float targetVoice;

    public SirenSoundInstance(SirenWailPayload payload, boolean far) {
        super(
                far ? ModSounds.SIREN_DISTANT.get() : ModSounds.SIREN.get(),
                far ? SoundSource.WEATHER : SoundSource.BLOCKS,
                RandomSource.create(payload.source().pos().asLong()));
        this.source = payload.source();
        this.far = far;
        this.ticksLeft = payload.remainingTicks();
        this.voice = payload.voice();
        this.targetVoice = payload.voice();
        this.looping = true;
        this.delay = 0;
        // Positioned, so the post can still be located by ear, but with the engine's own
        // distance curve out of the way — the crossfade below is the whole point.
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.pitch = far ? 0.92f : 1.0f;
        this.updatePosition();
        this.volume = this.gain((float) listenerDistance()) * this.voice;
    }

    public SirenSource source() {
        return this.source;
    }

    @Override
    public boolean canStartSilent() {
        // A moving ship can enter the near layer after this voice was started outside it.
        return true;
    }

    private void updatePosition() {
        var level = Minecraft.getInstance().level;
        Vec3 at = level == null ? this.source.worldPosition() : this.source.position(level);
        // Give the engine world coordinates. Sable must not wrap and transform these twice.
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;
    }

    public boolean isFar() {
        return this.far;
    }

    /** The post has been told to stop, or the manager is letting go of this level. */
    public void close() {
        this.closing = true;
    }

    /** A keepalive arrived: how much the post has left, and how hard its rotor is turning. */
    public void refresh(SirenWailPayload payload) {
        this.source = payload.source();
        this.ticksLeft = payload.remainingTicks();
        this.targetVoice = payload.voice();
        this.closing = false;
        this.fadeTicks = 0;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            this.stop();
            return;
        }
        this.updatePosition();
        if (this.closing || this.ticksLeft-- <= 0) {
            this.fadeOut();
            return;
        }
        this.fadeTicks = 0;
        this.voice = Mth.lerp(GLIDE, this.voice, this.targetVoice);
        this.volume = Mth.lerp(GLIDE, this.volume, this.gain((float) listenerDistance()) * this.voice);
        // A rotor wound right down takes the note with it rather than leaving a hum.
        this.pitch = (this.far ? 0.92f : 1.0f) * Mth.lerp(this.voice, 0.82f, 1.0f);
    }

    /** How far the listener is from the post; the whole mix is a function of this. */
    private double listenerDistance() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0.0D;
        }
        return mc.player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(this.x, this.y, this.z));
    }

    /** Where this layer sits in the mix at that distance. */
    private float gain(float distance) {
        if (!this.far) {
            return NEAR_MAX * (1.0f - smoothstep(NEAR_FULL, NEAR_EDGE, distance));
        }
        float swell = Mth.lerp(smoothstep(0.0f, FAR_FULL, distance), FAR_UNDER, 1.0f);
        return FAR_MAX * swell * (1.0f - smoothstep(FAR_FADE, FAR_EDGE, distance));
    }

    private void fadeOut() {
        this.fadeTicks++;
        this.volume *= 0.66f;
        if (this.fadeTicks >= FADE_OUT_TICKS || this.volume < 0.002f) {
            this.stop();
        }
    }

    /** Hermite ramp, so neither curve has a corner in it anywhere. */
    private static float smoothstep(float from, float to, float value) {
        float t = Mth.clamp((value - from) / (to - from), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
