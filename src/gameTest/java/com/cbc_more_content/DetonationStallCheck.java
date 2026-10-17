package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import java.nio.file.Files;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Measures the hitch a real detonation causes in a real client: the longest frames and the
 * total time lost to long frames in the five seconds after each charge, plus the server time
 * spent inside the detonation itself.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class DetonationStallCheck {
    private record Charge(String name, BombSize size, float block, float entity, boolean lights, boolean shadows) {}

    private static final Charge SMALL = new Charge("small", BombSize.SMALL, 5.2f, 6.5f, true, false);
    private static final Charge LARGE = new Charge("large", BombSize.LARGE, 12f, 16.5f, true, false);
    private static final Charge CRUISE = new Charge(
            "cruise",
            BombSize.MOAB,
            BombSize.MOAB.blockBlastPower * .85f,
            BombSize.MOAB.entityBlastPower * .85f,
            true,
            false);
    private static final Charge MOAB = new Charge("moab", BombSize.MOAB, 33f, 56f, true, false);

    /** First use, steady state, then the same charges with Veil bomb lights switched off. */
    private static final List<Charge> CHARGES = List.of(
            SMALL,
            SMALL,
            LARGE,
            CRUISE,
            MOAB,
            new Charge("large-shadows", BombSize.LARGE, 12f, 16.5f, true, true),
            new Charge("cruise-shadows", CRUISE.size(), CRUISE.block(), CRUISE.entity(), true, true));

    /** Each charge gets its own untouched ground, this far apart along +Z. */
    private static final int SPACING = 140;

    private static final int WINDOW_TICKS = 100;
    private static final int TOP = 80;

    private static boolean creating, built, finished;
    private static int ticks, index, windowAge = -1;
    private static long lastFrame;
    private static double lastFrameMillis;
    private static double maxFrame, lostMillis, baselineMax, spotBaseline;
    private static int longFrames;
    private static volatile double serverMillis;
    private static final List<String> RESULTS = new ArrayList<>();

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("warnautics.stallCheck") || finished) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0) {
            double millis = (now - lastFrame) / 1e6;
            lastFrameMillis = millis;
            if (windowAge >= 0) {
                maxFrame = Math.max(maxFrame, millis);
                if (millis > 50) {
                    longFrames++;
                    lostMillis += millis - 16.7;
                }
            } else if (built) {
                baselineMax = Math.max(baselineMax, millis);
            }
        }
        lastFrame = now;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("warnautics.stallCheck") || finished) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (!creating) {
            if (mc.screen == null || mc.getOverlay() != null || ++ticks < 20) {
                return;
            }
            creating = true;
            ticks = 0;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(10);
            mc.createWorldOpenFlows()
                    .createFreshLevel(
                            "stall-test",
                            new LevelSettings(
                                    "Stall test",
                                    GameType.CREATIVE,
                                    false,
                                    Difficulty.PEACEFUL,
                                    true,
                                    new GameRules(),
                                    WorldDataConfiguration.DEFAULT),
                            new WorldOptions(77, false, false),
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
            if (++ticks == 60) {
                server.execute(() -> {
                    var level = server.overworld();
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000);
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true;
                });
            }
            if (ticks >= 100) {
                built = true;
                ticks = 0;
            }
            return;
        }
        mc.player.setYRot(0);
        mc.player.yRotO = 0;
        mc.player.setXRot(18);
        mc.player.xRotO = 18;
        if (windowAge < 0) {
            if (index >= CHARGES.size()) {
                finish();
                return;
            }
            Charge charge = CHARGES.get(index);
            int spot = index * SPACING;
            if (++ticks == 1) {
                // Fly to fresh ground, lay it, and let it load and mesh before measuring.
                com.cbc_more_content.config.WarnauticsClientConfig.BOMB_LIGHTS.set(charge.lights());
                com.cbc_more_content.config.WarnauticsClientConfig.BOMB_LIGHT_SHADOWS.set(charge.shadows());
                server.execute(() -> {
                    var level = server.overworld();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(0, TOP + 25, spot - 60);
                    for (var p : BlockPos.betweenClosed(-60, TOP - 12, spot - 60, 60, TOP, spot + 60)) {
                        var material =
                                p.getY() == TOP ? Blocks.GRASS_BLOCK : p.getY() < TOP - 3 ? Blocks.STONE : Blocks.DIRT;
                        level.setBlock(p, material.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                });
                spotBaseline = 0;
                return;
            }
            if (ticks < 220) {
                if (ticks > 180) {
                    spotBaseline = Math.max(spotBaseline, lastFrameMillis);
                }
                return;
            }
            Vec3 at = new Vec3(0, TOP + .8, spot);
            maxFrame = lostMillis = 0;
            longFrames = 0;
            windowAge = 0;
            server.execute(() -> {
                long start = System.nanoTime();
                var level = server.overworld();
                BombExplosionHandler.detonate(
                        level,
                        null,
                        BombDamageSource.create(level),
                        at,
                        charge.block(),
                        charge.entity(),
                        charge.size());
                serverMillis = (System.nanoTime() - start) / 1e6;
            });
            return;
        }
        if (++windowAge >= WINDOW_TICKS) {
            Charge charge = CHARGES.get(index);
            RESULTS.add(String.format(
                    "%s: server=%.0fms maxFrame=%.0fms longFrames=%d lost=%.0fms (quiet frame before %.0fms)",
                    charge.name(), serverMillis, maxFrame, longFrames, lostMillis, spotBaseline));
            index++;
            windowAge = -1;
            ticks = 0;
        }
    }

    private static void finish() {
        finished = true;
        var text = "DONE baselineMaxFrame=" + String.format("%.0fms", baselineMax) + "\n" + String.join("\n", RESULTS)
                + "\n";
        CBCMoreContent.LOGGER.info("Detonation stall check:\n{}", text);
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("stall-check.txt"), text);
        } catch (Exception exception) {
            CBCMoreContent.LOGGER.error("Cannot write stall report", exception);
        }
        Minecraft.getInstance().stop();
    }
}
