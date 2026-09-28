package com.cbc_more_content.client.sound;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.network.SirenWailPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps one pair of looping voices open per wailing post.
 * <p>
 * Opened when the server says a post has started, and again on its keepalive, so walking
 * into range part-way through a raid still puts you under it. Stop packets and a bounded
 * lease also close voices whose source is no longer loaded by the listener.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class SirenSoundManager {
    private static final Map<BlockPos, Voices> ACTIVE = new HashMap<>();
    private static ClientLevel activeLevel;

    private SirenSoundManager() {}

    /** A post has started, or is still going. Idempotent — the keepalive lands often. */
    public static void wail(SirenWailPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        useLevel(mc);
        BlockPos pos = payload.source().pos();
        Voices voices = ACTIVE.get(pos);
        boolean same = voices != null && voices.near.source().samePost(payload.source());
        if (payload.remainingTicks() <= 0 || payload.voice() <= 0) {
            if (same) {
                voices.near.close();
                voices.far.close();
            }
            return;
        }
        if (same) {
            ACTIVE.put(
                    pos,
                    new Voices(
                            refreshVoice(mc, voices.near, payload, false),
                            refreshVoice(mc, voices.far, payload, true)));
            return;
        }
        if (voices != null) {
            stop(mc, voices);
        }
        SirenSoundInstance near = new SirenSoundInstance(payload, false);
        SirenSoundInstance far = new SirenSoundInstance(payload, true);
        ACTIVE.put(pos, new Voices(near, far));
        mc.getSoundManager().play(near);
        mc.getSoundManager().play(far);
    }

    private static SirenSoundInstance refreshVoice(
            Minecraft mc, SirenSoundInstance sound, SirenWailPayload payload, boolean far) {
        if (!sound.isStopped() && mc.getSoundManager().isActive(sound)) {
            sound.refresh(payload);
            return sound;
        }
        sound.close();
        mc.getSoundManager().stop(sound);
        var replacement = new SirenSoundInstance(payload, far);
        mc.getSoundManager().play(replacement);
        return replacement;
    }

    private static void useLevel(Minecraft mc) {
        if (activeLevel != mc.level) {
            clear(mc);
            activeLevel = mc.level;
        }
    }

    private static void stop(Minecraft mc, Voices voices) {
        voices.near.close();
        voices.far.close();
        mc.getSoundManager().stop(voices.near);
        mc.getSoundManager().stop(voices.far);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clear(mc);
            return;
        }
        useLevel(mc);
        Iterator<Map.Entry<BlockPos, Voices>> entries = ACTIVE.entrySet().iterator();
        while (entries.hasNext()) {
            Voices voices = entries.next().getValue();
            if (voices.near.isStopped() && voices.far.isStopped()) {
                entries.remove();
            }
        }
    }

    private static void clear(Minecraft mc) {
        for (Voices voices : ACTIVE.values()) {
            stop(mc, voices);
        }
        ACTIVE.clear();
        activeLevel = null;
    }

    private record Voices(SirenSoundInstance near, SirenSoundInstance far) {}
}
