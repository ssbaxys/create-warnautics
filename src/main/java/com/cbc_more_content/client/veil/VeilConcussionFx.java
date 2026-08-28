package com.cbc_more_content.client.veil;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.client.ConcussionClient;
import com.cbc_more_content.client.FlashRenderMode;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.forge.event.ForgeVeilPostProcessingEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Veil post pass for the concussion effect.
 * <p>
 * The fallback overlay can only stack translucent quads over the finished frame, which
 * is why it never looked like anything but stacked quads. This pass has the scene
 * texture, so the defocus is a real gaussian blur with chromatic separation, desaturation
 * and per-pixel grain, all driven from the same shock value.
 */
public final class VeilConcussionFx {
    /** A plain single blit, for the same reason as {@link VeilBombFx#PIPELINE}. */
    public static final ResourceLocation PIPELINE =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "concussion");
    private static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "concussion");
    /** Priority above the bomb flash so the blur is applied to the already-lit frame. */
    private static final int PRIORITY = 60;

    private static boolean active;

    private VeilConcussionFx() {
    }

    /** True once the pass is running, so the fallback overlay can stand down. */
    public static boolean isHandlingConcussion() {
        return active;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean wanted = !FlashRenderMode.sodiumExtrasLoaded()
                && com.cbc_more_content.config.WarnauticsClientConfig.screenEffects()
                && mc.level != null
                && mc.player != null
                && mc.player.isAlive()
                && ConcussionClient.shock() > 0.002f;
        if (wanted == active) {
            return;
        }
        try {
            PostProcessingManager post = VeilRenderSystem.renderer().getPostProcessingManager();
            if (wanted) {
                if (!post.isActive(PIPELINE)) {
                    post.add(PRIORITY, PIPELINE);
                }
            } else {
                post.remove(PIPELINE);
            }
            active = wanted;
        } catch (Throwable t) {
            active = false;
            CBCMoreContent.LOGGER.debug("Veil concussion pipeline toggle failed: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onPostPre(ForgeVeilPostProcessingEvent.Pre event) {
        if (!PIPELINE.equals(event.getName())) {
            return;
        }
        try {
            ShaderProgram shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
            if (shader == null) {
                return;
            }
            shader.getUniformSafe("Shock").setFloat(ConcussionClient.shock());
            shader.getUniformSafe("Recovery").setFloat(ConcussionClient.recovery());
            shader.getUniformSafe("Time").setFloat(
                    (Minecraft.getInstance().level.getGameTime() % 20000L) / 20.0f);
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.debug("Veil concussion uniform upload failed: {}", t.toString());
        }
    }
}
