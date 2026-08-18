package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, CBCMoreContent.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_SMALL = register("bomb_explosion_small");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_MEDIUM = register("bomb_explosion_medium");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_LARGE = register("bomb_explosion_large");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_LARGE_RUMBLE = register("bomb_explosion_large_rumble");
    /** Distant muffled battlefield boom — heard far from the blast. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_WAR_RUMBLE = register("bomb_war_rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_FALLING = register("bomb_falling");
    public static final DeferredHolder<SoundEvent, SoundEvent> SEA_BOMB_PROPELLER = register("sea_bomb_propeller");
    public static final DeferredHolder<SoundEvent, SoundEvent> SEA_BOMB_SPLASH = register("sea_bomb_splash");
    /** Ringing ears after surviving a near miss. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_CONCUSSION = register("bomb_concussion");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, name)));
    }

    private ModSounds() {
    }
}
