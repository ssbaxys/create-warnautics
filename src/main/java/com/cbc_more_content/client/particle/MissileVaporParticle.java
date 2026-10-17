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
import org.joml.Vector3f;

/**
 * A wingtip vortex made visible: a thin white streak left hanging along the flight path in
 * a hard turn. The spawn velocity is read as the streak's direction and length, not as
 * motion; the vapour itself barely drifts and fades within half a second.
 */
@OnlyIn(Dist.CLIENT)
public class MissileVaporParticle extends TextureSheetParticle {
    private final Vector3f axis;
    private final float length;

    protected MissileVaporParticle(
            ClientLevel level, double x, double y, double z, double ax, double ay, double az, SpriteSet sprites) {
        super(level, x, y, z);
        this.axis = new Vector3f((float) ax, (float) ay, (float) az);
        this.length = Math.max(0.2f, this.axis.length());
        if (this.axis.lengthSquared() > 1.0E-6f) {
            this.axis.normalize();
        } else {
            this.axis.set(1, 0, 0);
        }
        this.xd = -this.axis.x * 0.02D;
        this.yd = -this.axis.y * 0.02D;
        this.zd = -this.axis.z * 0.02D;
        this.gravity = 0.0f;
        this.friction = 0.9f;
        this.hasPhysics = false;
        this.lifetime = 9 + this.random.nextInt(6);
        this.quadSize = 0.03f + this.random.nextFloat() * 0.015f;
        this.setColor(0.95f, 0.97f, 1.0f);
        this.setAlpha(0.45f);
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        float t = Mth.clamp(this.age / (float) this.lifetime, 0.0f, 1.0f);
        this.setAlpha(0.45f * (1.0f - t) * (1.0f - t));
    }

    @Override
    public void render(
            com.mojang.blaze3d.vertex.VertexConsumer buffer, net.minecraft.client.Camera camera, float partial) {
        var eye = camera.getPosition();
        float cx = (float) (Mth.lerp(partial, xo, x) - eye.x);
        float cy = (float) (Mth.lerp(partial, yo, y) - eye.y);
        float cz = (float) (Mth.lerp(partial, zo, z) - eye.z);
        var forward = new Vector3f(0, 0, 1).rotate(camera.rotation());
        var along = new Vector3f(this.axis).sub(new Vector3f(forward).mul(this.axis.dot(forward)));
        if (along.lengthSquared() < 1.0E-5f) {
            along.set(0, 1, 0).rotate(camera.rotation());
        }
        along.normalize();
        float grow = 1.0f + (this.age + partial) / this.lifetime * 0.6f;
        var across = new Vector3f(along).cross(forward).normalize().mul(this.quadSize * grow);
        along.mul(this.length * 0.5f);
        int light = this.getLightColor(partial);
        corner(
                buffer,
                cx - along.x - across.x,
                cy - along.y - across.y,
                cz - along.z - across.z,
                getU0(),
                getV1(),
                light);
        corner(
                buffer,
                cx - along.x + across.x,
                cy - along.y + across.y,
                cz - along.z + across.z,
                getU0(),
                getV0(),
                light);
        corner(
                buffer,
                cx + along.x + across.x,
                cy + along.y + across.y,
                cz + along.z + across.z,
                getU1(),
                getV0(),
                light);
        corner(
                buffer,
                cx + along.x - across.x,
                cy + along.y - across.y,
                cz + along.z - across.z,
                getU1(),
                getV1(),
                light);
    }

    private void corner(
            com.mojang.blaze3d.vertex.VertexConsumer buffer, float x, float y, float z, float u, float v, int light) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(rCol, gCol, bCol, alpha).setLight(light);
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
            return new MissileVaporParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
