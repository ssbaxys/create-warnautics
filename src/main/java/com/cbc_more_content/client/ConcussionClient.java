package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.network.ConcussionPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Flashbang-style concussion overlay, drawn above the HUD: a hard white bloom that
 * snaps in and decays fast, plus edge darkening when Veil is not carrying the real
 * defocus. Plain {@link GuiGraphics} work, so it behaves the same under Sodium/Iris.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class ConcussionClient {
    /**
     * The hard white-out runs on its own fixed clock — 2.3 seconds at full strength —
     * rather than as a share of the total. The haze then decays across the whole
     * duration, which the server sizes to the length of the ringing sound, so vision
     * is back to normal just as the audio finishes.
     */
    private static final int FLASH_TICKS = 46;
    /**
     * Ceiling on the running effect, matched to the length of bomb_concussion.ogg.
     * Without it a long bombing run would push recovery out indefinitely.
     */
    private static final int MAX_TOTAL_TICKS = 300;

    /** Drives the white-out and the Veil defocus. Zero when cover blocked the blast. */
    private static float intensity;
    /** Drives the ringing, which reaches the player around and through cover. */
    private static float audio;
    private static int duration;
    private static int age = -1;
    @javax.annotation.Nullable
    private static net.minecraft.client.resources.sounds.SoundInstance ringing;

    private ConcussionClient() {
    }

    /**
     * Takes a new blast on top of whatever is already running. Every payload counts:
     * a later blast tops the level up and pushes recovery out, and can only extend
     * the effect, never cut it short.
     */
    public static void handle(ConcussionPayload payload) {
        if (!Float.isFinite(payload.visual()) || !Float.isFinite(payload.audio())
                || payload.durationTicks() <= 0) {
            return;
        }
        float incomingVisual = Mth.clamp(payload.visual(), 0.0f, 1.0f);
        float incomingAudio = Mth.clamp(payload.audio(), 0.0f, 1.0f);

        // What is still left of the running effect, on the same curve the renderer uses.
        float remainingShare = age >= 0 && duration > 0
                ? 1.0f - Mth.clamp(age / (float) duration, 0.0f, 1.0f)
                : 0.0f;
        int remainingTicks = age >= 0 && age < duration ? duration - age : 0;

        float previousAudio = audio;
        intensity = Math.max(intensity * remainingShare, incomingVisual);
        audio = Math.max(audio * remainingShare, incomingAudio);
        // Never shorter than what was already scheduled, never longer than the ring.
        duration = Math.min(MAX_TOTAL_TICKS, Math.max(remainingTicks, payload.durationTicks()));
        age = 0;

        // Sheltered behind a wall that held: ears ring, vision is untouched.
        if (audio > 0.02f) {
            refreshRinging(previousAudio);
        }
    }

    /**
     * UI sound rather than positional: tinnitus is in the listener head and must not
     * attenuate as they walk away. A ring already running is left alone unless the new
     * blast is clearly louder, so a bombing run does not stutter or stack copies.
     */
    private static void refreshRinging(float previousAudio) {
        Minecraft mc = Minecraft.getInstance();
        boolean playing = ringing != null && mc.getSoundManager().isActive(ringing);
        if (playing && audio <= previousAudio + 0.15f) {
            return;
        }
        stopRinging();
        try {
            ringing = net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    com.cbc_more_content.registry.ModSounds.BOMB_CONCUSSION.get(),
                    1.04f - audio * 0.12f,
                    0.5f + audio * 0.5f);
            mc.getSoundManager().play(ringing);
        } catch (Throwable ignored) {
            ringing = null;
        }
    }

    private static void stopRinging() {
        if (ringing == null) {
            return;
        }
        try {
            Minecraft.getInstance().getSoundManager().stop(ringing);
        } catch (Throwable ignored) {
        }
        ringing = null;
    }

    /** Drops all state. Called when the world goes away so nothing survives a rejoin. */
    private static void reset() {
        age = -1;
        duration = 0;
        intensity = 0.0f;
        audio = 0.0f;
        stopRinging();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // Leaving the world must not strand the statics, or the next join counts the
        // effect as still running and rejects everything. Dying does not clear it: a mine
        // that kills you should still white out the last thing you see. Respawn does.
        if (mc.level == null || mc.player == null) {
            if (age >= 0 || ringing != null) {
                reset();
            }
            return;
        }
        if (age >= 0) {
            age++;
            if (age >= duration) {
                age = -1;
                intensity = 0.0f;
                audio = 0.0f;
                // The ring is allowed to finish on its own — ears keep going after vision
                // clears — and isActive() stops a second copy from ever being layered on.
            }
        }
    }

    /** Respawn replaces LocalPlayer without unloading the level, so clear defensively. */
    @SubscribeEvent
    public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        reset();
    }

    /** Also covers switching servers/worlds before the next client tick runs. */
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    /** Current shock strength, 0 when nothing is active. Read by the Veil pass. */
    public static float shock() {
        return age < 0 || duration <= 0 ? 0.0f : intensity;
    }

    /** Progress from the blast (0) to fully recovered (1). Read by the Veil pass. */
    public static float recovery() {
        if (age < 0 || duration <= 0) {
            return 1.0f;
        }
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        return Mth.clamp((age + partial) / (float) duration, 0.0f, 1.0f);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        // Skipped while a screen is up; onRenderScreen draws it above that instead.
        if (Minecraft.getInstance().screen == null) {
            draw(event.getGuiGraphics(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }

    /**
     * Drawn over any open screen, so the shock from the blast that killed you is not
     * hidden behind the death screen — which is exactly when it matters most.
     */
    @SubscribeEvent
    public static void onRenderScreen(ScreenEvent.Render.Post event) {
        draw(event.getGuiGraphics(), event.getPartialTick());
    }

    private static void draw(GuiGraphics graphics, float partial) {
        if (age < 0 || duration <= 0 || intensity <= 0.002f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        float ticks = age + partial;
        float t = Mth.clamp(ticks / (float) duration, 0.0f, 1.0f);
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();

        // Veil's pass owns the scene itself — real blur, chromatic separation and grain
        // sampled from the framebuffer. It runs before the HUD is drawn, so all that is
        // left here is washing out the HUD along with the world.
        boolean shaded = veilActive();
        drawFlash(graphics, w, h, ticks, shaded ? 0.5f : 1.0f);
        if (!shaded) {
            drawFallbackHaze(graphics, w, h, t);
        }
    }

    private static boolean veilActive() {
        try {
            return net.neoforged.fml.ModList.get().isLoaded("veil")
                    && com.cbc_more_content.client.veil.VeilConcussionFx.isHandlingConcussion();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Hard white bloom: near-instant onset, then a fast smooth decay. */
    private static void drawFlash(GuiGraphics graphics, int w, int h, float ticks, float weight) {
        float span = FLASH_TICKS * Mth.lerp(intensity, 0.45f, 1.0f);
        if (ticks >= span) {
            return;
        }
        // Rises over the first frames rather than appearing fully formed.
        float rise = ticks / span;
        float f = rise < 0.12f
                ? rise / 0.12f
                : 1.0f - smoothstep((rise - 0.12f) / 0.88f);
        f *= intensity * weight;
        if (f <= 0.002f) {
            return;
        }
        int a = (int) (Mth.clamp(f, 0.0f, 1.0f) * 235.0f);
        graphics.fill(0, 0, w, h, (a << 24) | 0xFFFFFF);
    }

    /**
     * Soft edge darkening for clients with no Veil. Without the scene texture there is
     * no honest way to defocus the frame, so this only narrows vision.
     */
    private static void drawFallbackHaze(GuiGraphics graphics, int w, int h, float t) {
        float f = (1.0f - smoothstep(t)) * intensity;
        int a = (int) (Mth.clamp(f, 0.0f, 1.0f) * 110.0f);
        if (a <= 1) {
            return;
        }
        int band = Math.max(1, h / 3);
        graphics.fillGradient(0, 0, w, band, (a << 24) | 0xFFFFFF, 0x00FFFFFF);
        graphics.fillGradient(0, h - band, w, h, 0x00FFFFFF, (a << 24) | 0xFFFFFF);
    }

    private static float smoothstep(float value) {
        value = Mth.clamp(value, 0.0f, 1.0f);
        return value * value * (3.0f - 2.0f * value);
    }
}
