package com.cbc_more_content.client.sound;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.client.sound.DroppedDiscSoundInstance.Role;
import com.cbc_more_content.registry.ModItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps the theme, and the falling whistle, attached to dropped Breaker of Skies records.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class DroppedDiscSoundManager {
    /** A little past the whistle's own range, so a sound starts before it is audible. */
    private static final double SEARCH_RADIUS = 10.0D;
    /** Enough for a small pile; past this the mix turns to mud anyway. */
    private static final int MAX_DISCS = 4;

    private static final Map<Long, DroppedDiscSoundInstance> ACTIVE = new HashMap<>();

    private static ClientLevel activeLevel;

    private DroppedDiscSoundManager() {
    }

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

        ACTIVE.values().removeIf(DroppedDiscSoundInstance::isStopped);

        AABB area = player.getBoundingBox().inflate(SEARCH_RADIUS);
        int tracked = 0;
        for (ItemEntity disc : level.getEntitiesOfClass(ItemEntity.class, area,
                e -> e.isAlive() && e.getItem().is(ModItems.MUSIC_DISC_BREAKER_OF_SKIES.get()))) {
            if (tracked++ >= MAX_DISCS) {
                break;
            }
            play(mc, disc, Role.THEME);
            if (DroppedDiscSoundInstance.isFalling(disc)) {
                play(mc, disc, Role.WHISTLE);
            }
        }
    }

    private static void play(Minecraft mc, ItemEntity disc, Role role) {
        long key = key(disc, role);
        DroppedDiscSoundInstance existing = ACTIVE.get(key);
        if (existing != null && !existing.isStopped() && existing.disc() == disc) {
            return;
        }
        DroppedDiscSoundInstance sound = new DroppedDiscSoundInstance(disc, role);
        ACTIVE.put(key, sound);
        mc.getSoundManager().play(sound);
    }

    private static long key(ItemEntity disc, Role role) {
        return ((long) disc.getId() << 1) | role.ordinal();
    }

    private static void clear(Minecraft mc) {
        Iterator<DroppedDiscSoundInstance> sounds = ACTIVE.values().iterator();
        while (sounds.hasNext()) {
            mc.getSoundManager().stop(sounds.next());
            sounds.remove();
        }
        activeLevel = null;
    }
}
