package com.cbc_more_content.client.sound;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModSounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opens booster and sustainer voices on the nearest missiles, and plays the close flyby. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class MissileEngineSoundManager {
    /** Enough for a salvo to sound like one; past this the mix turns to mud. */
    private static final int MAX_MISSILES = 6;
    /** A pass this close earns the flyby. */
    private static final double FLYBY_DISTANCE = 26.0D;

    private static final Map<Long, MissileEngineSoundInstance> ACTIVE = new HashMap<>();
    private static final Map<Integer, Double> CLOSEST = new HashMap<>();
    /** Missiles whose pass has already been heard; one flyby each. */
    private static final java.util.Set<Integer> PLAYED = new java.util.HashSet<>();

    private static ClientLevel activeLevel;

    private MissileEngineSoundManager() {}

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
        ACTIVE.values().removeIf(MissileEngineSoundInstance::isStopped);

        var eye = mc.player.getEyePosition();
        List<CruiseMissileProjectile> missiles = new ArrayList<>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CruiseMissileProjectile missile
                    && !missile.isRemoved()
                    && missile.position().distanceToSqr(eye)
                            < MissileEngineSoundInstance.range(true) * MissileEngineSoundInstance.range(true)) {
                missiles.add(missile);
            }
        }
        missiles.sort(Comparator.comparingDouble(m -> m.position().distanceToSqr(eye)));
        var seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < missiles.size(); i++) {
            var missile = missiles.get(i);
            seen.add(missile.getId());
            if (i < MAX_MISSILES) {
                for (boolean booster : new boolean[] {true, false}) {
                    if (MissileEngineSoundInstance.wanted(missile, booster)) {
                        open(mc, missile, booster);
                    }
                }
            }
            flyby(mc, missile, Math.sqrt(missile.position().distanceToSqr(eye)));
        }
        CLOSEST.keySet().retainAll(seen);
        PLAYED.retainAll(seen);
    }

    private static void open(Minecraft mc, CruiseMissileProjectile missile, boolean booster) {
        long key = ((long) missile.getId() << 1) | (booster ? 1 : 0);
        var existing = ACTIVE.get(key);
        if (existing != null && !existing.isStopped() && existing.missile() == missile) {
            return;
        }
        var sound = new MissileEngineSoundInstance(missile, booster);
        ACTIVE.put(key, sound);
        mc.getSoundManager().play(sound);
    }

    /** Fires once, as a powered missile starts to recede after passing close by. */
    private static void flyby(Minecraft mc, CruiseMissileProjectile missile, double distance) {
        if (PLAYED.contains(missile.getId())) {
            return;
        }
        Double closest = CLOSEST.get(missile.getId());
        if (closest == null || distance < closest) {
            CLOSEST.put(missile.getId(), distance);
            return;
        }
        if (closest >= FLYBY_DISTANCE || distance < closest + 1.5D || !missile.isPowered()) {
            return;
        }
        PLAYED.add(missile.getId());
        float volume = (float) Mth.clamp(1.15D - closest / FLYBY_DISTANCE, 0.25D, 1.0D);
        mc.getSoundManager()
                .play(new SimpleSoundInstance(
                        ModSounds.CRUISE_MISSILE_FLYBY.get(),
                        SoundSource.HOSTILE,
                        volume,
                        0.95f + mc.level.random.nextFloat() * 0.1f,
                        mc.level.random,
                        missile.getX(),
                        missile.getY(),
                        missile.getZ()));
    }

    private static void clear(Minecraft mc) {
        Iterator<MissileEngineSoundInstance> sounds = ACTIVE.values().iterator();
        while (sounds.hasNext()) {
            mc.getSoundManager().stop(sounds.next());
            sounds.remove();
        }
        CLOSEST.clear();
        PLAYED.clear();
        activeLevel = null;
    }
}
