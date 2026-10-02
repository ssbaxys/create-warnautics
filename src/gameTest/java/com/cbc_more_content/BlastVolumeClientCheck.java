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

/** Real packet delivery: separate charges, underwater mine, distant effects and a 64-charge burst. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class BlastVolumeClientCheck {
    private static boolean active;
    private static int stage, age, captureStep;
    private static final int[] CAPTURE_AGES = {52, 65, 95};
    private static volatile boolean ready;
    private static volatile Throwable failure;
    private static final StringBuilder report = new StringBuilder("PASS\n");

    static void begin() {
        active = true;
        var mc = Minecraft.getInstance();
        mc.options.hideGui = true;
        mc.options.renderDistance().set(16);
        mc.options.broadcastOptions();
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
                player.teleportTo(0, 98, stage < 5 ? 0 : -100);
                player.setYRot(0);
                player.setXRot(0);
                var level = server.overworld();
                level.setDayTime(6000);
                // The preceding post-effect fixture built an occlusion wall; remove it for this view.
                for (var cell : net.minecraft.core.BlockPos.betweenClosed(-32, 70, 4, 32, 128, 4)) {
                    level.setBlock(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
                }
                for (var cell : net.minecraft.core.BlockPos.betweenClosed(-5, 89, 50, 5, 96, 60)) {
                    level.setBlock(
                            cell,
                            stage == 4 && cell.getY() < 95
                                    ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                            2 | 16);
                }
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
                        var viewer = level.players().getFirst();
                        if (viewer.requestedViewDistance() < 16) {
                            throw new AssertionError(
                                    "Fixture view distance was not sent to server: " + viewer.requestedViewDistance());
                        }
                        Vec3 source = new Vec3(0, 92, 55);
                        switch (stage) {
                            case 0 -> BombBlastFx.play(level, source, BombSize.SMALL, BombSize.SMALL.blockBlastPower);
                            case 1 -> BombBlastFx.play(level, source, BombSize.MEDIUM, BombSize.MEDIUM.blockBlastPower);
                            case 2 -> com.cbc_more_content.block.C4BlockEntity.explode(level, source);
                            case 3 -> {
                                var wearer = new net.minecraft.world.entity.monster.Zombie(level);
                                wearer.setPos(source);
                                level.addFreshEntity(wearer);
                                wearer.setItemSlot(
                                        net.minecraft.world.entity.EquipmentSlot.CHEST,
                                        new net.minecraft.world.item.ItemStack(
                                                com.cbc_more_content.registry.ModItems.BOMB_VEST.get()));
                                com.cbc_more_content.item.BombVestItem.detonate(level, wearer);
                                wearer.discard();
                            }
                            case 4 -> com.cbc_more_content.effects.BombExplosionHandler.detonateSeaMine(
                                    level,
                                    null,
                                    com.cbc_more_content.damage.BombDamageSource.create(level),
                                    source,
                                    com.cbc_more_content.mine.MineType.SEA.blockBlastPower,
                                    com.cbc_more_content.mine.MineType.SEA.entityBlastPower);
                            case 5 -> {
                                for (int i = 0; i < 3; i++) {
                                    var size = new BombSize[] {BombSize.SMALL, BombSize.MEDIUM, BombSize.LARGE}[i];
                                    BombBlastFx.play(level, new Vec3((i - 1) * 24, 92, 55), size, size.blockBlastPower);
                                }
                            }
                            default -> {
                                for (int i = 0; i < 64; i++) {
                                    Vec3 point = new Vec3((i % 16 - 7.5) * 4, 92, 55 + i / 16 * 8);
                                    BombBlastFx.play(
                                            level,
                                            point,
                                            i % 3 == 0 ? BombSize.SEA : BombSize.SMALL,
                                            i % 3 == 0 ? 6.5f : BombSize.SMALL.blockBlastPower,
                                            BombBurstBudget.begin(level));
                                }
                            }
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
            if (captureStep == 1) {
                int cbc = particleCount("ShellExplosionSmokeParticle");
                int soft = particleCount("MissileSmokeParticle");
                if (cbc < 5 || soft < 3) {
                    throw new AssertionError(
                            "Missing actual smoke layer at stage " + stage + ": CBC=" + cbc + " soft=" + soft);
                }
                report.append("stage=")
                        .append(stage)
                        .append(" CBC=")
                        .append(cbc)
                        .append(" soft=")
                        .append(soft)
                        .append("\n");
            }
            report.append(output).append("\n");
            if (++captureStep == CAPTURE_AGES.length) {
                if (++stage < 7) {
                    prepare();
                } else {
                    finish(null);
                }
            }
        } catch (Throwable error) {
            finish(error);
        }
    }

    private static int particleCount(String className) throws Exception {
        int count = 0;
        var engine = Minecraft.getInstance().particleEngine;
        var field = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
        field.setAccessible(true);
        var batches = (java.util.Map<?, ?>) field.get(engine);
        for (var batch : batches.values()) {
            for (var particle : (Iterable<?>) batch) {
                if (particle.getClass().getSimpleName().equals(className)) {
                    count++;
                }
            }
        }
        return count;
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
