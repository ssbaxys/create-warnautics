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
 * A casing sliver from an antipersonnel mine: leaves white-hot, cools to dull red as it
 * flies, and is stretched along its own travel so the fan reads as movement rather than
 * as a cloud of dots.
 */
@OnlyIn(Dist.CLIENT)
public class MineFragmentParticle extends TextureSheetParticle {
    private static final float DRAG = 0.91f;

    protected MineFragmentParticle(
            ClientLevel level,
            double x, double y, double z,
            double vx, double vy, double vz,
            SpriteSet sprites) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.gravity = 0.9f;
        this.friction = DRAG;
        this.hasPhysics = true;
        this.lifetime = 14 + this.random.nextInt(12);
        this.quadSize = 0.055f + this.random.nextFloat() * 0.035f;
        this.setSpriteFromAge(sprites);
        this.setColor(1.0f, 0.94f, 0.72f);
    }

    @Override
    public void tick() {
        super.tick();
        // Cool from white through amber to a dull ember over the fragment's life.
        float t = Mth.clamp(this.age / (float) this.lifetime, 0.0f, 1.0f);
        this.setColor(
                Mth.lerp(t, 1.0f, 0.42f),
                Mth.lerp(t, 0.94f, 0.13f),
                Mth.lerp(t, 0.72f, 0.06f));
        this.setAlpha(1.0f - t * t);
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
            return new MineFragmentParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
