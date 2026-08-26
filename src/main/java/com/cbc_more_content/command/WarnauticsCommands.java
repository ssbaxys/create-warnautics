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

    private WarnauticsCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cw")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("panel").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) {
                        // The panel is a screen; there is nowhere to put one on a console.
                        context.getSource().sendFailure(
                                Component.translatable("command.cbc_more_content.panel.player_only"));
                        return 0;
                    }
                    open(player);
                    return 1;
                }));
        event.getDispatcher().register(root);
    }

    /** Sends an operator the current switch positions so the panel opens reading true. */
    public static void open(ServerPlayer player) {
        WarnauticsServerSettings settings = WarnauticsServerSettings.get(player.server);
        PacketDistributor.sendToPlayer(player,
                new OpenControlPanelPayload(settings.cannonFx()));
    }
}
