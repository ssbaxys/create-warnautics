package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.effects.BombBlastFx;
import com.cbc_more_content.effects.BombBurstBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Actual networked smoke, three bomb sizes and a chain seen at near/far camera distances. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class BlastVolumeClientCheck {
    private static boolean active;
    private static int stage, age, captureStep;
    private static final int[] CAPTURE_AGES = {41, 60, 90};
    private static volatile boolean ready;
    private static volatile Throwable failure;
    private static final StringBuilder report = new StringBuilder("PASS\n");

    static void begin() {
        active = true;
        var mc = Minecraft.getInstance();
        mc.options.hideGui = true;
        mc.options.renderDistance().set(16);
        mc.getSingleplayerServer().getPlayerList().setViewDistance(16);
        prepare();
    }

    private static void prepare() {
        ready = false;
        age = 0;
        captureStep = 0;
        var mc = Minecraft.getInstance();
        mc.particleEngine.setLevel(mc.level);
        mc.getSingleplayerServer().execute(() -> {
            try {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(0, 98, stage == 0 ? 0 : -100);
                player.setYRot(0);
                player.setXRot(0);
                var level = server.overworld();
                level.setDayTime(6000);
                for (int x = -3; x <= 3; x++) {
                    for (int z = 2; z <= 4; z++) {
                        level.getChunk(x, z);
                    }
                }
                ready = true;
            } catch (Throwable error) {
                failure = error;
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
                throw new AssertionError("Server fixture", failure);
            }
            var mc = Minecraft.getInstance();
            mc.player.setYRot(0);
            mc.player.yRotO = 0;
            mc.player.setXRot(0);
            mc.player.xRotO = 0;
            age++;
            if (age == 40) {
                mc.getSingleplayerServer().execute(() -> {
                    try {
                        var level = mc.getSingleplayerServer().overworld();
                        for (int i = 0; i < 3; i++) {
                            var size = new BombSize[] {BombSize.SMALL, BombSize.MEDIUM, BombSize.LARGE}[i];
                            BombBlastFx.play(level, new Vec3((i - 1) * 24, 92, 55), size, size.blockBlastPower);
                        }
                        // Simultaneous requests exercise the real per-tick load budget, not a hand-picked LOD.
                        for (int i = 0; i < 32; i++) {
                            BombBlastFx.play(
                                    level,
                                    new Vec3((i - 15.5) * 3, 92, 85),
                                    BombSize.SMALL,
                                    BombSize.SMALL.blockBlastPower,
                                    BombBurstBudget.begin(level));
                        }
                    } catch (Throwable error) {
                        failure = error;
                    }
                });
            }
        } catch (Throwable error) {
            finish(error);
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!active || captureStep >= CAPTURE_AGES.length || age < CAPTURE_AGES[captureStep]) {
            return;
        }
        try {
            var mc = Minecraft.getInstance();
            var output = Path.of("blast-volume-" + stage + "-" + CAPTURE_AGES[captureStep] + ".png");
            try (var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int visiblePixels = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getPixelRGBA(x, y) & 0xFFFFFF) != 0) {
                            visiblePixels++;
                        }
                    }
                }
                if (visiblePixels < image.getWidth() * image.getHeight() / 2) {
                    throw new AssertionError("Empty framebuffer capture");
                }
                image.writeToFile(output);
            }
            report.append(output).append("\n");
            if (++captureStep == CAPTURE_AGES.length) {
                if (++stage < 2) {
                    prepare();
                } else {
                    finish(null);
                }
            }
        } catch (Throwable error) {
            finish(error);
        }
    }

    private static void finish(Throwable error) {
        active = false;
        try {
            Files.writeString(Path.of("blast-volume-check.txt"), error == null ? report.toString() : "FAIL " + error);
        } catch (Exception writeError) {
            writeError.printStackTrace();
        }
        if (error != null) {
            CBCMoreContent.LOGGER.error("Blast volume check failed", error);
        }
        Minecraft.getInstance().stop();
    }
}
