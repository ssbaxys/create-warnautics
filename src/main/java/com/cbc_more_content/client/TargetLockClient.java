package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.network.MissileLockPayload;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.registry.ModSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Painting a target with the designator: hold the attack key on something and the lock
 * fills, then goes to the server for it to resolve.
 * <p>
 * The bar and the crosshair ring are all the feedback there is, so the lock has to be
 * held steadily on one block — looking away resets it.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class TargetLockClient {
    /** Ticks of steady aim needed for a lock. */
    private static final int LOCK_TICKS = 30;
    /** How long the confirmation stays on screen. */
    private static final int CONFIRM_TICKS = 30;

    private static int progress;
    private static BlockPos painted;
    private static int confirm;

    private TargetLockClient() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (confirm > 0) {
            confirm--;
        }
        if (player == null || mc.level == null) {
            reset();
            return;
        }

        ItemStack stack = player.getMainHandItem();
        BlockPos missile = stack.getItem() instanceof TargetDesignatorItem
                ? TargetDesignatorItem.boundMissile(stack)
                : null;
        if (missile == null || !mc.options.keyAttack.isDown() || mc.screen != null) {
            reset();
            return;
        }

        if (!(mc.hitResult instanceof BlockHitResult hit)
                || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            reset();
            return;
        }

        // Drifting onto a different block starts the lock over: this is a steady hold,
        // not a total of however long the key happened to be down.
        if (painted == null || !painted.equals(hit.getBlockPos())) {
            painted = hit.getBlockPos();
            progress = 0;
        }

        progress++;
        if (progress % 6 == 0 && progress < LOCK_TICKS) {
            player.playSound(ModSounds.C4_BUTTON.get(), 0.35f,
                    0.9f + (progress / (float) LOCK_TICKS) * 0.6f);
        }
        if (progress >= LOCK_TICKS) {
            PacketDistributor.sendToServer(new MissileLockPayload(missile, painted));
            player.playSound(ModSounds.C4_BUTTON.get(), 0.9f, 1.6f);
            confirm = CONFIRM_TICKS;
            reset();
        }
    }

    private static void reset() {
        progress = 0;
        painted = null;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int cx = graphics.guiWidth() / 2;
        int cy = graphics.guiHeight() / 2;

        if (confirm > 0) {
            int alpha = (int) (Mth.clamp(confirm / (float) CONFIRM_TICKS, 0.0f, 1.0f) * 255.0f);
            graphics.drawCenteredString(mc.font,
                    Component.translatable("gui.cbc_more_content.missile.locked"),
                    cx, cy + 22, (alpha << 24) | 0x6FD46F);
            return;
        }
        if (progress <= 0) {
            return;
        }

        // A bracket that closes on the crosshair as the lock fills.
        float t = Mth.clamp(progress / (float) LOCK_TICKS, 0.0f, 1.0f);
        int reach = Math.round(Mth.lerp(t, 18.0f, 7.0f));
        int colour = 0xC0000000 | (t >= 1.0f ? 0x6FD46F : 0xFFB036);
        for (int[] corner : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
            int x = cx + corner[0] * reach;
            int y = cy + corner[1] * reach;
            graphics.fill(x - (corner[0] > 0 ? 4 : 0), y, x + (corner[0] > 0 ? 0 : 4), y + 1, colour);
            graphics.fill(x, y - (corner[1] > 0 ? 4 : 0), x + 1, y + (corner[1] > 0 ? 0 : 4), colour);
        }

        int width = 40;
        graphics.fill(cx - width / 2, cy + 26, cx + width / 2, cy + 28, 0xA012140F);
        graphics.fill(cx - width / 2, cy + 26, cx - width / 2 + Math.round(width * t), cy + 28, colour);
    }
}
