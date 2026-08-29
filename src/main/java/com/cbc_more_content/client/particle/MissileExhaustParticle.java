package com.cbc_more_content.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Rocket efflux: a bright core that expands and cools into smoke as it falls behind.
 * <p>
 * Drawn on the translucent sheet at full brightness rather than as vanilla flame, so it
 * reads as a light source in its own right and does not pick up the dark block lighting
 * of wherever the missile happens to be flying.
 */
@OnlyIn(Dist.CLIENT)
public class MissileExhaustParticle extends TextureSheetParticle {
    private final float spin;

    protected MissileExhaustParticle(
            ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.gravity = 0.0f;
        this.friction = 0.86f;
        this.hasPhysics = false;
        this.lifetime = 12 + this.random.nextInt(10);
        this.quadSize = 0.16f + this.random.nextFloat() * 0.10f;
        this.spin = (this.random.nextFloat() - 0.5f) * 0.25f;
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        this.oRoll = this.roll;
        this.roll += this.spin;
        super.tick();

        float t = Mth.clamp(this.age / (float) this.lifetime, 0.0f, 1.0f);
        // White-hot at the nozzle, amber a moment later, then grey smoke.
        if (t < 0.35f) {
            float k = t / 0.35f;
            this.setColor(1.0f, Mth.lerp(k, 1.0f, 0.72f), Mth.lerp(k, 0.92f, 0.28f));
        } else {
            float k = (t - 0.35f) / 0.65f;
            this.setColor(Mth.lerp(k, 1.0f, 0.32f), Mth.lerp(k, 0.72f, 0.30f), Mth.lerp(k, 0.28f, 0.29f));
        }
        this.setAlpha((1.0f - t) * (1.0f - t));
        this.quadSize += 0.022f;
    }

    /** Full-bright: the plume lights itself instead of taking the sky's word for it. */
    @Override
    protected int getLightColor(float partialTick) {
        return 0x00F000F0;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @OnlyIn(Dist.CLIENT)
    public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel level,
                double x,
                double y,
                double z,
                double vx,
                double vy,
                double vz) {
            return new MissileExhaustParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
