package com.cbc_more_content.effects;

import com.cbc_more_content.registry.ModParticles;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A bounded billow size carried by one ordinary particle packet. */
public record BlastSmokeData(float scale) implements ParticleOptions {
    public static final MapCodec<BlastSmokeData> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.floatRange(.25f, 24).fieldOf("scale").forGetter(BlastSmokeData::scale))
                    .apply(i, BlastSmokeData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlastSmokeData> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.FLOAT, BlastSmokeData::scale, BlastSmokeData::new);

    public BlastSmokeData {
        scale = Float.isFinite(scale) ? Math.clamp(scale, .25f, 24) : 1;
    }

    @Override
    public ParticleType<BlastSmokeData> getType() {
        return ModParticles.BLAST_SMOKE.get();
    }
}
