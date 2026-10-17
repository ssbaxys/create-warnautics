package com.cbc_more_content.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * Condensation trail: the white line a jet engine leaves in cold, high air. Long-lived,
 * slow to spread and nearly still, so a missile cruising high draws a line across the
 * sky that lingers well after it has gone.
 */
public final class MissileContrailParticle extends TextureSheetParticle {
    private final float size;
    private final float spin;

    private MissileContrailParticle(
            ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        xd = vx;
        yd = vy;
        zd = vz;
        friction = .92f;
        gravity = 0;
        hasPhysics = false;
        lifetime = 180 + random.nextInt(60);
        size = .45f + random.nextFloat() * .15f;
        quadSize = size;
        spin = (random.nextFloat() - .5f) * .006f;
        float shade = .93f + random.nextFloat() * .07f;
        setColor(shade, shade, shade);
        setAlpha(0);
        pickSprite(sprites);
    }

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        super.tick();
        float t = age / (float) lifetime;
        // Forms within a second behind the engine, then spreads and thins out.
        quadSize = size + 2.2f * (1 - (float) Math.exp(-t * 2.4f));
        float form = Mth.clamp(age / 12f, 0, 1);
        float fade = 1 - Mth.clamp((t - .35f) / .65f, 0, 1);
        setAlpha(.32f * form * fade * fade);
    }

    @Override
    protected int getLightColor(float partial) {
        // Lit like the sky it hangs in, not like the ground underneath.
        return 0xF000F0;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

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
            return new MissileContrailParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}
