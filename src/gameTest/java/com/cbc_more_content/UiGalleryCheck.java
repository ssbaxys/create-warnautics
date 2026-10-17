package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.client.gui.Aim9SettingsScreen;
import com.cbc_more_content.client.gui.BombSettingsScreen;
import com.cbc_more_content.client.gui.C4CodeScreen;
import com.cbc_more_content.client.gui.C4ModeScreen;
import com.cbc_more_content.client.gui.C4SettingsScreen;
import com.cbc_more_content.client.gui.C4WireScreen;
import com.cbc_more_content.client.gui.ControlPanelScreen;
import com.cbc_more_content.client.gui.MissileTargetScreen;
import com.cbc_more_content.client.gui.RadarSettingsScreen;
import com.cbc_more_content.client.gui.SirenSettingsScreen;
import com.cbc_more_content.radar.InterceptSettings;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.siren.SirenSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
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
import org.lwjgl.glfw.GLFW;

/**
 * Opens every Warnautics screen in a real client, in both shipped languages, and saves a
 * screenshot of each, so the whole interface can be reviewed side by side.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class UiGalleryCheck {
    private static final BlockPos C4 = new BlockPos(0, -60, 4);
    private static final BlockPos SIREN = new BlockPos(2, -60, 4);
    private static final BlockPos AIM9 = new BlockPos(-2, -59, 4);
    private static final BlockPos MISSILE = new BlockPos(-4, -59, 4);
    private static final BlockPos BOMB = new BlockPos(4, -60, 4);
    private static final String[] LANGUAGES = {"ru_ru", "en_us"};

    private static final Runnable NOOP = () -> {};

    private record Shot(String name, Supplier<Screen> screen, Runnable before) {
        Shot(String name, Supplier<Screen> screen) {
            this(name, screen, NOOP);
        }
    }

    /** A bare screen that only shows item tooltips, side by side. */
    private static final class TooltipBoard extends Screen {
        TooltipBoard() {
            super(net.minecraft.network.chat.Component.empty());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            var stacks = List.of(
                    new ItemStack(com.cbc_more_content.registry.ModItems.SETTINGS_KEY.get()),
                    new ItemStack(com.cbc_more_content.registry.ModItems.DETONATOR.get()),
                    new ItemStack(ModBlocks.C4.get()));
            int x = 8;
            for (var stack : stacks) {
                g.renderTooltip(this.font, stack, x, 30);
                x += this.width / 3;
            }
        }
    }

    private static void notices() {
        com.cbc_more_content.ui.Notices.show(new com.cbc_more_content.network.NoticePayload(
                com.cbc_more_content.ui.UiTone.ACCENT,
                net.minecraft.network.chat.Component.translatable("message.cbc_more_content.key.bind_mode")));
        com.cbc_more_content.ui.Notices.show(new com.cbc_more_content.network.NoticePayload(
                com.cbc_more_content.ui.UiTone.DANGER,
                net.minecraft.network.chat.Component.translatable("message.cbc_more_content.detonator.out_of_range")));
        com.cbc_more_content.ui.Notices.show(new com.cbc_more_content.network.NoticePayload(
                com.cbc_more_content.ui.UiTone.SUCCESS,
                net.minecraft.network.chat.Component.translatable("message.cbc_more_content.detonator.paired", 3)));
    }

    private static final List<Shot> SHOTS = List.of(
            new Shot("bomb", () -> new BombSettingsScreen(BOMB, 30, 4)),
            new Shot("c4-code", () -> new C4CodeScreen(C4, false)),
            new Shot("c4-mode", () -> new C4ModeScreen(C4)),
            new Shot("c4-timer", () -> new C4SettingsScreen(C4, 20)),
            new Shot("c4-wires", () -> new C4WireScreen(C4)),
            new Shot("panel", () -> new ControlPanelScreen(true)),
            new Shot("radar", () -> new RadarSettingsScreen(BOMB, new InterceptSettings(0.6f, 160, false))),
            new Shot("siren", () -> new SirenSettingsScreen(SIREN, SirenSettings.DEFAULT)),
            new Shot("aim9", () -> new Aim9SettingsScreen(AIM9, true, true, 140)),
            new Shot("missile", () -> new MissileTargetScreen(MISSILE, new BlockPos(120, 64, -300), 0, 1)),
            new Shot("missile-remote", () -> new MissileTargetScreen(MISSILE, null, 1, 0)),
            new Shot("tooltips", TooltipBoard::new),
            new Shot("notices", () -> null, UiGalleryCheck::notices),
            new Shot("models", () -> null, UiGalleryCheck::models));

    /** Lays both airframes on their sides and hands the player the items, for a look at the models. */
    private static void models() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            var missile = (CruiseMissileBlock) ModBlocks.CRUISE_MISSILE.get();
            missile.setPlacedBy(
                    level,
                    new BlockPos(-3, -59, 12),
                    missile.defaultBlockState().setValue(CruiseMissileBlock.FACING, Direction.EAST),
                    player,
                    ItemStack.EMPTY);
            var aim9 = (Aim9Block) ModBlocks.AIM9.get();
            aim9.setPlacedBy(
                    level,
                    new BlockPos(-3, -59, 16),
                    aim9.defaultBlockState().setValue(Aim9Block.FACING, Direction.EAST),
                    player,
                    ItemStack.EMPTY);
            // Gold to the east of both: each nose must point at it.
            level.setBlockAndUpdate(
                    new BlockPos(1, -60, 12), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(
                    new BlockPos(1, -60, 16), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
            player.getInventory().setItem(0, new ItemStack(ModBlocks.CRUISE_MISSILE.get()));
            player.getInventory().setItem(1, new ItemStack(ModBlocks.AIM9.get()));
            player.getInventory().selected = 1;
            player.connection.teleport(-2.5, -50, 14.5, 0, 90);
        });
    }

    private static boolean creating, built, capture, finished;
    private static int ticks, index, language, frames;
    private static final List<String> SAVED = new ArrayList<>();

    private UiGalleryCheck() {}

    private static boolean enabled() {
        return Boolean.getBoolean("warnautics.uiGallery") && !finished;
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
            mc.options.guiScale().set(2);
            mc.getToasts().clear();
            mc.createWorldOpenFlows()
                    .createFreshLevel(
                            "ui-gallery",
                            new LevelSettings(
                                    "UI gallery",
                                    GameType.CREATIVE,
                                    false,
                                    Difficulty.PEACEFUL,
                                    true,
                                    new GameRules(),
                                    WorldDataConfiguration.DEFAULT),
                            new WorldOptions(91, false, false),
                            registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.FLAT)
                                    .createWorldDimensions(),
                            mc.screen);
            return;
        }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) {
            return;
        }
        var server = mc.getSingleplayerServer();
        if (!built) {
            mc.setScreen(null);
            if (++ticks == 40) {
                server.execute(() -> {
                    var level = server.overworld();
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000);
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(0.5, -60, -2.5);
                    level.setBlockAndUpdate(C4, ModBlocks.C4.get().defaultBlockState());
                    level.setBlockAndUpdate(SIREN, ModBlocks.SIREN.get().defaultBlockState());
                    level.setBlockAndUpdate(BOMB, ModBlocks.SMALL_BOMB.get().defaultBlockState());
                    var aim9 = (Aim9Block) ModBlocks.AIM9.get();
                    aim9.setPlacedBy(
                            level,
                            AIM9,
                            aim9.defaultBlockState().setValue(Aim9Block.FACING, Direction.UP),
                            player,
                            ItemStack.EMPTY);
                    var missile = (CruiseMissileBlock) ModBlocks.CRUISE_MISSILE.get();
                    missile.setPlacedBy(
                            level,
                            MISSILE,
                            missile.defaultBlockState().setValue(CruiseMissileBlock.FACING, Direction.UP),
                            player,
                            ItemStack.EMPTY);
                });
            }
            if (ticks >= 80) {
                built = true;
                ticks = 0;
                selectLanguage(mc);
            }
            return;
        }
        if (capture) {
            return;
        }
        if (index >= SHOTS.size()) {
            if (++language >= LANGUAGES.length) {
                finish(null);
                return;
            }
            index = 0;
            selectLanguage(mc);
            return;
        }
        ticks++;
        if (SHOTS.get(index).name().equals("models") && ticks > 4) {
            mc.player.setYRot(0);
            mc.player.yRotO = 0;
            mc.player.setXRot(90);
            mc.player.xRotO = 90;
            mc.player.getInventory().selected = 1;
        }
        if (ticks == 1) {
            SHOTS.get(index).before().run();
            mc.setScreen(SHOTS.get(index).screen().get());
            // Park the cursor off the panel so hover states do not depend on the window.
            GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 4, 4);
        } else if (ticks == 16) {
            if (mc.screen == null && SHOTS.get(index).before() == NOOP) {
                throw new IllegalStateException(SHOTS.get(index).name() + " closed itself");
            }
            frames = 0;
            capture = true;
        }
    }

    private static void selectLanguage(Minecraft mc) {
        mc.getLanguageManager().setSelected(LANGUAGES[language]);
        mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!enabled() || !capture || ++frames < 3) {
            return;
        }
        try {
            var mc = Minecraft.getInstance();
            String name = "ui-" + SHOTS.get(index).name() + "-" + LANGUAGES[language] + ".png";
            Path directory = mc.gameDirectory.toPath().resolve("ui-gallery");
            Files.createDirectories(directory);
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(directory.resolve(name));
            }
            SAVED.add(name);
            capture = false;
            ticks = 0;
            index++;
            mc.setScreen(null);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private static void finish(Throwable failure) {
        finished = true;
        String text = failure == null ? "PASS\n" + String.join("\n", SAVED) + "\n" : "FAIL " + failure + "\n";
        if (failure != null) {
            CBCMoreContent.LOGGER.error("UI gallery failed", failure);
        }
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("ui-gallery.txt"), text);
        } catch (Exception exception) {
            CBCMoreContent.LOGGER.error("Cannot write UI gallery report", exception);
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
