package com.cbc_more_content.client.sound;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.DropBombProjectile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Mix limiter for many simultaneously falling bombs. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class FallingBombSoundManager {
    private static final double SEARCH_RADIUS = 300.0D;
    private static final int MAX_VOICES = 5;
    private static final Map<Integer, FallingBombSoundInstance> ACTIVE = new HashMap<>();

    private static ClientLevel activeLevel;

    private FallingBombSoundManager() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            clear(mc);
            return;
        }
        if (activeLevel != level) {
            clear(mc);
            activeLevel = level;
        }

        Iterator<FallingBombSoundInstance> oldSounds = ACTIVE.values().iterator();
        while (oldSounds.hasNext()) {
            FallingBombSoundInstance sound = oldSounds.next();
            if (sound.isStopped()) {
                oldSounds.remove();
            } else {
                sound.deselect();
            }
        }

        AABB area = player.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Candidate> candidates = new ArrayList<>();
        for (DropBombProjectile bomb :
                level.getEntitiesOfClass(DropBombProjectile.class, area, FallingBombSoundInstance::isAirborne)) {
            double distance = player.getEyePosition().distanceTo(bomb.position());
            double range = FallingBombSoundInstance.range(bomb.bombSize());
            if (distance >= range) {
                continue;
            }
            float proximity = 1.0f - (float) (distance / range);
            float score = score(player, bomb, proximity);
            if (score > 0.008f) {
                candidates.add(new Candidate(bomb, score));
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        int audibleCount = candidates.size();
        float crowdGain = Math.max(0.18f, (float) (1.0D / Math.sqrt(Math.max(1, audibleCount))));
        int voices = Math.min(MAX_VOICES, audibleCount);
        for (int index = 0; index < voices; index++) {
            DropBombProjectile bomb = candidates.get(index).bomb();
            FallingBombSoundInstance sound = ACTIVE.get(bomb.getId());
            if (sound == null || sound.bomb() != bomb || sound.isStopped()) {
                sound = new FallingBombSoundInstance(player, bomb);
                ACTIVE.put(bomb.getId(), sound);
                mc.getSoundManager().play(sound);
            }
            float voiceGain =
                    switch (index) {
                        case 0 -> 1.0f;
                        case 1 -> 0.88f;
                        case 2 -> 0.78f;
                        default -> 0.68f;
                    };
            sound.select(crowdGain, voiceGain);
        }
    }

    private static float score(LocalPlayer player, DropBombProjectile bomb, float proximity) {
        double distance = Math.max(0.001D, player.getEyePosition().distanceTo(bomb.position()));
        double radial = bomb.getDeltaMovement()
                .subtract(player.getDeltaMovement())
                .dot(player.getEyePosition().subtract(bomb.position()).scale(1.0D / distance));
        float approach = Math.max(0.72f, Math.min(1.28f, 1.0f + (float) radial * 0.12f));
        float persistentVoice = ACTIVE.containsKey(bomb.getId()) ? 1.08f : 1.0f;
        return FallingBombSoundInstance.maxVolume(bomb.bombSize())
                * (0.08f + 0.92f * proximity)
                * approach
                * persistentVoice;
    }

    private static void clear(Minecraft mc) {
        for (FallingBombSoundInstance sound : ACTIVE.values()) {
            mc.getSoundManager().stop(sound);
        }
        ACTIVE.clear();
        activeLevel = null;
    }

    private record Candidate(DropBombProjectile bomb, float score) {}
}
