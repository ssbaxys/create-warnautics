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
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_MEDIUM =
            register("bomb_explosion_medium");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_LARGE = register("bomb_explosion_large");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_EXPLOSION_LARGE_RUMBLE =
            register("bomb_explosion_large_rumble");
    /** Distant muffled battlefield boom — heard far from the blast. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_WAR_RUMBLE = register("bomb_war_rumble");

    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_FALLING = register("bomb_falling");
    public static final DeferredHolder<SoundEvent, SoundEvent> SEA_BOMB_PROPELLER = register("sea_bomb_propeller");
    public static final DeferredHolder<SoundEvent, SoundEvent> SEA_BOMB_SPLASH = register("sea_bomb_splash");
    /** Ringing ears after surviving a near miss. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_CONCUSSION = register("bomb_concussion");
    /** C4 countdown; a single beep, spaced tighter as the fuse runs out. */
    public static final DeferredHolder<SoundEvent, SoundEvent> C4_TICK = register("c4_tick");
    /** A thrown charge sticking to a surface. */
    public static final DeferredHolder<SoundEvent, SoundEvent> C4_PLACE = register("c4_place");
    /** Keypad and confirm button on the charge. */
    public static final DeferredHolder<SoundEvent, SoundEvent> C4_BUTTON = register("c4_button");
    /** The charge going live. The countdown stays silent until this has played out. */
    public static final DeferredHolder<SoundEvent, SoundEvent> C4_ARMED = register("c4_armed");
    /** Ignition when a cruise missile leaves its rack. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRUISE_MISSILE_LAUNCH =
            register("cruise_missile_launch");
    /** Sustained engine note while the missile is under power. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRUISE_MISSILE_ENGINE =
            register("cruise_missile_engine");
    /** Air-raid post, close enough to hear the wail itself. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN = register("siren");
    /** The same post from a long way off: the edge rolled off, only the swell left. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN_DISTANT = register("siren_distant");
    /** The main theme. Played by the jukebox, and quietly by a dropped record. */
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_BREAKER_OF_SKIES =
            register("music.breaker_of_skies");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(
                name,
                () -> SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, name)));
    }

    private ModSounds() {}
}
