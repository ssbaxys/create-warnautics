package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.client.WaterBlastClient;
import com.cbc_more_content.client.veil.VeilWaterBlast;
import com.cbc_more_content.effects.BombBlastFx;
import com.cbc_more_content.effects.BombBurstBudget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;

/** Real water packets and GPU: surface, submerged, dry, covered, mass burst, depth and cleanup. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class WaterBlastClientCheck {
    private static boolean active, captured, pixelsChecked;
    private static int stage, age;
    private static volatile boolean ready;
    private static volatile Throwable failure;
    private static final StringBuilder REPORT = new StringBuilder();

    static void begin() {
        active = true;
        var mc = Minecraft.getInstance();
        mc.options.renderDistance().set(16);
        mc.options.broadcastOptions();
        prepare();
    }

    @SuppressWarnings("unchecked")
    private static List<WaterBlastClient.Burst> bursts() throws Exception {
        var field = WaterBlastClient.class.getDeclaredField("BURSTS");
        field.setAccessible(true);
        return (List<WaterBlastClient.Burst>) field.get(null);
    }

    private static void prepare() {
        ready = false;
        age = 0;
        captured = false;
        pixelsChecked = false;
        var mc = Minecraft.getInstance();
        mc.particleEngine.setLevel(mc.level);
        try {
            bursts().clear();
        } catch (Exception e) {
            finish(e);
            return;
        }
        mc.getSingleplayerServer().execute(() -> {
            try {
                var level = mc.getSingleplayerServer().overworld();
                level.setDayTime(6000);
                for (int x = -2; x <= 2; x++) {
                    for (int z = -1; z <= 3; z++) {
                        level.getChunk(x, z);
                    }
                }
                for (var cell : BlockPos.betweenClosed(-19, 80, -2, 19, 108, 43)) {
                    var state = cell.getY() == 80
                                    || cell.getY() < 95
                                            && (cell.getX() == -19
                                                    || cell.getX() == 19
                                                    || cell.getZ() == -2
                                                    || cell.getZ() == 43)
                            ? Blocks.STONE.defaultBlockState()
                            : cell.getY() < 95 && stage != 2
                                    ? Blocks.WATER.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState();
                    level.setBlock(cell, state, 2 | 16);
                }
                // Covered water has a solid ceiling; bubbles remain, surface spray must not exist.
                if (stage == 3) {
                    for (var cell : BlockPos.betweenClosed(-18, 95, -1, 18, 95, 42)) {
                        level.setBlock(cell, Blocks.BEDROCK.defaultBlockState(), 2 | 16);
                    }
                }
                var player = level.players().getFirst();
                player.teleportTo(0, stage == 1 || stage == 3 ? 86 : 102, stage == 1 || stage == 3 ? 3 : -16);
                ready = true;
            } catch (Throwable e) {
                failure = e;
                ready = true;
            }
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!active || !ready) {
            return;
        }
        try {
            if (failure != null) {
                throw new AssertionError("Water fixture", failure);
            }
            var mc = Minecraft.getInstance();
            mc.player.setYRot(0);
            mc.player.yRotO = 0;
            mc.player.setXRot(stage == 1 || stage == 3 ? 0 : 20);
            mc.player.xRotO = mc.player.getXRot();
            if (++age == 65) {
                mc.getSingleplayerServer().execute(() -> {
                    try {
                        var level = mc.getSingleplayerServer().overworld();
                        Vec3 at = new Vec3(0, stage == 1 || stage == 3 ? 86 : 92, 22);
                        if (stage == 0) {
                            com.cbc_more_content.effects.BombExplosionHandler.detonateSeaMine(
                                    level,
                                    null,
                                    com.cbc_more_content.damage.BombDamageSource.create(level),
                                    at,
                                    6.5f,
                                    6.5f);
                        } else if (stage == 1) {
                            BombBlastFx.play(level, at, BombSize.SEA, 10, BombBurstBudget.begin(level), true);
                        } else if (stage == 4) {
                            for (int i = 0; i < 64; i++) {
                                BombBlastFx.waterBurst(level, at.add((i % 8 - 3.5) * 2, 0, (i / 8 - 3.5) * 2), 7);
                            }
                        } else {
                            BombBlastFx.waterBurst(level, at, 7);
                        }
                    } catch (Throwable e) {
                        failure = e;
                    }
                });
            }
            if (age == 84) {
                if (!VeilWaterBlast.available()) {
                    throw new AssertionError("Water shader failed to compile");
                }
                int count = WaterBlastClient.activeCount();
                if (stage == 2 ? count != 0 : stage == 4 ? count != 24 : count != 1) {
                    throw new AssertionError("Water packet accounting, stage=" + stage + " active=" + count);
                }
                if (stage == 3 && bursts().getFirst().cue.surface() != null) {
                    throw new AssertionError("Covered water spawned surface spray");
                }
                REPORT.append("stage=")
                        .append(stage)
                        .append(" active=")
                        .append(count)
                        .append('\n');
            }
            if (age > 200) {
                if (WaterBlastClient.activeCount() != 0) {
                    throw new AssertionError("Water effects failed to expire");
                }
                if (!captured || stage != 2 && !pixelsChecked) {
                    throw new AssertionError("Missing water render verification");
                }
                if (++stage < 5) {
                    prepare();
                } else {
                    finish(null);
                }
            }
        } catch (Throwable e) {
            finish(e);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void pixels(RenderLevelStageEvent event) {
        boolean underwater = stage == 1 || stage == 3;
        if (!active
                || pixelsChecked
                || stage == 2
                || age < 86
                || age > 96
                || event.getStage()
                        != (underwater
                                ? RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                                : RenderLevelStageEvent.Stage.AFTER_PARTICLES)) {
            return;
        }
        var mc = Minecraft.getInstance();
        var target = new TextureTarget(
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, true, Minecraft.ON_OSX);
        try (var preview = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            preview.writeToFile(Path.of("water-world-" + stage + ".png"));
        } catch (Exception e) {
            finish(e);
            return;
        }
        var dynamic = foundry.veil.api.client.render.VeilRenderSystem.renderer().getDynamicBufferManger();
        boolean dynamicEnabled = dynamic.isEnabled();
        // Veil's render-type shard normally routes draws to a wrapper of the main target.
        // Suspend that redirect only for this isolated framebuffer visibility/depth probe.
        dynamic.setEnabled(false);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            for (boolean covered : List.of(false, true)) {
                RenderSystem.depthMask(true);
                GL11.glDepthMask(true);
                GL11.glClearDepth(1);
                RenderSystem.colorMask(true, true, true, true);
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                if (covered) {
                    GL11.glClearDepth(0);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    GL11.glClearDepth(1);
                }
                VeilWaterBlast.render(event, bursts(), underwater, 140, 180);
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
                GL11.glReadBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
                try (var image = new com.mojang.blaze3d.platform.NativeImage(target.width, target.height, false)) {
                    var bytes = org.lwjgl.BufferUtils.createByteBuffer(target.width * target.height * 4);
                    GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
                    for (int y = 0; y < target.height; y++) {
                        for (int x = 0; x < target.width; x++) {
                            int k = (y * target.width + x) * 4;
                            image.setPixelRGBA(
                                    x,
                                    target.height - 1 - y,
                                    (bytes.get(k) & 255)
                                            | ((bytes.get(k + 1) & 255) << 8)
                                            | ((bytes.get(k + 2) & 255) << 16)
                                            | ((bytes.get(k + 3) & 255) << 24));
                        }
                    }
                    int lit = 0;
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            if ((image.getPixelRGBA(x, y) & 0xFFFFFF) != 0) {
                                lit++;
                            }
                        }
                    }
                    if (!covered) {
                        image.writeToFile(Path.of("water-gpu-" + stage + ".png"));
                    }
                    if (covered ? lit != 0 : lit < 15) {
                        throw new AssertionError("Water shader depth test stage=" + stage + " covered=" + covered
                                + " pixels=" + lit + " quads=" + VeilWaterBlast.lastQuads() + " camera="
                                + event.getCamera().getPosition() + " cue=" + bursts().getFirst().cue);
                    }

                    REPORT.append("GPU stage=")
                            .append(stage)
                            .append(" covered=")
                            .append(covered)
                            .append(" pixels=")
                            .append(lit)
                            .append('\n');
                }
            }
            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                throw new AssertionError("Water shader GL error");
            }
            pixelsChecked = true;
        } catch (Throwable e) {
            finish(e);
        } finally {
            dynamic.setEnabled(dynamicEnabled);
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
            RenderSystem.depthMask(depthWrite);
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!active || captured || age < 92) {
            return;
        }
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(
                Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(Path.of("water-scene-" + stage + ".png"));
            captured = true;
        } catch (Throwable e) {
            finish(e);
        }
    }

    private static void finish(Throwable e) {
        active = false;
        try {
            Files.writeString(
                    Path.of("water-blast-check.txt"), e == null ? "PASS\n" + REPORT : "FAIL " + e + "\n" + REPORT);
        } catch (Exception write) {
            write.printStackTrace();
        }
        if (e != null) {
            CBCMoreContent.LOGGER.error("Water blast check failed", e);
        }
        Minecraft.getInstance().stop();
    }
}
