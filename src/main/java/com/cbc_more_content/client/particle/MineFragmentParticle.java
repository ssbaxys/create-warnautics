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
            ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.gravity = 0.9f;
        this.friction = DRAG;
        this.hasPhysics = true;
        this.lifetime = 22 + this.random.nextInt(14);
        this.quadSize = 0.055f + this.random.nextFloat() * 0.035f;
        this.setSpriteFromAge(sprites);
        this.setColor(1.0f, 0.94f, 0.72f);
    }

    @Override
    public void tick() {
        super.tick();
        // Cool from white through amber to a dull ember over the fragment's life.
        float t = Mth.clamp(this.age / (float) this.lifetime, 0.0f, 1.0f);
        this.setColor(Mth.lerp(t, 1.0f, 0.42f), Mth.lerp(t, 0.94f, 0.13f), Mth.lerp(t, 0.72f, 0.06f));
        this.setAlpha(1.0f - t * t);
    }

    @Override
    public void render(
            com.mojang.blaze3d.vertex.VertexConsumer buffer, net.minecraft.client.Camera camera, float partial) {
        var eye = camera.getPosition();
        float cx = (float) (Mth.lerp(partial, xo, x) - eye.x),
                cy = (float) (Mth.lerp(partial, yo, y) - eye.y),
                cz = (float) (Mth.lerp(partial, zo, z) - eye.z);
        var forward = new org.joml.Vector3f(0, 0, 1).rotate(camera.rotation());
        var travel = new org.joml.Vector3f((float) (x - xo), (float) (y - yo), (float) (z - zo));
        float speed = travel.length();
        travel.sub(new org.joml.Vector3f(forward).mul(travel.dot(forward)));
        if (travel.lengthSquared() < .00001f) {
            travel.set(0, 1, 0).rotate(camera.rotation());
        }
        travel.normalize();
        float fade = Mth.clamp((lifetime - age - partial) / 10f, 0, 1);
        float width = quadSize * .38f * fade;
        var across = new org.joml.Vector3f(travel).cross(forward).normalize().mul(width);
        travel.mul(Math.min(.42f, quadSize + speed * .28f) * fade);
        int light = getLightColor(partial);
        corner(
                buffer,
                cx - travel.x - across.x,
                cy - travel.y - across.y,
                cz - travel.z - across.z,
                getU0(),
                getV1(),
                light);
        corner(
                buffer,
                cx - travel.x + across.x,
                cy - travel.y + across.y,
                cz - travel.z + across.z,
                getU0(),
                getV0(),
                light);
        corner(
                buffer,
                cx + travel.x + across.x,
                cy + travel.y + across.y,
                cz + travel.z + across.z,
                getU1(),
                getV0(),
                light);
        corner(
                buffer,
                cx + travel.x - across.x,
                cy + travel.y - across.y,
                cz + travel.z - across.z,
                getU1(),
                getV1(),
                light);
    }

    private void corner(
            com.mojang.blaze3d.vertex.VertexConsumer buffer, float x, float y, float z, float u, float v, int light) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(rCol, gCol, bCol, alpha).setLight(light);
    }

    @Override
    protected int getLightColor(float partial) {
        int ambient = super.getLightColor(partial);
        int hot = (int) (Mth.clamp(1 - (age + partial) / 8f, 0, 1) * 240);
        return (ambient & 0xFFFF0000) | Math.max(ambient & 0xFFFF, hot);
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
            return new MineFragmentParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
