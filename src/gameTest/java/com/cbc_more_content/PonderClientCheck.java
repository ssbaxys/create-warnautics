package com.cbc_more_content;

import com.cbc_more_content.client.ponder.WarnauticsPonder;
import com.cbc_more_content.registry.ModItems;
import com.mojang.blaze3d.platform.NativeImage;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.TooltipModifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Actual Ponder UI, all storyboards, both locales, replay and resource reload. Opt-in only. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class PonderClientCheck {
    private static final List<String> RESULTS = new ArrayList<>();
    private static boolean creating, ready, finished, reloading;
    private static int age, guideIndex, step, frames, locale;
    private static PonderUI screen;
    private static String capture;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("warnautics.ponderCheck") || finished || reloading) {
            return;
        }
        try {
            run();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void run() throws Exception {
        var mc = Minecraft.getInstance();
        if (!creating) {
            if (mc.screen == null || mc.getOverlay() != null || ++age < 20) {
                return;
            }
            creating = true;
            age = 0;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(4);
            mc.options.languageCode = "ru_ru";
            mc.getLanguageManager().setSelected("ru_ru");
            mc.createWorldOpenFlows()
                    .createFreshLevel(
                            "ponder-test",
                            new LevelSettings(
                                    "Ponder integration test",
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
        if (!ready) {
            if (++age < 40) {
                return;
            }
            ready = true;
            reload();
            return;
        }
        if (capture != null) {
            return;
        }
        if (screen == null) {
            verifyCatalog();
            screen = PonderUI.of(WarnauticsPonder.location(
                    WarnauticsPonder.guides().get(guideIndex).id()));
            mc.setScreen(screen);
            require(screen.getActiveScene().getKeyframeCount() == 6, "six navigation keyframes");
            require(screen.getActiveScene().getTotalTime() >= 700, "readable scene duration");
            age = 0;
            step = 0;
        }
        if (++age < 7) {
            return;
        }
        var scene = screen.getActiveScene();
        if (step == 6) {
            scene.seekToTime(scene.getTotalTime() + 60);
            require(scene.isFinished(), "scene reaches completion");
            scene.begin();
            scene.seekToTime(scene.getTotalTime() + 60);
            require(scene.isFinished(), "replay reaches completion");
            RESULTS.add((locale == 0 ? "ru_ru " : "en_us ")
                    + WarnauticsPonder.guides().get(guideIndex).id()
                    + ": six rendered steps, completion and replay PASS");
            screen = null;
            guideIndex++;
            if (guideIndex == WarnauticsPonder.guides().size()) {
                if (locale == 0) {
                    locale = 1;
                    guideIndex = 0;
                    mc.options.languageCode = "en_us";
                    mc.getLanguageManager().setSelected("en_us");
                    PonderIndex.reload();
                    reload();
                } else {
                    Files.writeString(Path.of("ponder-check.txt"), "PASS\n" + String.join("\n", RESULTS));
                    finished = true;
                    mc.stop();
                }
            }
            return;
        }
        int target = scene.getKeyframeTime(step) + 35;
        if (target < scene.getCurrentTime()) {
            scene.begin();
        }
        scene.seekToTime(target);
        frames = 0;
        capture = (locale == 0 ? "ru_" : "en_")
                + WarnauticsPonder.guides().get(guideIndex).id() + "_" + step;
        step++;
        age = 0;
    }

    private static void verifyCatalog() {
        var ids = new HashSet<String>();
        for (var guide : WarnauticsPonder.guides()) {
            require(ids.add(guide.id()), "unique guide id");
            require(
                    PonderIndex.getSceneAccess().doScenesExistForId(WarnauticsPonder.location(guide.id())),
                    "registered " + guide.id());
            require(TooltipModifier.REGISTRY.get(guide.item()) != null, "Create tooltip modifier " + guide.id());
            var description = ItemDescription.create(guide.item(), Palette.STANDARD_CREATE);
            require(
                    description != null
                            && description.linesOnShift().size() > 3
                            && description.linesOnCtrl().size() > 3,
                    "Shift and Ctrl content " + guide.id());
            require(I18n.exists("cbc_more_content.ponder." + guide.id() + ".header"), "localized title " + guide.id());
            for (int i = 0; i < 6; i++) {
                require(
                        I18n.exists("cbc_more_content.ponder.shared." + guide.id() + "_" + i),
                        "localized step " + guide.id() + "_" + i);
            }
        }
        require(ids.size() == ModItems.ITEMS.getEntries().size(), "every registered item is covered");
        for (var item : ModItems.ITEMS.getEntries()) {
            require(ids.contains(item.getId().getPath()), "guide exists for " + item.getId());
        }
    }

    private static void reload() {
        reloading = true;
        Minecraft.getInstance().reloadResourcePacks().whenComplete((result, failure) -> {
            if (failure != null) {
                fail(failure);
            }
            reloading = false;
        });
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("warnautics.ponderCheck") || capture == null || finished || ++frames < 3) {
            return;
        }
        try {
            var target = Minecraft.getInstance().getMainRenderTarget();
            Files.createDirectories(Path.of("ponder-previews"));
            try (NativeImage image = new NativeImage(target.width, target.height, false)) {
                target.bindRead();
                image.downloadTexture(0, false);
                image.flipY();
                image.writeToFile(Path.of("ponder-previews", capture + ".png"));
            }
            capture = null;
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void fail(Throwable failure) {
        finished = true;
        CBCMoreContent.LOGGER.error("Ponder integration check failed", failure);
        try {
            Files.writeString(Path.of("ponder-check.txt"), "FAIL " + failure + "\n" + String.join("\n", RESULTS));
        } catch (Exception ignored) {
            // The log retains the original failure if the test directory cannot be written.
        }
        Minecraft.getInstance().stop();
    }
}
