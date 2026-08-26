package com.cbc_more_content.client.sound;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.cbc_more_content.CBCMoreContent;

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
 * into range part-way through a raid still puts you under it. Closed by the voices
 * themselves, which watch their own block — the post being broken, or its chunk going
 * away, are the same thing to them.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class SirenSoundManager {
    private static final Map<BlockPos, Voices> ACTIVE = new HashMap<>();
    private static ClientLevel activeLevel;

    private SirenSoundManager() {
    }

    /** A post has started, or is still going. Idempotent — the keepalive lands often. */
    public static void wail(BlockPos pos, int remainingTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Voices voices = ACTIVE.get(pos);
        if (voices != null && !voices.near.isStopped() && !voices.far.isStopped()) {
            // Already running: this is a keepalive, so it only tops the clock back up.
            voices.near.refresh(remainingTicks);
            voices.far.refresh(remainingTicks);
            return;
        }
        SirenSoundInstance near = new SirenSoundInstance(pos, false, remainingTicks);
        SirenSoundInstance far = new SirenSoundInstance(pos, true, remainingTicks);
        ACTIVE.put(pos.immutable(), new Voices(near, far));
        mc.getSoundManager().play(near);
        mc.getSoundManager().play(far);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clear(mc);
            return;
        }
        if (activeLevel != mc.level) {
            clear(mc);
            activeLevel = mc.level;
        }
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
            voices.near.close();
            voices.far.close();
            mc.getSoundManager().stop(voices.near);
            mc.getSoundManager().stop(voices.far);
        }
        ACTIVE.clear();
        activeLevel = null;
    }

    private record Voices(SirenSoundInstance near, SirenSoundInstance far) {
    }
}
