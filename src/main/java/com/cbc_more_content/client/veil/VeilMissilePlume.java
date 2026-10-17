package com.cbc_more_content.client.veil;

import com.cbc_more_content.CBCMoreContent;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * A ray-marched rocket plume in nozzle space (+X is downstream), drawn as one bounded box,
 * no full-screen pass: nozzle exit, shock-diamond core, mixing layer and glow.
 */
public final class VeilMissilePlume extends RenderType {
    public static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "missile_plume");
    /**
     * Premultiplied blending: the shader writes emitted light in colour and how much of the
     * background the gas hides in alpha. Pure additive light washed every flame to white
     * against a daytime sky.
     */
    private static final TransparencyStateShard EMISSIVE_OVER = new TransparencyStateShard(
            "warnautics_emissive_over",
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(
                        com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            },
            () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });

    private static final RenderType TYPE = create(
            "warnautics_missile_plume",
            DefaultVertexFormat.POSITION,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(VeilRenderBridge.shaderState(SHADER))
                    .setTransparencyState(EMISSIVE_OVER)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    // Record the first lit sample so clouds in front obscure the exhaust,
                    // while clouds behind it still fail the depth test.
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .createCompositeState(false));

    private VeilMissilePlume() {
        super("unused", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 1536, false, false, () -> {}, () -> {});
    }

    public static boolean available() {
        var shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        return shader != null && shader.isValid();
    }

    /** The explicit view keeps this independent of Sable's current plot/model matrix. */
    /** Solid booster, interceptor motor, turbofan sustainer. */
    public static final int BOOSTER = 0;

    public static final int INTERCEPTOR = 1;
    public static final int TURBOFAN = 2;
    /** Half the box across, and its length downstream; must match the shader. */
    private static final float HALF = 1.6f;

    private static final float LENGTH = 9.0f;

    /**
     * @param kind  {@link #BOOSTER}, {@link #INTERCEPTOR} or {@link #TURBOFAN}
     * @param style 0 realistic flame, 1 plasma jet
     */
    public static void render(
            Matrix4f nozzleView,
            Matrix4f projection,
            float time,
            float strength,
            int samples,
            float fogStart,
            float fogEnd,
            int kind,
            float style) {
        var shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        if (shader == null || !shader.isValid() || strength <= 0) {
            return;
        }
        var camera = new Matrix4f(nozzleView).invert().transformPosition(new Vector3f());
        shader.getUniformSafe("PlumeView").setMatrix(nozzleView);
        shader.getUniformSafe("PlumeProjection").setMatrix(projection);
        shader.getUniformSafe("CameraLocal").setVector(camera);
        shader.getUniformSafe("Time").setFloat(time);
        shader.getUniformSafe("Kind").setInt(kind);
        shader.getUniformSafe("Style").setFloat(style);
        shader.getUniformSafe("Strength").setFloat(strength);
        shader.getUniformSafe("Samples").setInt(Math.clamp(samples, 8, 40));
        shader.getUniformSafe("FogRange").setVector(fogStart, fogEnd);
        // Filter the incandescent core across a pixel instead of letting it flicker between rays.
        float pixelsPerBlock = Math.abs(projection.m11())
                * net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().height
                * .5f;
        shader.getUniformSafe("MinimumRadius")
                .setFloat(Math.min(.55f, camera.length() * .75f / Math.max(1, pixelsPerBlock)));
        var previousShader = RenderSystem.getShader();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        var b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        // Outward winding. Only back faces survive in the shader, including when inside the volume.
        float[][] v = {
            {0, -HALF, -HALF},
            {LENGTH, -HALF, -HALF},
            {LENGTH, HALF, -HALF},
            {0, HALF, -HALF},
            {0, -HALF, HALF},
            {LENGTH, -HALF, HALF},
            {LENGTH, HALF, HALF},
            {0, HALF, HALF}
        };
        int[][] faces = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3}, {1, 2, 6, 5}, {0, 1, 5, 4}, {3, 7, 6, 2}};
        for (int[] face : faces) {
            for (int i : face) {
                b.addVertex(v[i][0], v[i][1], v[i][2]);
            }
        }
        try {
            TYPE.draw(b.buildOrThrow());
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
}
