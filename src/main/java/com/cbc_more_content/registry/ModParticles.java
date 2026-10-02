package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, CBCMoreContent.MOD_ID);

    /** Hot casing sliver thrown by an antipersonnel mine. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MINE_FRAGMENT =
            PARTICLE_TYPES.register("mine_fragment", () -> new SimpleParticleType(false));

    /** Rocket efflux behind a cruise missile under power. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MISSILE_EXHAUST =
            PARTICLE_TYPES.register("missile_exhaust", () -> new SimpleParticleType(false));

    /** Cold ejection gas, before the motor lights. No heat, no glow — just pressure. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MISSILE_GAS =
            PARTICLE_TYPES.register("missile_gas", () -> new SimpleParticleType(false));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MISSILE_SMOKE =
            PARTICLE_TYPES.register("missile_smoke", () -> new SimpleParticleType(false));

    public static final DeferredHolder<ParticleType<?>, ParticleType<com.cbc_more_content.effects.BlastSmokeData>>
            BLAST_SMOKE = PARTICLE_TYPES.register("blast_smoke", () -> new ParticleType<>(true) {
        @Override
        public com.mojang.serialization.MapCodec<com.cbc_more_content.effects.BlastSmokeData> codec() {
            return com.cbc_more_content.effects.BlastSmokeData.CODEC;
        }

        @Override
        public net.minecraft.network.codec.StreamCodec<
                        ? super net.minecraft.network.RegistryFriendlyByteBuf,
                        com.cbc_more_content.effects.BlastSmokeData>
                streamCodec() {
            return com.cbc_more_content.effects.BlastSmokeData.STREAM_CODEC;
        }
    });

    private ModParticles() {}
}
