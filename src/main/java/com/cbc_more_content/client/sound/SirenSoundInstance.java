package com.cbc_more_content.client.sound;

import com.cbc_more_content.block.SirenBlock;
import com.cbc_more_content.registry.ModSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One layer of a wailing post, held open for as long as the post is sounding.
 * <p>
 * Both layers run the whole time and are mixed here by distance rather than left to the
 * sound engine's own rolloff. Fired as two one-shots the way it was, the near layer went
 * silent at a fixed range while the far one was still at full strength, so walking away
 * from a siren did not fade it — it flipped, mid-note, from a wail into a rumble. Here the
 * two curves overlap across sixty blocks and neither ever reaches an edge.
 * <p>
 * It also watches the block it belongs to. A one-shot sample cannot be recalled once it
 * has started, which is why breaking a siren left it howling for another ten seconds.
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

    private final BlockPos pos;
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

    public SirenSoundInstance(BlockPos pos, boolean far, int remainingTicks, float voice) {
        super(far ? ModSounds.SIREN_DISTANT.get() : ModSounds.SIREN.get(),
                far ? SoundSource.WEATHER : SoundSource.BLOCKS,
                RandomSource.create(pos.asLong()));
        this.pos = pos.immutable();
        this.far = far;
        this.ticksLeft = remainingTicks;
        this.voice = voice;
        this.targetVoice = voice;
        this.looping = true;
        this.delay = 0;
        // Positioned, so the post can still be located by ear, but with the engine's own
        // distance curve out of the way — the crossfade below is the whole point.
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.pitch = far ? 0.92f : 1.0f;
        this.x = pos.getX() + 0.5f;
        this.y = pos.getY() + 0.5f;
        this.z = pos.getZ() + 0.5f;
        // Opened at the mix it belongs at, and never at nothing: the sound engine drops a
        // sound whose volume is zero when it is handed over, so a voice starting silent
        // would be thrown away before it ever got a tick to fade itself up.
        this.volume = Math.max(0.01f, this.gain((float) listenerDistance()) * Math.max(0.05f, voice));
    }

    public BlockPos pos() {
        return this.pos;
    }

    public boolean isFar() {
        return this.far;
    }

    /** The post has been told to stop, or the manager is letting go of this level. */
    public void close() {
        this.closing = true;
    }

    /** A keepalive arrived: how much the post has left, and how hard its rotor is turning. */
    public void refresh(int remainingTicks, float voice) {
        this.ticksLeft = Math.max(this.ticksLeft, remainingTicks);
        this.targetVoice = voice;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            this.stop();
            return;
        }
        // Only worth asking the block when the listener actually has that chunk. Close
        // enough to have it, a post that stops sounding — or is broken outright — goes
        // quiet at once, which is the case that matters. Too far to have it, the post's
        // own count is all there is to go on.
        boolean loaded = mc.level.hasChunkAt(this.pos);
        if (this.closing
                || (loaded && !sounding(mc.level.getBlockState(this.pos)))
                || (!loaded && this.ticksLeft <= 0)) {
            this.fadeOut();
            return;
        }
        if (this.ticksLeft > 0) {
            this.ticksLeft--;
        }
        this.fadeTicks = 0;
        this.voice = Mth.lerp(GLIDE, this.voice, this.targetVoice);
        this.volume = Mth.lerp(GLIDE, this.volume,
                this.gain((float) listenerDistance()) * this.voice);
        // A rotor wound right down takes the note with it rather than leaving a hum.
        this.pitch = (this.far ? 0.92f : 1.0f) * Mth.lerp(this.voice, 0.82f, 1.0f);
    }

    /** How far the listener is from the post; the whole mix is a function of this. */
    private double listenerDistance() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0.0D;
        }
        return mc.player.getEyePosition()
                .distanceTo(new net.minecraft.world.phys.Vec3(this.x, this.y, this.z));
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

    /** Whether that block is still a siren, and still sounding. */
    public static boolean sounding(BlockState state) {
        return state.getBlock() instanceof SirenBlock
                && state.hasProperty(SirenBlock.SOUNDING)
                && state.getValue(SirenBlock.SOUNDING);
    }

    /** Hermite ramp, so neither curve has a corner in it anywhere. */
    private static float smoothstep(float from, float to, float value) {
        float t = Mth.clamp((value - from) / (to - from), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
