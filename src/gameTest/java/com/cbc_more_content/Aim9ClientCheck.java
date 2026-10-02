package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.client.gui.Aim9SettingsScreen;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opens via the real settings key on the tail, drags the slider and verifies server/client persistence. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class Aim9ClientCheck {
    private static final BlockPos POS = new BlockPos(5, 96, 0);
    private static boolean active, waiting, capture;
    private static volatile boolean ready;
    private static int stage, age, frames;
    private static Aim9SettingsScreen screen;
    private static final int[] RANGES = {220, 40, 137};
    private static final boolean[] ENABLED = {true, false, false};
    private static final boolean[] FILTER = {false, true, true};

    static void begin() {
        active = true;
        var mc = Minecraft.getInstance();
        mc.getLanguageManager().setSelected("ru_ru");
        mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(4, 96, 3);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SETTINGS_KEY.get()));
            var block = (Aim9Block) ModBlocks.AIM9.get();
            block.setPlacedBy(
                    level,
                    POS,
                    block.defaultBlockState().setValue(Aim9Block.FACING, Direction.UP),
                    player,
                    ItemStack.EMPTY);
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
            if (++age > 160) {
                throw new AssertionError("AIM-9 settings did not synchronize");
            }
            if (!ready || !(mc.level.getBlockEntity(POS) instanceof Aim9BlockEntity be)) {
                return;
            }
            if (waiting) {
                if (be.enabled() != ENABLED[stage]
                        || be.interceptCruise() != FILTER[stage]
                        || be.range() != RANGES[stage]) {
                    return;
                }
                if (++stage == RANGES.length) {
                    MissilePlumePixelCheck.runAim9();
                    finish(null);
                    return;
                }
                waiting = false;
                screen = null;
            }
            if (screen == null) {
                // Hit the tail, so multi-block body resolution is tested along with client opening.
                var hit = new BlockHitResult(POS.below().getCenter(), Direction.SOUTH, POS.below(), false);
                ModItems.SETTINGS_KEY.get().useOn(new UseOnContext(mc.player, InteractionHand.MAIN_HAND, hit));
                if (!(mc.screen instanceof Aim9SettingsScreen opened)) {
                    throw new AssertionError("Settings key on tail did not open AIM-9 menu");
                }
                screen = opened;
                double left = (screen.width - 300) / 2.0, top = (screen.height - 222) / 2.0;
                if (be.enabled() != ENABLED[stage]) {
                    screen.mouseClicked(left + 190, top + 59, 0);
                }
                if (be.interceptCruise() != FILTER[stage]) {
                    screen.mouseClicked(left + 190, top + 101, 0);
                }
                double knob = left + 14 + 4 + (RANGES[stage] - 40) / 180.0 * 264;
                screen.mouseClicked(left + 60, top + 169, 0);
                screen.mouseDragged(knob, top + 169, 0, knob - left - 60, 0);
                screen.mouseReleased(knob, top + 169, 0);
                age = 0;
            }
            if (age == 10) {
                frames = 0;
                capture = true;
            }
            if (age > 12 && !capture) {
                screen.mouseClicked((screen.width - 300) / 2.0 + 220, (screen.height - 222) / 2.0 + 203, 0);
                if (mc.screen != null) {
                    throw new AssertionError("Apply button did not submit AIM-9 settings");
                }
                waiting = true;
                age = 0;
            }
        } catch (Throwable error) {
            finish(error);
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
                image.writeToFile(Path.of("aim9-settings-" + stage + ".png"));
            }
            capture = false;
        } catch (Throwable error) {
            finish(error);
        }
    }

    private static void finish(Throwable error) {
        active = false;
        try {
            Files.writeString(
                    Path.of("aim9-client-check.txt"),
                    error == null
                            ? "PASS\nSettings key on tail; 40/220/137 range dragging; switches; real packets and synchronized reopening; distinct animated Veil tail and depth occlusion PASS\n"
                            : "FAIL " + error);
        } catch (Exception failure) {
            failure.printStackTrace();
        }
        if (error != null) {
            CBCMoreContent.LOGGER.error("AIM-9 client check failed", error);
        }
        if (error == null) {
            BlastVolumeClientCheck.begin();
        } else {
            Minecraft.getInstance().stop();
        }
    }
}
