package com.cbc_more_content.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/** Ambient-lit, slowly expanding exhaust. Only the first few ticks retain any incandescence. */
public final class MissileSmokeParticle extends TextureSheetParticle {
    private static final ParticleRenderType SMOKE = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            var buffer = PARTICLE_SHEET_TRANSLUCENT.begin(tesselator, textures);
            // Vanilla's translucent sheet writes depth. Even faint smoke then hides the
            // later volumetric flame as a solid billboard when looking into the exhaust.
            // Fabulous has a separate particle depth buffer, needed to sort water/glass.
            RenderSystem.depthMask(Minecraft.useShaderTransparency());
            return buffer;
        }

        @Override
        public String toString() {
            return "WARNAUTICS_MISSILE_SMOKE";
        }
    };
    private final float spin;
    private final float shade;
    private final float size;
    private final boolean softVolume;
    private final float bulk;

    private MissileSmokeParticle(
            ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        this(level, x, y, z, vx, vy, vz, sprites, 1, false);
    }

    private MissileSmokeParticle(
            ClientLevel level,
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz,
            SpriteSet sprites,
            float bulk,
            boolean blast) {
        super(level, x, y, z);
        this.bulk = bulk;
        xd = vx;
        yd = vy;
        zd = vz;
        friction = .97f;
        gravity = -.006f;
        hasPhysics = false;
        lifetime = (blast ? 100 : 64) + random.nextInt(24);
        size = (.62f + random.nextFloat() * .20f) * bulk;
        quadSize = size;
        spin = (random.nextFloat() - .5f) * .016f;
        shade = (blast ? .30f : .70f) + random.nextFloat() * .13f;
        setColor(shade, shade * .98f, shade * .94f);
        setAlpha(.14f);
        pickSprite(sprites);
        softVolume = net.neoforged.fml.ModList.get().isLoaded("veil")
                && com.cbc_more_content.client.veil.VeilMissileSmoke.available();
    }

    @Override
    public void tick() {
        oRoll = roll;
        roll += spin;
        super.tick();
        float t = age / (float) lifetime;
        quadSize = size + 2.6f * bulk * (1 - (float) Math.exp(-t * 1.8));
        float fade = Mth.clamp((t - .18f) / .82f, 0, 1);
        float tail = 1 - fade * fade * (3 - 2 * fade);
        setAlpha((.3f + .7f * Math.min(1, age / 4f)) * tail * .46f);
    }

    @Override
    protected int getLightColor(float partial) {
        int ambient = super.getLightColor(partial);
        int warm = (int) (Mth.clamp(1 - (age + partial) / 7f, 0, 1) * 160);
        return (ambient & 0xFFFF0000) | Math.max(ambient & 0xFFFF, warm);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return softVolume ? com.cbc_more_content.client.veil.VeilMissileSmoke.TYPE : SMOKE;
    }

    @Override
    protected float getU0() {
        return softVolume ? 0 : super.getU0();
    }

    @Override
    protected float getU1() {
        return softVolume ? 1 : super.getU1();
    }

    @Override
    protected float getV0() {
        return softVolume ? 0 : super.getV0();
    }

    @Override
    protected float getV1() {
        return softVolume ? 1 : super.getV1();
    }

    public record BlastProvider(SpriteSet sprites)
            implements ParticleProvider<com.cbc_more_content.effects.BlastSmokeData> {
        @Override
        public Particle createParticle(
                com.cbc_more_content.effects.BlastSmokeData data,
                ClientLevel level,
                double x,
                double y,
                double z,
                double vx,
                double vy,
                double vz) {
            return new MissileSmokeParticle(level, x, y, z, vx, vy, vz, sprites, data.scale(), true);
        }
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
            return new MissileSmokeParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}
