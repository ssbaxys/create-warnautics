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
 * Cold gas dumped out of a silo before the motor lights.
 * <p>
 * No white-hot core and no glow, unlike {@link MissileExhaustParticle} — the whole point
 * of the two is that they read as different events. This one only ever cools, from a pale
 * grey down to a thin haze, and it hangs rather than streaking away.
 */
@OnlyIn(Dist.CLIENT)
public class MissileGasParticle extends TextureSheetParticle {
    protected MissileGasParticle(
            ClientLevel level,
            double x, double y, double z,
            double vx, double vy, double vz,
            SpriteSet sprites) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.gravity = 0.0f;
        this.friction = 0.96f;
        this.hasPhysics = false;
        this.lifetime = 22 + this.random.nextInt(14);
        this.quadSize = 0.5f + this.random.nextFloat() * 0.35f;
        this.setColor(0.86f, 0.87f, 0.88f);
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        float t = Mth.clamp(this.age / (float) this.lifetime, 0.0f, 1.0f);
        this.setAlpha((1.0f - t) * 0.75f);
        this.quadSize += 0.03f;
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
                double x, double y, double z,
                double vx, double vy, double vz) {
            return new MissileGasParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
