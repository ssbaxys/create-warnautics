package com.cbc_more_content.network;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.item.BombSettingsKeyItem;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.item.WireCuttersItem;
import com.cbc_more_content.util.ReflectiveDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ModNetworking {
    /** How far a player may be from the bomb they are configuring. */
    private static final double REACH_SQR = 8.0D * 8.0D;

    private ModNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(BombFlashPayload.TYPE, BombFlashPayload.STREAM_CODEC, ModNetworking::handleClient)
                .playToClient(ConcussionPayload.TYPE, ConcussionPayload.STREAM_CODEC, ModNetworking::handleConcussion)
                .playToClient(
                        C4CodeResultPayload.TYPE, C4CodeResultPayload.STREAM_CODEC, ModNetworking::handleCodeResult)
                .playToClient(
                        C4WireResultPayload.TYPE, C4WireResultPayload.STREAM_CODEC, ModNetworking::handleWireResult)
                .playToClient(
                        OpenRadarSettingsPayload.TYPE,
                        OpenRadarSettingsPayload.STREAM_CODEC,
                        ModNetworking::handleOpenRadarSettings)
                .playToServer(
                        RadarSettingsPayload.TYPE,
                        RadarSettingsPayload.STREAM_CODEC,
                        ModNetworking::handleRadarSettings)
                .playToServer(
                        MissileFirePayload.TYPE, MissileFirePayload.STREAM_CODEC, ModNetworking::handleMissileFire)
                .playToServer(
                        MissileTargetPayload.TYPE,
                        MissileTargetPayload.STREAM_CODEC,
                        ModNetworking::handleMissileTarget)
                .playToServer(C4WirePayload.TYPE, C4WirePayload.STREAM_CODEC, ModNetworking::handleWireCut)
                .playToServer(C4CodePayload.TYPE, C4CodePayload.STREAM_CODEC, ModNetworking::handleC4Code)
                .playToServer(
                        ConfigureC4Payload.TYPE, ConfigureC4Payload.STREAM_CODEC, ModNetworking::handleConfigureC4)
                .playToServer(
                        ConfigureBombPayload.TYPE,
                        ConfigureBombPayload.STREAM_CODEC,
                        ModNetworking::handleConfigureBomb)
                .playToServer(
                        SirenSettingsPayload.TYPE,
                        SirenSettingsPayload.STREAM_CODEC,
                        ModNetworking::handleSirenSettings)
                .playToClient(SirenWailPayload.TYPE, SirenWailPayload.STREAM_CODEC, ModNetworking::handleSirenWail)
                .playToClient(
                        OpenControlPanelPayload.TYPE,
                        OpenControlPanelPayload.STREAM_CODEC,
                        ModNetworking::handleOpenControlPanel)
                .playToServer(
                        ControlPanelPayload.TYPE, ControlPanelPayload.STREAM_CODEC, ModNetworking::handleControlPanel);
    }

    private static void handleConcussion(ConcussionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.ConcussionClient",
                    "handle",
                    new Class<?>[] {ConcussionPayload.class},
                    payload);
        });
    }

    private static void handleClient(BombFlashPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.BombFlashClient",
                    "handle",
                    new Class<?>[] {BombFlashPayload.class},
                    payload);
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
            if (!canAccess(player, pos)) {
                return;
            }
            if (!(level.getBlockState(pos).getBlock() instanceof DropBombBlock bomb) || !bomb.allowsCassette()) {
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
            if (!canAccess(player, pos)) {
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
            c4.setRemote(payload.remote());
            if (payload.arm()) {
                // arm() plays the charge going live itself.
                c4.arm();
            }
        });
    }

    private static void handleCodeResult(C4CodeResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.gui.C4CodeClient",
                    "handle",
                    new Class<?>[] {C4CodeResultPayload.class},
                    payload);
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
            if (!canAccess(player, pos)) {
                return;
            }
            if (!holdsSettingsKey(player) || !(level.getBlockEntity(pos) instanceof C4BlockEntity c4)) {
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
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.BLOCKS, 0.7f, 0.6f);
            }
            PacketDistributor.sendToPlayer(player, new C4CodeResultPayload(accepted));
        });
    }

    private static void handleWireResult(C4WireResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.gui.C4WireClient",
                    "handle",
                    new Class<?>[] {C4WireResultPayload.class},
                    payload);
        });
    }

    /** Which wire does what lives only here, so the cut is resolved server-side. */
    private static void handleWireCut(C4WirePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = payload.pos();
            if (!canAccess(player, pos)) {
                return;
            }
            if (!holdsWireCutters(player) || !(level.getBlockEntity(pos) instanceof C4BlockEntity c4)) {
                return;
            }

            C4BlockEntity.WireResult result = c4.cutWire(level, payload.wire());
            if (result == C4BlockEntity.WireResult.NOTHING) {
                return;
            }
            // Swinging is replicated to every client, which is what drives the hinge on
            // the cutters for onlookers as well as the player doing the cutting.
            player.swing(
                    player.getMainHandItem().getItem() instanceof WireCuttersItem
                            ? net.minecraft.world.InteractionHand.MAIN_HAND
                            : net.minecraft.world.InteractionHand.OFF_HAND,
                    true);
            level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.9f, 1.3f);
            if (result == C4BlockEntity.WireResult.DEFUSED) {
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.8f, 1.4f);
            }
            PacketDistributor.sendToPlayer(player, new C4WireResultPayload(payload.wire(), result.ordinal()));
        });
    }

    /** The switchboard is opened by an operator who ran the command. */
    private static void handleOpenControlPanel(OpenControlPanelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.gui.ControlPanelClient",
                    "open",
                    new Class<?>[] {boolean.class},
                    payload.cannonFx());
        });
    }

    /**
     * A switch thrown on the panel. Re-checked against the same permission level the
     * command needs: having a screen open is not authority, and nothing stops a client
     * from sending this without ever having run the command.
     */
    private static void handleControlPanel(ControlPanelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !player.hasPermissions(com.cbc_more_content.command.WarnauticsCommands.PERMISSION_LEVEL)) {
                return;
            }
            com.cbc_more_content.settings.WarnauticsServerSettings.get(player.server)
                    .setCannonFx(payload.cannonFx());
            player.displayClientMessage(
                    Component.translatable(
                                    payload.cannonFx()
                                            ? "message.cbc_more_content.panel.cannon_fx_on"
                                            : "message.cbc_more_content.panel.cannon_fx_off")
                            .withStyle(
                                    payload.cannonFx()
                                            ? net.minecraft.ChatFormatting.GREEN
                                            : net.minecraft.ChatFormatting.GRAY),
                    true);
        });
    }

    /** A post has started, or is still going; the client holds its voices open. */
    private static void handleSirenWail(SirenWailPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.sound.SirenSoundManager",
                    "wail",
                    new Class<?>[] {BlockPos.class, int.class, float.class},
                    payload.pos(),
                    payload.remainingTicks(),
                    payload.voice());
        });
    }

    /** Same validation as everything else the key opens: reach, block identity, key. */
    private static void handleSirenSettings(SirenSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            BlockPos pos = payload.pos();
            if (!canAccess(player, pos)) {
                return;
            }
            if (!holdsSettingsKey(player)
                    || !(level.getBlockEntity(pos) instanceof com.cbc_more_content.block.SirenBlockEntity siren)) {
                return;
            }
            siren.applySettings(payload.settings());
            level.playSound(
                    null,
                    pos,
                    SoundEvents.UI_BUTTON_CLICK.value(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    0.8f,
                    1.3f);
        });
    }

    private static void handleMissileTarget(MissileTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            BlockPos pos = payload.pos();
            if (!canAccess(player, pos)) {
                return;
            }
            if (!holdsSettingsKey(player)
                    || !(level.getBlockEntity(pos)
                            instanceof com.cbc_more_content.block.CruiseMissileBlockEntity guidance)) {
                return;
            }
            if (payload.mode() == 1) {
                guidance.armRemote();
            } else if (payload.mode() == 2) {
                // The client hides this mode without Create Radar; the server refuses it
                // outright, so a hand-built packet cannot park a missile on a picture
                // that nothing is painting.
                if (!com.cbc_more_content.compat.RadarCompat.loaded()) {
                    return;
                }
                guidance.armIntercept();
            } else {
                guidance.setTarget(new BlockPos(
                        Mth.clamp(payload.x(), -30_000_000, 30_000_000),
                        Mth.clamp(payload.y(), level.getMinBuildHeight(), level.getMaxBuildHeight()),
                        Mth.clamp(payload.z(), -30_000_000, 30_000_000)));
            }
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.6f, 1.4f);
        });
    }

    /** Opens the intercept panel with the network's current settings. */
    private static void handleOpenRadarSettings(OpenRadarSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.gui.RadarSettingsClient",
                    "handle",
                    new Class<?>[] {OpenRadarSettingsPayload.class},
                    payload);
        });
    }

    /**
     * Intercept conditions coming back from the panel. Re-checked here like everything
     * else: the position has to be a Create Radar block the player is stood next to, not
     * whatever a client cares to name.
     */
    private static void handleRadarSettings(RadarSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos controller = payload.controller();
            if (!canAccess(player, controller) || !holdsSettingsKey(player)) {
                return;
            }
            if (!com.cbc_more_content.compat.RadarCompat.isRadarModBlock(level.getBlockEntity(controller))) {
                return;
            }
            com.cbc_more_content.radar.InterceptSettingsStore.get(level).set(controller, payload.settings());
            level.playSound(null, controller, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.7f, 1.3f);
        });
    }

    private static void handleMissileFire(MissileFirePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos missile = payload.missile();
            if (!canAccess(player, missile) || !holdsDesignator(player)) {
                return;
            }
            BlockPos bound = com.cbc_more_content.item.TargetDesignatorItem.boundMissile(player.getMainHandItem());
            if (bound == null || !bound.equals(missile)) {
                return;
            }
            BlockState state = level.getBlockState(missile);
            if (!(state.getBlock() instanceof com.cbc_more_content.block.CruiseMissileBlock)
                    || !(level.getBlockEntity(missile)
                            instanceof com.cbc_more_content.block.CruiseMissileBlockEntity guidance)
                    || guidance.guidance() != com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance.REMOTE) {
                return;
            }
            if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
                return;
            }

            int runtimeId = com.cbc_more_content.compat.SableTrackCompat.runtimeIdOf(level, payload.subLevel());
            net.minecraft.world.phys.Vec3 centre =
                    com.cbc_more_content.compat.SableTrackCompat.centreOf(level, payload.subLevel());
            if (runtimeId < 0 || centre == null) {
                return;
            }

            guidance.lockOnto(runtimeId, centre);
            com.cbc_more_content.block.CruiseMissileBlock.launch(level, missile, state);
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

    private static boolean canAccess(Player player, BlockPos pos) {
        return player.level().isLoaded(pos)
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= REACH_SQR;
    }

    private static boolean holdsSettingsKey(Player player) {
        return player.getMainHandItem().getItem() instanceof BombSettingsKeyItem
                || player.getOffhandItem().getItem() instanceof BombSettingsKeyItem;
    }
}
