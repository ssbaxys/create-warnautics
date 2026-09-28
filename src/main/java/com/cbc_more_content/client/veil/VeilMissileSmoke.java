package com.cbc_more_content.client.veil;

import com.cbc_more_content.CBCMoreContent;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/** One batched particle pass: softly shaded procedural billows without pixelated sprite edges. */
public final class VeilMissileSmoke {
    public static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "missile_smoke");
    public static final ParticleRenderType TYPE = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            var buffer = PARTICLE_SHEET_TRANSLUCENT.begin(tesselator, textures);
            RenderSystem.depthMask(Minecraft.useShaderTransparency());
            VeilRenderSystem.setShader(SHADER);
            return buffer;
        }

        @Override
        public String toString() {
            return "WARNAUTICS_SOFT_MISSILE_SMOKE";
        }
    };

    private VeilMissileSmoke() {}

    public static boolean available() {
        var shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        return shader != null && shader.isValid();
    }
}
