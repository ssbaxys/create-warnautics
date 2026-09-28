package com.cbc_more_content.command;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.network.OpenControlPanelPayload;
import com.cbc_more_content.settings.WarnauticsServerSettings;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /cw panel} — the server's switchboard.
 * <p>
 * Operator only, because what it sets is server-wide: one person changes it and everybody
 * on the map gets the change. Gated at the permission level rather than in the screen, so
 * a hand-built packet is refused for the same reason the command is.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class WarnauticsCommands {
    /** Game-master level. The same bar vanilla puts on /gamerule, and for the same reason. */
    public static final int PERMISSION_LEVEL = 2;

    private WarnauticsCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cw")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("panel").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) {
                        // The panel is a screen; there is nowhere to put one on a console.
                        context.getSource()
                                .sendFailure(Component.translatable("command.cbc_more_content.panel.player_only"));
                        return 0;
                    }
                    open(player);
                    return 1;
                }));
        root.then(Commands.literal("debug")
                .then(Commands.literal("sea_mine")
                        .then(Commands.literal("status").executes(context -> corrosion(context.getSource(), null)))
                        .then(Commands.literal("stage")
                                .then(Commands.argument(
                                                "stage",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 3))
                                        .executes(context -> corrosion(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                                context, "stage")
                                                        * com.cbc_more_content.block.SeaMineBlockEntity
                                                                .OXIDATION_TICKS_PER_STAGE))))
                        .then(Commands.literal("age")
                                .then(Commands.argument(
                                                "ticks",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                                                        0,
                                                        3
                                                                * com.cbc_more_content.block.SeaMineBlockEntity
                                                                        .OXIDATION_TICKS_PER_STAGE))
                                        .executes(context -> corrosion(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                        context, "ticks")))))));
        event.getDispatcher().register(root);
    }

    private static int corrosion(CommandSourceStack source, Integer ticks) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.cbc_more_content.panel.player_only"));
            return 0;
        }
        var eye = player.getEyePosition();
        var look = player.getLookAngle();
        var scene = new com.cbc_more_content.effects.BlastScene(source.getLevel(), eye, 8);
        var mine =
                scene
                        .matching(eye, 8, state -> state.getBlock() instanceof com.cbc_more_content.block.SeaMineBlock)
                        .stream()
                        .filter(block -> {
                            var delta = block.worldCenter().subtract(eye);
                            double along = delta.dot(look);
                            return along >= 0
                                    && delta.subtract(look.scale(along)).lengthSqr() <= 0.75 * 0.75;
                        })
                        .min(java.util.Comparator.comparingDouble(
                                block -> block.worldCenter().distanceToSqr(eye)))
                        .map(block -> source.getLevel().getBlockEntity(block.pos()))
                        .orElse(null);
        if (!(mine instanceof com.cbc_more_content.block.SeaMineBlockEntity seaMine)) {
            source.sendFailure(Component.translatable("command.cbc_more_content.sea_mine.missing"));
            return 0;
        }
        if (ticks != null) {
            seaMine.setCorrosionAge(ticks);
        }
        int stage = seaMine.getBlockState().getValue(com.cbc_more_content.block.SeaMineBlock.OXIDATION);
        source.sendSuccess(
                () -> Component.translatable(
                        "command.cbc_more_content.sea_mine.status",
                        stage,
                        seaMine.corrosionAge(),
                        com.cbc_more_content.block.SeaMineBlockEntity.touchesWater(
                                source.getLevel(),
                                com.cbc_more_content.block.SeaMineBlockEntity.worldPosition(
                                        source.getLevel(), seaMine.getBlockPos()))),
                false);
        source.sendSuccess(
                () -> Component.translatable("command.cbc_more_content.sea_mine.misfire", (int)
                        Math.round(com.cbc_more_content.block.SeaMineBlockEntity.contactMisfireChance(stage) * 100)),
                false);
        return 1;
    }

    /** Sends an operator the current switch positions so the panel opens reading true. */
    public static void open(ServerPlayer player) {
        WarnauticsServerSettings settings = WarnauticsServerSettings.get(player.server);
        PacketDistributor.sendToPlayer(player, new OpenControlPanelPayload(settings.cannonFx()));
    }
}
