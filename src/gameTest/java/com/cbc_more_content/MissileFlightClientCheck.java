package com.cbc_more_content;

import com.cbc_more_content.client.MissileExhaustLights;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModEntityTypes;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;

/** Disposable single-player world: compares the real scene before/after the exhaust render stage. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class MissileFlightClientCheck {
    private static boolean creating;
    private static boolean launched;
    private static boolean finished;
    private static int ticks;
    private static UUID missileId;
    private static int lastSample = -1;
    private static int[] before;
    private static final List<String> RESULTS = new ArrayList<>();
    private static final double[] DISTANCES = {12, 24, 48, 72, 120, 150, 176, 210, 55, 130, 300, 420, 480};
    private static final List<String> FAILURES = new ArrayList<>();
    private static boolean chained;

    private MissileFlightClientCheck() {}

    static void beginInExistingWorld() throws Exception {
        MissilePlumePixelCheck.run();
        // The preceding flash scene uses eight chunks; the flight reaches 480 blocks.
        Minecraft.getInstance().options.renderDistance().set(32);
        Minecraft.getInstance().options.broadcastOptions();
        chained = creating = true;
        ticks = 100;
    }

    private static boolean enabled() {
        return chained || Boolean.getBoolean("warnautics.missileFlightCheck");
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!enabled() || finished) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (!creating) {
            if (mc.screen == null || mc.getOverlay() != null || ++ticks < 20) {
                return;
            }
            creating = true;
            System.out.println("MISSILE_FLIGHT_CREATING_WORLD");
            try {
                MissilePlumePixelCheck.run();
            } catch (Throwable failure) {
                finish("GPU check: " + failure);
                return;
            }
            ticks = 0;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(32);
            mc.options.broadcastOptions();
            if (Boolean.getBoolean("warnautics.missileFlightFabulous")) {
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS);
            }
            mc.createWorldOpenFlows()
                    .createFreshLevel(
                            "missile-fx-test",
                            new LevelSettings(
                                    "Missile FX test",
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
            try {
                MissileSmokePixelCheck.run();
            } catch (Throwable failure) {
                finish("Smoke GPU check: " + failure);
                return;
            }
            launched = true;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                server.getPlayerList().setViewDistance(32);
                level.setDayTime(1000);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(0, 100, 0);
                var missile = new CruiseMissileProjectile(ModEntityTypes.CRUISE_MISSILE.get(), level);
                missile.setPos(0, 112, 0);
                missile.launch(new Vec3(0, 1, 0));
                // GPU snapshots can stall the render thread while the integrated server keeps ticking.
                // Extend only this visual fixture's fuel so a real 190-tick cutoff is not a false failure.
                var fixture = missile.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                fixture.putInt("Fuel", 1200);
                missile.load(fixture);
                missileId = missile.getUUID();
                level.addFreshEntity(missile);
            });
            return;
        }
        var missile = missile();
        if (missile == null) {
            if (ticks == 100) {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var entity = server.overworld().getEntity(missileId);
                    var player = server.getPlayerList().getPlayers().getFirst();
                    System.out.println("MISSILE_TRACKING_DIAGNOSTIC entity=" + entity + " view="
                            + server.getPlayerList().getViewDistance() + " player=" + player.position());
                });
            }
            if (++ticks > 400) {
                finish("Missing tracked missile");
            }
            return;
        }
        int phase = phase(missile);
        double distance = DISTANCES[Math.min(phase, DISTANCES.length - 1)];
        Vec3 nozzle = MissileExhaustLights.nozzleOf(missile, 1);
        mc.player.setPos(nozzle.x, nozzle.y - distance - mc.player.getEyeHeight(), nozzle.z);
        mc.player.setOldPosAndRot();
        mc.player.setXRot(-90);
        mc.player.xRotO = -90;
        mc.player.setYRot(0);
        mc.player.yRotO = 0;
        if (phase >= 8) {
            Vec3 eye = nozzle.add(distance * .65, -distance * .55, -distance * .5);
            Vec3 look = nozzle.add(0, -12, 0).subtract(eye);
            mc.player.setPos(eye.x, eye.y - mc.player.getEyeHeight(), eye.z);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
            mc.player.setXRot(
                    (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
            mc.player.setOldPosAndRot();
        }
        if (missile.tickCount > 10 + DISTANCES.length * 20 + 5) {
            if (RESULTS.size() != DISTANCES.length) {
                FAILURES.add("Missing flight snapshots: " + RESULTS.size());
            }
            finish(FAILURES.isEmpty() ? null : String.join("; ", FAILURES));
        }
    }

    private static int phase(CruiseMissileProjectile missile) {
        return Math.max(0, (missile.tickCount - 10) / 20);
    }

    private static CruiseMissileProjectile missile() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || missileId == null) {
            return null;
        }
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CruiseMissileProjectile missile
                    && missile.getUUID().equals(missileId)) {
                return missile;
            }
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void before(RenderLevelStageEvent event) {
        if (!enabled() || finished || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        var missile = missile();
        if (missile == null
                || missile.tickCount < 10
                || (missile.tickCount - 10) % 20 < 2
                || phase(missile) >= DISTANCES.length
                || phase(missile) == lastSample) {
            return;
        }
        try (var frame = capture()) {
            before = frame.getPixelsRGBA();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void after(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || before == null) {
            return;
        }
        var missile = missile();
        if (missile == null) {
            before = null;
            return;
        }
        try (var frame = capture()) {
            var after = frame.getPixelsRGBA();
            int changed = 0;
            for (int i = 0; i < after.length; i++) {
                if ((before[i] & 0xFFFFFF) != (after[i] & 0xFFFFFF)) {
                    changed++;
                }
            }
            lastSample = phase(missile);
            String result = "distance=" + DISTANCES[lastSample] + " age=" + missile.tickCount + " powered="
                    + missile.isPowered() + " pixels=" + changed + " fog=" + RenderSystem.getShaderFogStart() + ","
                    + RenderSystem.getShaderFogEnd();
            RESULTS.add(result);
            System.out.println("MISSILE_FLIGHT_GPU " + result);
            System.out.println("MISSILE_FLIGHT_PROJECTION same="
                    + event.getProjectionMatrix().equals(RenderSystem.getProjectionMatrix(), .0001f));
            frame.flipY();
            frame.writeToFile(Path.of("flight-" + lastSample + ".png"));
            if (changed == 0 || !missile.isPowered()) {
                FAILURES.add(result);
            }
        } catch (Exception failure) {
            finish(failure.toString());
        } finally {
            before = null;
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

    private static void finish(String error) {
        finished = true;
        try {
            Files.writeString(
                    Path.of("missile-flight-check.txt"),
                    (error == null ? "PASS" : "FAIL " + error) + "\n" + String.join("\n", RESULTS));
        } catch (Exception failure) {
            failure.printStackTrace();
        }
        if (error == null) {
            MissileSettingsClientCheck.begin();
        } else {
            Minecraft.getInstance().stop();
        }
    }
}
