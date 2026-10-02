package com.cbc_more_content.client.veil;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.client.WaterBlastClient.Burst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** Procedural crests, ballistic spray and bubble fronts. At most three draws per render stage. */
public final class VeilWaterBlast extends RenderType {
    public static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "water_blast");
    private static final RenderType TYPE = create(
            "warnautics_water_blast",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            65536,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(VeilRenderBridge.shaderState(SHADER))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
    private static int lastQuads;

    private VeilWaterBlast() {
        super(
                "unused",
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                65536,
                false,
                false,
                () -> {},
                () -> {});
    }

    public static boolean available() {
        var shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        return shader != null && shader.isValid();
    }

    public static int lastQuads() {
        return lastQuads;
    }

    public static void render(
            RenderLevelStageEvent event, List<Burst> bursts, boolean submerged, float fogStart, float fogEnd) {
        var mc = Minecraft.getInstance();
        var shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        if (mc.level == null || shader == null || !shader.isValid()) {
            return;
        }
        var camera = event.getCamera().getPosition();
        var right = new Vector3f(1, 0, 0).rotate(event.getCamera().rotation());
        var up = new Vector3f(0, 1, 0).rotate(event.getCamera().rotation());
        Vec3 rx = new Vec3(right.x, right.y, right.z), uy = new Vec3(up.x, up.y, up.z);
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        shader.getUniformSafe("WaterView").setMatrix(event.getModelViewMatrix());
        shader.getUniformSafe("WaterProjection").setMatrix(event.getProjectionMatrix());
        shader.getUniformSafe("FogRange").setVector(fogStart, fogEnd);
        float[] fog = RenderSystem.getShaderFogColor();
        shader.getUniformSafe("FogColor").setVector(fog[0], fog[1], fog[2], fog[3]);
        shader.getUniformSafe("Time").setFloat((mc.level.getGameTime() % 24000 + partial) / 20f);
        var previousShader = RenderSystem.getShader();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        if (submerged) {
            lastQuads = 0;
        }
        try {
            for (int mode = submerged ? 2 : 0; mode <= (submerged ? 2 : 1); mode++) {
                shader.getUniformSafe("Mode").setInt(mode);
                var b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                int quads = 0;
                for (var burst : bursts) {
                    Vec3 center = mode == 2 ? burst.cue.water() : burst.cue.surface();
                    if (center == null || !event.getFrustum().isVisible(new AABB(center, center).inflate(35))) {
                        continue;
                    }
                    double pixels = Math.abs(event.getProjectionMatrix().m11())
                            * mc.getMainRenderTarget().height
                            * burst.scale
                            / Math.max(2, center.distanceTo(camera));
                    // Detail follows projected size, including a spyglass. Far waves remain continuous.
                    int detail = mc.options.particles().get() == ParticleStatus.MINIMAL
                            ? 0
                            : pixels > 100 ? 2 : pixels > 22 ? 1 : 0;
                    float age = burst.age + partial;
                    if (mode == 0) {
                        quads += waves(b, burst, age - burst.delay, camera, detail);
                    } else if (mode == 1) {
                        quads += spray(b, burst, age - burst.delay, camera, rx, uy, detail);
                    } else {
                        quads += bubbles(b, burst, age, camera, rx, uy, detail);
                    }
                }
                var mesh = b.build();
                if (mesh != null) {
                    TYPE.draw(mesh);
                }
                lastQuads += quads;
            }
        } finally {
            RenderSystem.setShader(() -> previousShader);
            if (depthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.depthMask(depthWrite);
        }
    }

    private static int waves(BufferBuilder b, Burst burst, float age, Vec3 camera, int detail) {
        if (age < 0 || age > 95) {
            return 0;
        }
        int segments = 24 + detail * 12, count = 0;
        if (burst.sampledAge != (int) age || burst.sampledSegments != segments) {
            java.util.Arrays.fill(burst.wetArcs, (byte) 0);
            burst.sampledAge = (int) age;
            burst.sampledSegments = segments;
        }
        Vec3 origin = burst.cue.surface();
        double strength = Math.sqrt(burst.scale);
        for (int wave = 0; wave < 3; wave++) {
            double time = age - wave * 6;
            if (time < 0) {
                continue;
            }
            double radius = .8 + time * (.12 + strength * .038);
            double width = .4 + strength * .16 + radius * .045;
            float alpha = (float) (Math.pow(1 - age / 100, 1.6) * .65 / (1 + wave * .35));
            for (int i = 0; i < segments; i++) {
                double a = i * Math.PI * 2 / segments, c = (i + 1) * Math.PI * 2 / segments;
                Vec3 middle = origin.add(Math.cos((a + c) * .5) * radius, -.10, Math.sin((a + c) * .5) * radius);
                // Separate arcs cannot bridge a shore or a solid block across the crest.
                Vec3 p0 = ring(origin, a, Math.max(.05, radius - width)), p1 = ring(origin, a, radius + width);
                Vec3 p2 = ring(origin, c, radius + width), p3 = ring(origin, c, Math.max(.05, radius - width));
                int arc = wave * segments + i;
                if (burst.wetArcs[arc] == 0) {
                    burst.wetArcs[arc] = (byte)
                            (wet(middle)
                                            && wet(p0.add(0, -.12, 0))
                                            && wet(p1.add(0, -.12, 0))
                                            && wet(p2.add(0, -.12, 0))
                                            && wet(p3.add(0, -.12, 0))
                                    ? 1
                                    : 2);
                }
                if (burst.wetArcs[arc] == 2) {
                    continue;
                }
                vertex(b, p0.subtract(camera), 0, (float) i / segments, .65f, .86f, 1, alpha);
                vertex(b, p1.subtract(camera), 1, (float) i / segments, .65f, .86f, 1, alpha);
                vertex(b, p2.subtract(camera), 1, (float) (i + 1) / segments, .65f, .86f, 1, alpha);
                vertex(b, p3.subtract(camera), 0, (float) (i + 1) / segments, .65f, .86f, 1, alpha);
                count++;
            }
        }
        return count;
    }

    private static Vec3 ring(Vec3 origin, double angle, double radius) {
        return origin.add(Math.cos(angle) * radius, .035, Math.sin(angle) * radius);
    }

    private static int spray(BufferBuilder b, Burst burst, float age, Vec3 camera, Vec3 rx, Vec3 uy, int detail) {
        if (age < 0 || age > 55) {
            return 0;
        }
        Vec3 origin = burst.cue.surface();
        double strength = Math.sqrt(burst.scale), depth = Math.max(0, origin.y - burst.cue.water().y);
        double impulse = (.32 + strength * .14) / (1 + depth * .12);
        int count = 0, drops = 18 + detail * 14;
        for (int i = 0; i < drops; i++) {
            double angle = i * 2.399963,
                    seed = fract(Math.sin(i * 127.1 + origin.x * .13 + origin.z * .17) * 43758.5453);
            double start = seed * 5, t = age - start;
            if (t < 0) {
                continue;
            }
            double vertical = impulse * (.65 + seed * .65);
            Vec3 pos = origin.add(
                    Math.cos(angle) * t * impulse * .28,
                    .1 + vertical * t - .018 * t * t,
                    Math.sin(angle) * t * impulse * .28);
            if (pos.y < origin.y || !open(pos)) {
                continue;
            }
            float alpha = (float) (.62 * (1 - age / 58) * Math.min(1, t / 2));
            double size = (.10 + strength * .065) * (1 + t * .04);
            billboard(
                    b,
                    pos.subtract(camera),
                    rx.scale(size),
                    uy.scale(size * (1.4 + Math.abs(vertical - .036 * t))),
                    .8f,
                    .92f,
                    1,
                    alpha);
            count++;
        }
        return count;
    }

    private static int bubbles(BufferBuilder b, Burst burst, float age, Vec3 camera, Vec3 rx, Vec3 uy, int detail) {
        int count = 0, bubbles = 12 + detail * 10;
        double strength = Math.sqrt(burst.scale);
        Vec3 origin = burst.cue.water();
        if (age < 16 && wet(origin)) {
            double front = .4 + age * (.18 + strength * .07);
            if (burst.cue.surface() != null) {
                front = Math.min(front, Math.max(.05, burst.cue.surface().y - origin.y));
            }
            billboard(b, origin.subtract(camera), rx.scale(front), uy.scale(front), .35f, .8f, 1, (1 - age / 16) * .3f);
            count++;
        }
        for (int i = 0; i < bubbles; i++) {
            double seed = fract(Math.sin(i * 311.7 + origin.x * .071 + origin.z * .11) * 43758.5453),
                    t = age - seed * 12;
            if (t < 0) {
                continue;
            }
            double angle = i * 2.399963;
            double radius = strength * .45 * seed * (1 - Math.exp(-t * .15));
            Vec3 pos = origin.add(Math.cos(angle) * radius, t * (.045 + seed * .075), Math.sin(angle) * radius);
            if (!wet(pos)) {
                continue;
            }
            float alpha = (float) (.64 * Math.min(1, t / 4) * Math.max(0, 1 - t / 105));
            double size = (.08 + seed * .17) * (1 + strength * .1 + t * .01);
            billboard(b, pos.subtract(camera), rx.scale(size), uy.scale(size), .58f, .88f, 1, alpha);
            count++;
        }
        return count;
    }

    private static boolean wet(Vec3 point) {
        var level = Minecraft.getInstance().level;
        BlockPos pos = BlockPos.containing(point);
        if (level == null || !level.hasChunkAt(pos)) {
            return false;
        }
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER)
                && point.y < pos.getY() + fluid.getHeight(level, pos)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean open(Vec3 point) {
        var level = Minecraft.getInstance().level;
        BlockPos pos = BlockPos.containing(point);
        return level != null
                && level.hasChunkAt(pos)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static double fract(double x) {
        return x - Math.floor(x);
    }

    private static void billboard(
            BufferBuilder b, Vec3 center, Vec3 rx, Vec3 uy, float r, float g, float blue, float alpha) {
        vertex(b, center.subtract(rx).subtract(uy), 0, 0, r, g, blue, alpha);
        vertex(b, center.add(rx).subtract(uy), 1, 0, r, g, blue, alpha);
        vertex(b, center.add(rx).add(uy), 1, 1, r, g, blue, alpha);
        vertex(b, center.subtract(rx).add(uy), 0, 1, r, g, blue, alpha);
    }

    private static void vertex(BufferBuilder b, Vec3 pos, float u, float v, float r, float g, float blue, float alpha) {
        b.addVertex((float) pos.x, (float) pos.y, (float) pos.z)
                .setUv(u, v)
                .setColor(r, g, blue, Mth.clamp(alpha, 0, 1));
    }
}
