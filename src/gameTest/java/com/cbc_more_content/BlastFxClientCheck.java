package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.client.BombFlashClient;
import com.cbc_more_content.client.ConcussionClient;
import com.cbc_more_content.client.FlashExposure;
import com.cbc_more_content.client.veil.VeilBombFx;
import com.cbc_more_content.client.veil.VeilConcussionFx;
import com.cbc_more_content.effects.BlastDebris;
import com.cbc_more_content.entity.BlastDebrisEntity;
import com.cbc_more_content.network.BombFlashPayload;
import com.cbc_more_content.network.ConcussionPayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.forge.event.ForgeVeilPostProcessingEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/** Real disposable client world, networked debris and the actual Veil post pipeline. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class BlastFxClientCheck {
    private static boolean creating, launched, finished;
    private static int ticks, age = -1, sampled = -1, gpuFrames, maxSeen, warmWait;
    private static int[] before;
    private static float beforeTurn, afterTurn;
    private static boolean melted;
    private static volatile int serverPeak;
    private static final List<String> RESULTS = new ArrayList<>(), FAILURES = new ArrayList<>();
    private static final Vec3 SOURCE = new Vec3(0, 84, 70);

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("warnautics.blastFxCheck") || finished) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (!creating) {
            if (mc.screen == null || mc.getOverlay() != null || ++ticks < 20) {
                return;
            }
            creating = true;
            if (Boolean.getBoolean("warnautics.expectSodium")) {
                try {
                    Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod");
                    check(net.neoforged.fml.ModList.get().isLoaded("sodium"), "Actual Sodium renderer loaded");
                    check(net.neoforged.fml.ModList.get().isLoaded("sodium_extra"), "Exact Sodium Extra add-on loaded");
                } catch (ClassNotFoundException e) {
                    FAILURES.add("Sodium bootstrap present without its nested renderer");
                    finish();
                    return;
                }
            }
            for (int fps : new int[] {30, 60, 144}) {
                float value = 1;
                for (int i = 0; i < fps; i++) {
                    value = FlashExposure.approach(value, 0, 1f / fps, .018f, .22f);
                }
                check(Math.abs(value - Math.exp(-1 / .22)) < .00001, "Frame-independent recovery " + fps + " fps");
            }
            ticks = 0;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(8);
            mc.options.hideGui = true;
            if (Boolean.getBoolean("warnautics.blastFxFabulous")) {
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS);
            }
            mc.createWorldOpenFlows()
                    .createFreshLevel(
                            "blast-fx-test",
                            new LevelSettings(
                                    "Blast FX test",
                                    GameType.CREATIVE,
                                    false,
                                    Difficulty.PEACEFUL,
                                    true,
                                    new GameRules(),
                                    WorldDataConfiguration.DEFAULT),
                            new WorldOptions(51, false, false),
                            registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.FLAT)
                                    .createWorldDimensions(),
                            mc.screen);
            return;
        }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) {
            return;
        }
        mc.setScreen(null);
        if (!launched) {
            if (++ticks < 100) {
                return;
            }
            launched = true;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                level.setDayTime(1000);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(0, 82, 0);
                for (var p : BlockPos.betweenClosed(-20, 80, -20, 20, 80, 90)) {
                    level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            });
            return;
        }
        if (mc.player.getY() < 81) {
            return;
        }
        age++;
        if (age == 25 && gpuFrames == 0) {
            age--;
            if (++warmWait > 300) {
                FAILURES.add("Post pipeline did not render during warmup");
                finish();
                return;
            }
        }
        float yaw = age >= 30 && age < 60 ? 180 : age >= 90 && age < 115 ? 20 : 0;
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(age >= 140 ? 12 : 0);
        mc.player.xRotO = mc.player.getXRot();
        mc.player.setPos(0, 82, age >= 140 ? -8 : 0);
        mc.player.setOldPosAndRot();
        if (age == 0) {
            flash(SOURCE);
        }
        if (age < 30) {
            BombFlashClient.flashes().forEach(f -> f.age = 0);
        }
        if (age == 29) {
            beforeTurn = FlashExposure.exposure();
        }
        if (age == 60) {
            expire();
            flash(SOURCE.add(-25, 0, 0));
            flash(SOURCE.add(25, 0, 0));
        }
        if (age >= 60 && age < 110) {
            BombFlashClient.flashes().forEach(f -> f.age = 0);
        }
        if (age == 115) {
            expire();
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().overworld();
                for (var p : BlockPos.betweenClosed(-32, 70, 4, 32, 128, 4)) {
                    level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            });
        }
        if (age == 124) {
            flash(SOURCE);
        }
        if (age >= 124 && age < 138) {
            BombFlashClient.flashes().forEach(f -> f.age = 0);
        }
        if (age == 140) {
            expire();
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().overworld();
                for (var p : BlockPos.betweenClosed(-32, 70, 4, 32, 128, 4)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            });
        }
        if (age == 145) {
            mc.getSingleplayerServer().execute(() -> burstTicks = 12);
        }
        if (age == 175) {
            ConcussionClient.handle(new ConcussionPayload(1.0f, 0.0f, 80));
        }
        if (age == 185) {
            check(VeilConcussionFx.isHandlingConcussion(), "Concussion Veil pipeline active");
        }
        int seen = 0;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof BlastDebrisEntity piece) {
                seen++;
                if (piece.meltProgress(0) > .2f && piece.meltProgress(0) < .9f) {
                    melted = true;
                }
            }
        }
        maxSeen = Math.max(maxSeen, seen);
        if (age == 360) {
            check(gpuFrames > 0, "Actual Veil pipeline changed pixels");
            check(serverPeak == BlastDebris.MAX_ACTIVE, "Server debris cap reached: " + serverPeak);
            check(maxSeen > 80, "Networked debris visible: " + maxSeen);
            check(melted, "Client received and rendered melting phase");
            check(seen == 0, "Debris cleaned up: " + seen);
            finish();
        }
    }

    private static int burstTicks;

    @SubscribeEvent
    public static void burst(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("warnautics.blastFxCheck") || burstTicks <= 0) {
            return;
        }
        burstTicks--;
        var mc = Minecraft.getInstance();
        var level = mc.getSingleplayerServer().overworld();
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -7; x <= 7; x++) {
            for (int z = 0; z < 8; z++) {
                var pos = new BlockPos(x, 81, z);
                var block =
                        switch (Math.floorMod(x, 3)) {
                            case 0 -> Blocks.OAK_PLANKS;
                            case 1 -> Blocks.IRON_BLOCK;
                            default -> Blocks.STONE;
                        };
                level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
                candidates.add(pos);
            }
        }
        for (int i = 0; i < 24; i++) {
            BlastDebris.fling(level, new Vec3(0, 81, 4), candidates);
        }
        candidates.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
        int count = level.getEntitiesOfClass(BlastDebrisEntity.class, new AABB(-150, 0, -150, 150, 250, 150))
                .size();
        serverPeak = Math.max(serverPeak, count);
        if (count > BlastDebris.MAX_ACTIVE) {
            FAILURES.add("Server debris exceeded limit: " + count);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void frame(RenderLevelStageEvent event) {
        if (!Boolean.getBoolean("warnautics.blastFxCheck")
                || finished
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS
                || sampled == age) {
            return;
        }
        if (age != 20 && age != 32 && age != 40 && age != 70 && age != 96 && age != 136 && age != 175 && age != 210) {
            return;
        }
        sampled = age;
        float exposure = FlashExposure.exposure();
        var first = new Vector4f(FlashExposure.source(0));
        var second = new Vector4f(FlashExposure.source(1));
        RESULTS.add("age=" + age + " exposure=" + exposure + " source0=" + first + " source1=" + second);
        if (age == 20) {
            check(first.z > .1 && Math.abs(first.x - .5) < .02, "Front flash centered and visible");
        }
        if (age == 32) {
            afterTurn = exposure;
            check(first.z == 0, "Turning behind removes world hotspot immediately");
            check(exposure > 0 && exposure < beforeTurn, "Recovery persists and decays after turn");
        }
        if (age == 40) {
            check(exposure < afterTurn, "Afterimage continues fading while looking away");
        }
        if (age == 70) {
            check(
                    first.z > .1 && second.z > .1 && Math.abs(first.x - second.x) > .2,
                    "Separate simultaneous flashes keep separate origins");
        }
        if (age == 96) {
            for (int i = 0; i < 2; i++) {
                var source = FlashExposure.source(i);
                if (source.z < .001) {
                    continue;
                }
                double best = 1;
                for (var flash : BombFlashClient.flashes()) {
                    var d = flash.pos.subtract(event.getCamera().getPosition());
                    var clip = event.getProjectionMatrix()
                            .transform(event.getModelViewMatrix()
                                    .transform(new Vector4f((float) d.x, (float) d.y, (float) d.z, 1)));
                    if (clip.w > 0) {
                        best = Math.min(best, Math.abs(clip.x / clip.w * .5 + .5 - source.x));
                    }
                }
                check(best < .001, "Hotspot follows current frame on abrupt camera turn");
            }
        }
        if (age == 136) {
            check(first.z < .001, "Solid wall blocks direct and elevated flash");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void pre(ForgeVeilPostProcessingEvent.Pre event) {
        if (!Boolean.getBoolean("warnautics.blastFxCheck")
                || finished
                || !event.getName().equals(VeilBombFx.PIPELINE)
                || age < 5
                || age >= 30
                || gpuFrames > 0) {
            return;
        }
        try (var pixels = capture()) {
            before = pixels.getPixelsRGBA();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void post(ForgeVeilPostProcessingEvent.Post event) {
        if (before == null || !event.getName().equals(VeilBombFx.PIPELINE)) {
            return;
        }
        try (var pixels = capture()) {
            int count = 0;
            var after = pixels.getPixelsRGBA();
            for (int i = 0; i < after.length; i++) {
                if ((before[i] & 0xFFFFFF) != (after[i] & 0xFFFFFF)) {
                    count++;
                }
            }
            if (count > 500) {
                gpuFrames++;
            }
            RESULTS.add("GPU changed pixels=" + count);
            pixels.flipY();
            pixels.writeToFile(Path.of("flash-front.png"));
        } catch (Exception e) {
            FAILURES.add(e.toString());
        } finally {
            before = null;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void screenshots(RenderLevelStageEvent event) {
        if (!Boolean.getBoolean("warnautics.blastFxCheck")
                || finished
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (age != 32 && age != 70 && age != 96 && age != 136 && age != 175 && age != 185 && age != 210) {
            return;
        }
        try (var pixels = capture()) {
            pixels.flipY();
            pixels.writeToFile(Path.of("blast-stage-" + age + ".png"));
        } catch (Exception e) {
            FAILURES.add(e.toString());
        }
    }

    private static NativeImage capture() {
        var target = Minecraft.getInstance().getMainRenderTarget();
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        target.bindRead();
        var image = new NativeImage(target.width, target.height, false);
        image.downloadTexture(0, false);
        RenderSystem.bindTexture(texture);
        return image;
    }

    private static void flash(Vec3 pos) {
        BombFlashClient.handle(new BombFlashPayload(pos.x, pos.y, pos.z, 1, (byte) BombSize.MOAB.ordinal()));
    }

    private static void expire() {
        BombFlashClient.flashes().forEach(f -> f.age = f.life);
        BombFlashClient.tick();
        FlashExposure.tick();
    }

    private static void check(boolean value, String label) {
        (value ? RESULTS : FAILURES).add((value ? "PASS " : "FAIL ") + label);
    }

    private static void finish() {
        finished = true;
        try {
            Files.writeString(
                    Path.of("blast-fx-check.txt"),
                    (FAILURES.isEmpty() ? "PASS" : "FAIL") + "\n" + String.join("\n", RESULTS) + "\n"
                            + String.join("\n", FAILURES));
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (FAILURES.isEmpty() && Boolean.getBoolean("warnautics.blastFxIncludeFlight")) {
            try {
                MissileFlightClientCheck.beginInExistingWorld();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (FAILURES.isEmpty() && Boolean.getBoolean("warnautics.blastFxIncludeVolume")) {
            BlastVolumeClientCheck.begin();
            return;
        }
        Minecraft.getInstance().stop();
    }
}
