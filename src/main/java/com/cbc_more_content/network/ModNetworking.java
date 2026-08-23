package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.item.BombSettingsKeyItem;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.item.WireCuttersItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
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
                .playToClient(
                        C4CodeResultPayload.TYPE,
                        C4CodeResultPayload.STREAM_CODEC,
                        ModNetworking::handleCodeResult)
                .playToClient(
                        C4WireResultPayload.TYPE,
                        C4WireResultPayload.STREAM_CODEC,
                        ModNetworking::handleWireResult)
                .playToServer(
                        MissileLockPayload.TYPE,
                        MissileLockPayload.STREAM_CODEC,
                        ModNetworking::handleMissileLock)
                .playToServer(
                        MissileTargetPayload.TYPE,
                        MissileTargetPayload.STREAM_CODEC,
                        ModNetworking::handleMissileTarget)
                .playToServer(
                        C4WirePayload.TYPE,
                        C4WirePayload.STREAM_CODEC,
                        ModNetworking::handleWireCut)
                .playToServer(
                        C4CodePayload.TYPE,
                        C4CodePayload.STREAM_CODEC,
                        ModNetworking::handleC4Code)
                .playToServer(
                        ConfigureC4Payload.TYPE,
                        ConfigureC4Payload.STREAM_CODEC,
                        ModNetworking::handleConfigureC4)
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

    /** Same validation as the bomb dial: proximity, block identity and the key. */
    private static void handleConfigureC4(ConfigureC4Payload payload, IPayloadContext context) {
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
            if (!holdsSettingsKey(player)) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof C4BlockEntity c4) || c4.isArmed()) {
                // A live fuse is not adjustable — that is the whole point of the timer.
                return;
            }
            c4.setFuseSeconds(payload.seconds());
            if (payload.arm()) {
                // arm() plays the charge going live itself.
                c4.arm();
            }
        });
    }

    private static void handleCodeResult(C4CodeResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                Class.forName("com.cbc_more_content.client.gui.C4CodeClient")
                        .getMethod("handle", C4CodeResultPayload.class)
                        .invoke(null, payload);
            } catch (ReflectiveOperationException e) {
                CBCMoreContent.LOGGER.debug("C4 code result handle failed: {}", e.toString());
            }
        });
    }

    /**
     * Setting a code on a charge being planted, or offering one against a live charge.
     * The stored code never leaves the server, so this is the only place it is compared.
     */
    private static void handleC4Code(C4CodePayload payload, IPayloadContext context) {
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
            if (!holdsSettingsKey(player)
                    || !(level.getBlockEntity(pos) instanceof C4BlockEntity c4)) {
                return;
            }

            if (!payload.disarm()) {
                if (!c4.isArmed()) {
                    c4.setCode(payload.code());
                }
                return;
            }

            boolean accepted = c4.isArmed() && c4.matchesCode(payload.code());
            if (accepted) {
                c4.disarm();
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.8f, 1.4f);
            } else {
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(),
                        SoundSource.BLOCKS, 0.7f, 0.6f);
            }
            PacketDistributor.sendToPlayer(player, new C4CodeResultPayload(accepted));
        });
    }

    private static void handleWireResult(C4WireResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                Class.forName("com.cbc_more_content.client.gui.C4WireClient")
                        .getMethod("handle", C4WireResultPayload.class)
                        .invoke(null, payload);
            } catch (ReflectiveOperationException e) {
                CBCMoreContent.LOGGER.debug("C4 wire result handle failed: {}", e.toString());
            }
        });
    }

    /** Which wire does what lives only here, so the cut is resolved server-side. */
    private static void handleWireCut(C4WirePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = payload.pos();
            if (!level.isLoaded(pos) || player.distanceToSqr(
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH_SQR) {
                return;
            }
            if (!holdsWireCutters(player)
                    || !(level.getBlockEntity(pos) instanceof C4BlockEntity c4)) {
                return;
            }

            C4BlockEntity.WireResult result = c4.cutWire(level, payload.wire());
            if (result == C4BlockEntity.WireResult.NOTHING) {
                return;
            }
            // Swinging is replicated to every client, which is what drives the hinge on
            // the cutters for onlookers as well as the player doing the cutting.
            player.swing(player.getMainHandItem().getItem() instanceof WireCuttersItem
                    ? net.minecraft.world.InteractionHand.MAIN_HAND
                    : net.minecraft.world.InteractionHand.OFF_HAND, true);
            level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.9f, 1.3f);
            if (result == C4BlockEntity.WireResult.DEFUSED) {
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.8f, 1.4f);
            }
            PacketDistributor.sendToPlayer(player,
                    new C4WireResultPayload(payload.wire(), result.ordinal()));
        });
    }

    /** The flight plan is typed on a screen, so it is re-checked here like the rest. */
    private static void handleMissileTarget(MissileTargetPayload payload, IPayloadContext context) {
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
            if (!holdsSettingsKey(player)
                    || !(level.getBlockEntity(pos)
                            instanceof com.cbc_more_content.block.CruiseMissileBlockEntity guidance)) {
                return;
            }
            guidance.setTarget(new BlockPos(
                    Mth.clamp(payload.x(), -30_000_000, 30_000_000),
                    Mth.clamp(payload.y(), level.getMinBuildHeight(), level.getMaxBuildHeight()),
                    Mth.clamp(payload.z(), -30_000_000, 30_000_000)));
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundSource.BLOCKS, 0.6f, 1.4f);
        });
    }

    /**
     * Resolving a paint into a lock. The client only says which block it was looking at;
     * which sub-level that is belongs to the server, which is also the only side that
     * knows sub-level runtime ids at all.
     */
    private static void handleMissileLock(MissileLockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos missile = payload.missile();
            if (!level.isLoaded(missile) || !level.isLoaded(payload.painted())) {
                return;
            }
            if (!holdsDesignator(player)
                    || !(level.getBlockEntity(missile)
                            instanceof com.cbc_more_content.block.CruiseMissileBlockEntity guidance)) {
                return;
            }
            // Painting anywhere is allowed, but only a hull can actually be tracked;
            // otherwise this is just a very slow way of setting coordinates.
            int subLevel = net.neoforged.fml.ModList.get().isLoaded("sable")
                    ? com.cbc_more_content.compat.SableDropCompat.subLevelIdAt(level, payload.painted())
                    : -1;
            if (subLevel >= 0) {
                guidance.lockOnto(subLevel, net.minecraft.world.phys.Vec3.atCenterOf(payload.painted()));
            } else {
                guidance.setTarget(payload.painted());
            }
            level.playSound(null, missile, SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundSource.BLOCKS, 0.8f, 1.7f);
        });
    }

    private static boolean holdsDesignator(Player player) {
        return player.getMainHandItem().getItem() instanceof TargetDesignatorItem
                || player.getOffhandItem().getItem() instanceof TargetDesignatorItem;
    }

    private static boolean holdsWireCutters(Player player) {
        return player.getMainHandItem().getItem() instanceof WireCuttersItem
                || player.getOffhandItem().getItem() instanceof WireCuttersItem;
    }

    private static boolean holdsSettingsKey(Player player) {
        return player.getMainHandItem().getItem() instanceof BombSettingsKeyItem
                || player.getOffhandItem().getItem() instanceof BombSettingsKeyItem;
    }
}
