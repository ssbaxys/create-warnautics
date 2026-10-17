package com.cbc_more_content;

import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.munitions.MissileFlightProfile;
import com.cbc_more_content.registry.ModEntityTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Flies a cruise missile off a rail and an interceptor at a crossing target in a real
 * client, photographs the stages of each flight, and measures how smoothly the airframe
 * actually moves on screen: per rendered frame, how far it moved, how many frames it
 * stood still and how many it jumped.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class FlightFxCheck {
    private static final int TOP = -60;
    private static final int[] CRUISE_SHOTS = {17, 30, 52, 62, 80, 100};
    private static final int[] AIM9_SHOTS = {10, 30, 50};

    private static boolean creating, built, finished;
    private static int ticks, stage, flightTicks;
    private static String capture;
    private static int frames;
    private static Entity watched;
    private static long lastFrame;
    private static Vec3 lastRendered;
    private static final List<double[]> SAMPLES = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();

    private FlightFxCheck() {}

    private static boolean enabled() {
        return Boolean.getBoolean("warnautics.flightFx") && !finished;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!enabled()) {
            return;
        }
        try {
            run();
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private static void run() {
        var mc = Minecraft.getInstance();
        if (!creating) {
            if (mc.screen == null || mc.getOverlay() != null || ++ticks < 20) {
                return;
            }
            creating = true;
            ticks = 0;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(8);
            mc.options.guiScale().set(2);
            mc.createWorldOpenFlows()
                    .createFreshLevel(
                            "flight-fx",
                            new LevelSettings(
                                    "Flight FX",
                                    GameType.CREATIVE,
                                    false,
                                    Difficulty.PEACEFUL,
                                    true,
                                    new GameRules(),
                                    WorldDataConfiguration.DEFAULT),
                            new WorldOptions(33, false, false),
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
        var server = mc.getSingleplayerServer();
        if (!built) {
            if (++ticks == 30) {
                server.execute(() -> {
                    var level = server.overworld();
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000);
                    server.getPlayerList().getPlayers().getFirst().getAbilities().flying = true;
                });
            }
            if (ticks >= 80) {
                built = true;
                ticks = 0;
            }
            return;
        }
        if (capture != null) {
            return;
        }
        flightTicks++;
        if (stage == 0 || stage == 1) {
            if (flightTicks == 1) {
                com.cbc_more_content.munitions.ClientFlightSmoother.enabled = stage == 0;
                com.cbc_more_content.config.WarnauticsClientConfig.PLASMA_PLUME.set(stage == 1);
                int lane = stage * 60;
                server.execute(() -> {
                    var level = server.overworld();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(-22, TOP + 6, lane);
                    var missile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
                    missile.setPos(0.5, TOP + 1.5, lane + 0.5);
                    missile.setFlightProfile(MissileFlightProfile.DIRECT);
                    missile.setGuidance(
                            CruiseMissileBlockEntity.Guidance.COORDINATES, new BlockPos(0, TOP, lane + 900), -1);
                    missile.ejectUpward();
                    level.addFreshEntity(missile);
                });
            }
            follow(mc, CruiseMissileProjectile.class);
            chase(mc);
            if (stage == 1) {
                shoot(CRUISE_SHOTS, "plasma");
            }
            if (stage == 0) {
                shoot(CRUISE_SHOTS, "cruise");
            }
            if (flightTicks >= 110) {
                summarise(stage == 0 ? "cruise" : "cruise-old-snapping");
                com.cbc_more_content.config.WarnauticsClientConfig.PLASMA_PLUME.set(false);
                stage++;
                flightTicks = 0;
                watched = null;
            }
            return;
        }
        if (stage == 4) {
            mineStage(mc);
            return;
        }
        if (stage == 2 || stage == 3) {
            if (flightTicks == 1) {
                com.cbc_more_content.munitions.ClientFlightSmoother.enabled = stage == 2;
                int lane = 2000 + (stage - 2) * 400;
                server.execute(() -> {
                    var level = server.overworld();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(-30, TOP + 10, lane);
                    var target = ModEntityTypes.CRUISE_MISSILE.get().create(level);
                    target.setPos(-80, TOP + 40, lane + 60);
                    target.launch(new Vec3(1, 0, 0));
                    level.addFreshEntity(target);
                    var interceptor = ModEntityTypes.AIM9.get().create(level);
                    interceptor.setPos(0.5, TOP + 2, lane + 0.5);
                    interceptor.launch(target, Vec3.ZERO);
                    level.addFreshEntity(interceptor);
                });
            }
            follow(mc, Aim9Projectile.class);
            if (stage == 2) {
                shoot(AIM9_SHOTS, "aim9");
            }
            if (flightTicks >= 70) {
                summarise(stage == 2 ? "aim9" : "aim9-old-snapping");
                if (stage == 3) {
                    com.cbc_more_content.munitions.ClientFlightSmoother.enabled = true;
                    stage = 4;
                    flightTicks = 0;
                    watched = null;
                } else {
                    stage++;
                    flightTicks = 0;
                    watched = null;
                }
            }
        }
    }

    /** An anti-tank mine in the ground, set off by a cart rolling onto it. */
    private static void mineStage(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (flightTicks == 1) {
            server.execute(() -> {
                var level = server.overworld();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.connection.teleport(-16.5, TOP + 3, 3000.5, -90, 12);
                level.setBlockAndUpdate(
                        new BlockPos(0, TOP, 3000),
                        com.cbc_more_content.registry.ModBlocks.LARGE_MINE.get().defaultBlockState());
            });
        }
        if (flightTicks > 2) {
            mc.player.setYRot(-90);
            mc.player.yRotO = -90;
            mc.player.setXRot(12);
            mc.player.xRotO = 12;
        }
        if (flightTicks == 40) {
            server.execute(() -> {
                var level = server.overworld();
                var cart = new net.minecraft.world.entity.vehicle.Minecart(level, .5, TOP + .2, 3000.5);
                level.addFreshEntity(cart);
            });
        }
        for (int at : new int[] {44, 47, 52, 60, 75}) {
            if (flightTicks == at) {
                capture = "mine-" + (at - 40);
                frames = 0;
            }
        }
        if (flightTicks >= 90) {
            finish(null);
        }
    }

    /** Keeps the camera on the newest missile of the type, from where the player stands. */
    private static void follow(Minecraft mc, Class<? extends Entity> type) {
        if (watched == null || watched.isRemoved() || !type.isInstance(watched)) {
            watched = null;
            for (var entity : mc.level.entitiesForRendering()) {
                if (type.isInstance(entity) && !entity.isRemoved()) {
                    watched = entity;
                }
            }
            lastRendered = null;
        }
        if (watched == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 to = watched.position().subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(to.z, to.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(Mth.clamp(pitch, -89, 89));
        mc.player.xRotO = mc.player.getXRot();
    }

    /** Flies the camera alongside the missile, behind and to one side, looking at it. */
    private static void chase(Minecraft mc) {
        if (watched == null || flightTicks < 12) {
            return;
        }
        if (flightTicks > 66) {
            // Stop following: the missile flies off, for a look at it from far away.
            follow(mc, CruiseMissileProjectile.class);
            return;
        }
        Vec3 velocity = watched.getDeltaMovement();
        Vec3 forward = velocity.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : velocity.normalize();
        Vec3 side = forward.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 eye =
                watched.position().subtract(forward.scale(7)).add(side.scale(6)).add(0, 2, 0);
        mc.player.setPos(eye.x, eye.y - mc.player.getEyeHeight(), eye.z);
        mc.player.setDeltaMovement(velocity);
        follow(mc, CruiseMissileProjectile.class);
    }

    private static void shoot(int[] at, String name) {
        for (int tick : at) {
            if (flightTicks == tick) {
                capture = name + "-" + tick;
                frames = 0;
            }
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!enabled()) {
            return;
        }
        var mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (watched != null && !watched.isRemoved() && mc.level != null) {
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 rendered = new Vec3(
                    Mth.lerp(partial, watched.xOld, watched.getX()),
                    Mth.lerp(partial, watched.yOld, watched.getY()),
                    Mth.lerp(partial, watched.zOld, watched.getZ()));
            if (lastRendered != null && lastFrame != 0) {
                double seconds = (now - lastFrame) / 1e9;
                SAMPLES.add(new double[] {rendered.distanceTo(lastRendered), seconds});
            }
            lastRendered = rendered;
        }
        lastFrame = now;
        if (capture == null || ++frames < 3) {
            return;
        }
        try {
            Path directory = mc.gameDirectory.toPath().resolve("flight-fx");
            Files.createDirectories(directory);
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(directory.resolve(capture + ".png"));
            }
            capture = null;
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    /**
     * Per-frame speed on screen against the speed of the frames around it, so the slow
     * launch and the acceleration do not count as stutter: the mean deviation from that
     * local pace, and how many frames froze (under a third of it) or leapt (over double).
     */
    private static void summarise(String name) {
        List<Double> speeds = new ArrayList<>();
        for (double[] sample : SAMPLES) {
            if (sample[1] > 0) {
                speeds.add(sample[0] / sample[1]);
            }
        }
        int still = 0, leaps = 0, counted = 0;
        double deviation = 0;
        for (int i = 3; i < speeds.size() - 3; i++) {
            List<Double> window = new ArrayList<>(speeds.subList(i - 3, i + 4));
            window.sort(Double::compare);
            double local = window.get(3);
            if (local < 1) {
                continue;
            }
            double ratio = speeds.get(i) / local;
            deviation += Math.abs(ratio - 1);
            counted++;
            if (ratio < .33) {
                still++;
            }
            if (ratio > 2) {
                leaps++;
            }
        }
        REPORT.add(String.format(
                "%s: frames=%d meanDeviation=%.0f%% frozenFrames=%d leapFrames=%d",
                name, counted, counted == 0 ? 0 : deviation / counted * 100, still, leaps));
        SAMPLES.clear();
    }

    private static void finish(Throwable failure) {
        finished = true;
        String text = failure == null ? "PASS\n" + String.join("\n", REPORT) + "\n" : "FAIL " + failure + "\n";
        if (failure != null) {
            CBCMoreContent.LOGGER.error("Flight FX check failed", failure);
        }
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("flight-fx.txt"), text);
        } catch (Exception exception) {
            CBCMoreContent.LOGGER.error("Cannot write flight FX report", exception);
        }
        // The report is written. Stop normally, but do not let a hung world save leave a
        // black window behind: give it twenty seconds, then end the process outright.
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(20_000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    Runtime.getRuntime().halt(0);
                },
                "warnautics-check-exit");
        watchdog.setDaemon(true);
        watchdog.start();
        Minecraft.getInstance().stop();
    }
}
