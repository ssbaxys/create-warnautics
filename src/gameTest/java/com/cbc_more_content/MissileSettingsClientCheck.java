package com.cbc_more_content;

import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.client.gui.MissileTargetScreen;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Actual card clicks and confirmation packet, followed by reopening the synchronized settings. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class MissileSettingsClientCheck {
    private static final BlockPos POSITION = new BlockPos(0, 96, 0);
    private static boolean active, waiting, capture;
    private static volatile boolean ready;
    private static int profile, age, completed, frames;
    private static MissileTargetScreen screen;

    static void begin() {
        active = true;
        var mc = Minecraft.getInstance();
        mc.getToasts().clear();
        mc.options.hideGui = false;
        mc.options.guiScale().set(2);
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(0, 96, 3);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SETTINGS_KEY.get()));
            var block = (CruiseMissileBlock) ModBlocks.CRUISE_MISSILE.get();
            var state = block.defaultBlockState().setValue(CruiseMissileBlock.FACING, Direction.UP);
            block.setPlacedBy(level, POSITION, state, player, ItemStack.EMPTY);
            ((CruiseMissileBlockEntity) level.getBlockEntity(POSITION)).armRemote();
            ready = true;
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        try {
            var mc = Minecraft.getInstance();
            if (++age > 180) {
                throw new IllegalStateException("Settings did not synchronize");
            }
            if (!ready || !(mc.level.getBlockEntity(POSITION) instanceof CruiseMissileBlockEntity missile)) {
                return;
            }
            if (waiting) {
                if (missile.flightProfile().id() != profile
                        || missile.guidance() != CruiseMissileBlockEntity.Guidance.REMOTE) {
                    return;
                }
                if (++completed == 3) {
                    finish(null);
                    return;
                }
                waiting = false;
                screen = null;
            }
            if (screen == null) {
                screen = new MissileTargetScreen(
                        POSITION, missile.target(), 1, missile.flightProfile().id());
                mc.setScreen(screen);
                profile = (completed + 1) % 3;
                if (!screen.mouseClicked(
                        (screen.width - 252) / 2.0 + 9 + profile * 80 + 35, (screen.height - 204) / 2.0 + 70, 0)) {
                    throw new IllegalStateException("Trajectory card did not accept the click");
                }
                age = 0;
            }
            if (age == 12) {
                frames = 0;
                capture = true;
            }
            if (age >= 14 && !capture) {
                screen.mouseClicked((screen.width - 252) / 2.0 + 126, (screen.height - 204) / 2.0 + 176, 0);
                if (mc.screen != null) {
                    throw new IllegalStateException("Confirm did not submit settings");
                }
                waiting = true;
                age = 0;
            }
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!active || !capture || ++frames < 3) {
            return;
        }
        try {
            var target = Minecraft.getInstance().getMainRenderTarget();
            try (NativeImage image = new NativeImage(target.width, target.height, false)) {
                target.bindRead();
                image.downloadTexture(0, false);
                image.flipY();
                image.writeToFile(Path.of("missile-settings-" + profile + ".png"));
            }
            capture = false;
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private static void finish(Throwable failure) {
        active = false;
        try {
            Files.writeString(
                    Path.of("missile-settings-check.txt"),
                    failure == null
                            ? "PASS\nThree trajectory cards rendered; clicks, confirmation packets and synchronized reopening PASS\n"
                            : "FAIL " + failure);
        } catch (Exception error) {
            error.printStackTrace();
        }
        if (failure != null) {
            CBCMoreContent.LOGGER.error("Missile settings check failed", failure);
        }
        Minecraft.getInstance().stop();
    }
}
