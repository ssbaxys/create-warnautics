package com.cbc_more_content.network;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.item.BombSettingsKeyItem;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.item.WireCuttersItem;
import com.cbc_more_content.siren.SirenSource;
import com.cbc_more_content.util.ReflectiveDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
        event.registrar("11")
                .playToServer(
                        Aim9SettingsPayload.TYPE, Aim9SettingsPayload.STREAM_CODEC, ModNetworking::handleAim9Settings)
                .playBidirectional(
                        Aim9StatusPayload.TYPE, Aim9StatusPayload.STREAM_CODEC, ModNetworking::handleAim9Status)
                .playToServer(
                        RocketPodSettingsPayload.TYPE,
                        RocketPodSettingsPayload.STREAM_CODEC,
                        ModNetworking::handleRocketPodSettings)
                .playToClient(BombFlashPayload.TYPE, BombFlashPayload.STREAM_CODEC, ModNetworking::handleClient)
                .playToClient(
                        BombsightSolutionPayload.TYPE,
                        BombsightSolutionPayload.STREAM_CODEC,
                        ModNetworking::handleBombsightSolution)
                .playToServer(
                        BombsightSettingsPayload.TYPE,
                        BombsightSettingsPayload.STREAM_CODEC,
                        ModNetworking::handleBombsightSettings)
                .playToClient(WaterBlastPayload.TYPE, WaterBlastPayload.STREAM_CODEC, ModNetworking::handleWater)
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
                .playToServer(C4PresetPayload.TYPE, C4PresetPayload.STREAM_CODEC, ModNetworking::handleC4Preset)
                .playToServer(
                        BombsightCommandPayload.TYPE,
                        BombsightCommandPayload.STREAM_CODEC,
                        ModNetworking::handleBombsightCommand)
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
                        ControlPanelPayload.TYPE, ControlPanelPayload.STREAM_CODEC, ModNetworking::handleControlPanel)
                .playToClient(NoticePayload.TYPE, NoticePayload.STREAM_CODEC, ModNetworking::handleNotice);
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

    private static void handleWater(WaterBlastPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.WaterBlastClient",
                "handle",
                new Class<?>[] {WaterBlastPayload.class},
                payload));
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

    /**
     * A player at the eyepiece: only someone standing at the sight (on the ship, if it is on
     * one) can work it, the same reach as a block.
     */
    private static void handleBombsightCommand(BombsightCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)
                    || !level.isLoaded(payload.sight())
                    || !(level.getBlockEntity(payload.sight())
                            instanceof com.cbc_more_content.bombsight.BombsightBlockEntity sight)) {
                return;
            }
            var at = com.cbc_more_content.compat.SableDropCompat.resolveWorldBlastChecked(
                            level, payload.sight().getCenter())
                    .pos();
            if (player.distanceToSqr(at) > 16 * 16) {
                return;
            }
            switch (payload.action()) {
                case BombsightCommandPayload.MARK -> {
                    sight.markTarget(payload.x(), payload.z());
                    level.playSound(
                            null, payload.sight(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, 1.4f);
                }
                case BombsightCommandPayload.RELEASE -> sight.releaseNow(level);
                case BombsightCommandPayload.TOGGLE_AUTO -> {
                    sight.setAutoRelease(!sight.autoRelease());
                    level.playSound(null, payload.sight(), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 1.3f);
                }
                case BombsightCommandPayload.CLEAR -> sight.clearTarget();
                default -> {}
            }
        });
    }

    /** The preset is kept on the C4 stack itself; the server only checks the stack really is C4. */
    private static void handleC4Preset(C4PresetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !com.cbc_more_content.config.WarnauticsConfig.c4PresetsAllowed()) {
                return;
            }
            var stack = player.getItemInHand(
                    payload.offhand()
                            ? net.minecraft.world.InteractionHand.OFF_HAND
                            : net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!(stack.getItem() instanceof com.cbc_more_content.item.C4Item)) {
                return;
            }
            byte activation = payload.activation();
            if (activation == com.cbc_more_content.item.C4Preset.ON_THROW
                    && !com.cbc_more_content.config.WarnauticsConfig.c4ArmOnThrowAllowed()) {
                activation = com.cbc_more_content.item.C4Preset.MANUAL;
            }
            new com.cbc_more_content.item.C4Preset(activation, payload.remote(), payload.seconds(), payload.code())
                    .store(stack);
            player.level()
                    .playSound(
                            null,
                            player.blockPosition(),
                            com.cbc_more_content.registry.ModSounds.C4_BUTTON.get(),
                            SoundSource.PLAYERS,
                            0.7f,
                            1.2f);
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

            if (!c4.isArmed()) {
                PacketDistributor.sendToPlayer(player, new C4CodeResultPayload((byte) 1, -1));
                return;
            }
            // Attempts are limited server-side: a modified client cannot guess its way past.
            var verdict = c4.tryCode(player.getUUID(), payload.code());
            switch (verdict.outcome()) {
                case ACCEPTED -> {
                    c4.disarm();
                    level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.8f, 1.4f);
                }
                case LOCKED -> level.playSound(
                        null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.9f, 0.6f);
                case WRONG -> level.playSound(
                        null, pos, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.BLOCKS, 0.7f, 0.6f);
                case DETONATED -> {}
            }
            PacketDistributor.sendToPlayer(
                    player, new C4CodeResultPayload((byte) verdict.outcome().ordinal(), verdict.value()));
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
    private static void handleNotice(NoticePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.cbc_more_content.ui.Notices.show(payload));
    }

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
            com.cbc_more_content.ui.Notices.send(
                    player,
                    payload.cannonFx() ? com.cbc_more_content.ui.UiTone.SUCCESS : com.cbc_more_content.ui.UiTone.MUTED,
                    payload.cannonFx()
                            ? "message.cbc_more_content.panel.cannon_fx_on"
                            : "message.cbc_more_content.panel.cannon_fx_off");
        });
    }

    /** A post has started, or is still going; the client holds its voices open. */
    private static void handleSirenWail(SirenWailPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.sound.SirenSoundManager",
                    "wail",
                    new Class<?>[] {SirenWailPayload.class},
                    payload);
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
            if (payload.flightProfile() < 0 || payload.flightProfile() > 2) {
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
            guidance.setFlightProfile(
                    com.cbc_more_content.munitions.MissileFlightProfile.byId(payload.flightProfile()));
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
            ItemStack designator = player.getMainHandItem().getItem() instanceof TargetDesignatorItem
                    ? player.getMainHandItem()
                    : player.getOffhandItem();
            if (!(designator.getItem() instanceof TargetDesignatorItem)) {
                return;
            }
            var missiles = com.cbc_more_content.item.TargetDesignatorItem.resolveBoundMissiles(level, designator);
            if (!missiles.contains(payload.missile())) {
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
            var fired = new java.util.ArrayList<BlockPos>();
            var salvo = java.util.UUID.randomUUID();
            var airborne = new java.util.ArrayList<com.cbc_more_content.munitions.CruiseMissileProjectile>();
            for (BlockPos missile : missiles) {
                if (!com.cbc_more_content.compat.MissileDesignatorTargeting.canControl(player, missile)
                        || !com.cbc_more_content.compat.MissileDesignatorTargeting.canTarget(
                                player, missile, payload.subLevel(), centre)) {
                    continue;
                }
                BlockState state = level.getBlockState(missile);
                if (!(state.getBlock() instanceof com.cbc_more_content.block.CruiseMissileBlock)
                        || !(level.getBlockEntity(missile)
                                instanceof com.cbc_more_content.block.CruiseMissileBlockEntity guidance)
                        || guidance.guidance() != com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance.REMOTE) {
                    continue;
                }
                guidance.lockOnto(runtimeId, centre);
                var launched = com.cbc_more_content.block.CruiseMissileBlock.launch(level, missile, state);
                if (launched != null) {
                    airborne.add(launched);
                    fired.add(missile);
                }
            }
            var salvoOrigin = net.minecraft.world.phys.Vec3.ZERO;
            for (var missile : airborne) {
                salvoOrigin = salvoOrigin.add(missile.position());
            }
            salvoOrigin = salvoOrigin.scale(1.0 / Math.max(1, airborne.size()));
            for (int index = 0; index < airborne.size(); index++) {
                airborne.get(index).setSalvo(salvo, index, airborne.size(), salvoOrigin);
            }
            com.cbc_more_content.item.TargetDesignatorItem.removeLaunched(designator, fired);
            if (!fired.isEmpty()) {
                com.cbc_more_content.ui.Notices.send(
                        player,
                        com.cbc_more_content.ui.UiTone.SUCCESS,
                        "message.cbc_more_content.designator.fired",
                        fired.size());
            }
        });
    }

    private static void handleBombsightSolution(BombsightSolutionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.BombsightClient",
                "receive",
                new Class<?>[] {BombsightSolutionPayload.class},
                payload));
    }

    private static void handleBombsightSettings(BombsightSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !holdsSettingsKey(player)
                    || !canAccess(player, payload.pos())
                    || !(player.level().getBlockEntity(payload.pos())
                            instanceof com.cbc_more_content.bombsight.BombsightBlockEntity sight)) {
                return;
            }
            sight.configure(
                    payload.mode(),
                    payload.hasTarget(),
                    payload.targetX(),
                    payload.targetZ(),
                    payload.autoRelease(),
                    payload.seriesTicks(),
                    payload.centred());
            player.level()
                    .playSound(null, payload.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, .5f, 1.25f);
        });
    }

    /** Server: answer with the rack's readiness worked out now. Client: show it. */
    private static void handleAim9Status(Aim9StatusPayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                ReflectiveDispatcher.invoke(
                        "com.cbc_more_content.client.gui.Aim9SettingsClient",
                        "status",
                        new Class<?>[] {Aim9StatusPayload.class},
                        packet);
                return;
            }
            if (!canAccess(player, packet.pos())
                    || !(player.level() instanceof ServerLevel server)
                    || !(server.getBlockEntity(packet.pos()) instanceof com.cbc_more_content.block.Aim9BlockEntity be)
                    || !be.isLiveAirframe()) {
                return;
            }
            var state = server.getBlockState(packet.pos());
            var readiness = state.getValue(com.cbc_more_content.block.Aim9Block.WATERLOGGED)
                    ? com.cbc_more_content.block.Aim9Block.Readiness.FLOODED
                    : com.cbc_more_content.block.Aim9Block.skyCheck(server, packet.pos());
            if (readiness == com.cbc_more_content.block.Aim9Block.Readiness.READY
                    && be.readiness() == com.cbc_more_content.block.Aim9Block.Readiness.NO_SIGHT) {
                readiness = be.readiness();
            }
            PacketDistributor.sendToPlayer(
                    player,
                    new Aim9StatusPayload(
                            packet.pos(), readiness.ordinal(), be.enabled(), be.interceptCruise(), be.range()));
        });
    }

    private static void handleRocketPodSettings(RocketPodSettingsPayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !holdsSettingsKey(player)
                    || !canAccess(player, packet.pos())) {
                return;
            }
            var state = player.level().getBlockState(packet.pos());
            if (!(state.getBlock() instanceof com.cbc_more_content.block.RocketPodBlock)
                    || !com.cbc_more_content.block.RocketPodBlock.formed(state)
                    || !(player.level()
                                    .getBlockEntity(
                                            com.cbc_more_content.block.RocketPodBlock.bodyOf(state, packet.pos()))
                            instanceof com.cbc_more_content.block.RocketPodBlockEntity pod)) {
                return;
            }
            pod.configure(packet.ripple(), packet.salvo());
            player.level()
                    .playSound(null, packet.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, .5f, 1.25f);
        });
    }

    private static void handleAim9Settings(Aim9SettingsPayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !holdsSettingsKey(player)
                    || !canAccess(player, packet.pos())
                    || !(player.level().getBlockEntity(packet.pos())
                            instanceof com.cbc_more_content.block.Aim9BlockEntity be)
                    || !be.isLiveAirframe()) {
                return;
            }
            be.configure(packet.enabled(), packet.interceptCruise(), packet.range());
            player.level()
                    .playSound(null, packet.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, .5f, 1.25f);
        });
    }

    private static boolean holdsWireCutters(Player player) {
        return player.getMainHandItem().getItem() instanceof WireCuttersItem
                || player.getOffhandItem().getItem() instanceof WireCuttersItem;
    }

    private static boolean canAccess(Player player, BlockPos pos) {
        return player.level().isLoaded(pos)
                && player.distanceToSqr(SirenSource.capture(player.level(), pos).worldPosition()) <= REACH_SQR;
    }

    private static boolean holdsSettingsKey(Player player) {
        return player.getMainHandItem().getItem() instanceof BombSettingsKeyItem
                || player.getOffhandItem().getItem() instanceof BombSettingsKeyItem;
    }
}
