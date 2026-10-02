package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.network.WaterBlastPayload;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL30;

/** Surface waves, ballistic spray and submerged bubbles share three bounded geometry batches. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class WaterBlastClient {
    private static final boolean VEIL = ModList.get().isLoaded("veil");
    private static final List<Burst> BURSTS = new ArrayList<>();
    private static ClientLevel previousLevel;
    private static float fogStart = Float.MAX_VALUE, fogEnd = Float.MAX_VALUE;
    private static boolean rendererFailed;

    private WaterBlastClient() {}

    public static void handle(WaterBlastPayload payload) {
        if (!VEIL
                || Minecraft.getInstance().level == null
                || !Double.isFinite(payload.water().lengthSqr())
                || payload.surface() != null
                        && !Double.isFinite(payload.surface().lengthSqr())
                || !Float.isFinite(payload.power())) {
            return;
        }
        if (BURSTS.size() == 24) {
            BURSTS.removeFirst();
        }
        if (previousLevel != Minecraft.getInstance().level) {
            BURSTS.clear();
            previousLevel = Minecraft.getInstance().level;
        }
        BURSTS.add(new Burst(payload));
    }

    public static int activeCount() {
        return BURSTS.size();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (previousLevel != level) {
            BURSTS.clear();
            previousLevel = level;
        }
        if (!Minecraft.getInstance().isPaused()) {
            BURSTS.removeIf(burst -> ++burst.age > 110);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (!VEIL || rendererFailed) {
            return;
        }
        boolean submerged = event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (submerged) {
            fogStart = RenderSystem.getShaderFogStart();
            fogEnd = RenderSystem.getShaderFogEnd();
        }
        if ((!submerged && event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) || BURSTS.isEmpty()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || !com.cbc_more_content.client.veil.VeilWaterBlast.available()) {
            return;
        }
        int draw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        mc.getMainRenderTarget().bindWrite(false);
        try {
            // Bubbles precede transparent water; spray and surface crests follow it.
            com.cbc_more_content.client.veil.VeilWaterBlast.render(event, BURSTS, submerged, fogStart, fogEnd);
        } catch (RuntimeException | LinkageError error) {
            rendererFailed = true;
            CBCMoreContent.LOGGER.error("Water shader disabled; vanilla water effects remain available", error);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    public static final class Burst {
        public final WaterBlastPayload cue;
        public final float scale;
        public final int delay;
        public int age;
        public int sampledAge = -1, sampledSegments;
        public final byte[] wetArcs = new byte[144];

        private Burst(WaterBlastPayload cue) {
            this.cue = cue;
            scale = Mth.clamp(cue.power(), 1, 24);
            delay = cue.surface() == null ? 0 : 2 + (int) Math.clamp((cue.surface().y - cue.water().y) * .3, 0, 10);
        }
    }
}
