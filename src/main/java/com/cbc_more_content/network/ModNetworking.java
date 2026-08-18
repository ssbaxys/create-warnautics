package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.item.BombSettingsKeyItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ModNetworking {
    /** How far a player may be from the bomb they are configuring. */
    private static final double REACH_SQR = 8.0D * 8.0D;

    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        BombFlashPayload.TYPE,
                        BombFlashPayload.STREAM_CODEC,
                        ModNetworking::handleClient)
                .playToClient(
                        ConcussionPayload.TYPE,
                        ConcussionPayload.STREAM_CODEC,
                        ModNetworking::handleConcussion)
                .playToServer(
                        ConfigureBombPayload.TYPE,
                        ConfigureBombPayload.STREAM_CODEC,
                        ModNetworking::handleConfigureBomb);
    }

    private static void handleConcussion(ConcussionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                Class.forName("com.cbc_more_content.client.ConcussionClient")
                        .getMethod("handle", ConcussionPayload.class)
                        .invoke(null, payload);
            } catch (ReflectiveOperationException e) {
                CBCMoreContent.LOGGER.debug("Concussion client handle failed: {}", e.toString());
            }
        });
    }

    private static void handleClient(BombFlashPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                Class.forName("com.cbc_more_content.client.BombFlashClient")
                        .getMethod("handle", BombFlashPayload.class)
                        .invoke(null, payload);
            } catch (ReflectiveOperationException e) {
                CBCMoreContent.LOGGER.debug("Bomb flash client handle failed: {}", e.toString());
            }
        });
    }

    /**
     * The dial is a client screen, so everything it reports has to be re-checked here:
     * the sender must be near a small rack bomb, and the tick value is normalized
     * rather than trusted.
     */
    private static void handleConfigureBomb(ConfigureBombPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            BlockPos pos = payload.pos();
            if (!level.isLoaded(pos) || player.distanceToSqr(
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH_SQR) {
                return;
            }
            if (!(level.getBlockState(pos).getBlock() instanceof DropBombBlock bomb)
                    || !bomb.allowsCassette()) {
                return;
            }
            if (!holdsSettingsKey(player)) {
                return;
            }
            DropBombBlock.applyReleaseDelay(level, pos, payload.delayTicks());
        });
    }

    private static boolean holdsSettingsKey(Player player) {
        return player.getMainHandItem().getItem() instanceof BombSettingsKeyItem
                || player.getOffhandItem().getItem() instanceof BombSettingsKeyItem;
    }
}
