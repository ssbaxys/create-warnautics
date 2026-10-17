package com.cbc_more_content.ui;

import com.cbc_more_content.network.NoticePayload;
import com.cbc_more_content.util.ReflectiveDispatcher;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Short status messages to one player: "paired", "out of range", "wire strung".
 * <p>
 * Every item and handler reports through here instead of the vanilla action bar, so the
 * mod speaks in one voice: the same strip above the hotbar, a lamp in the tone's colour,
 * and the same five tones everywhere. Called on the server it sends the notice to that
 * player; called on the client it shows it directly.
 */
public final class Notices {
    private static final String HUD = "com.cbc_more_content.client.hud.NoticeHud";

    private Notices() {}

    public static void send(@Nullable Player player, UiTone tone, Component message) {
        if (player == null) {
            return;
        }
        if (player instanceof ServerPlayer server) {
            PacketDistributor.sendToPlayer(server, new NoticePayload(tone, message));
        } else if (player.level().isClientSide) {
            show(new NoticePayload(tone, message));
        }
    }

    public static void send(@Nullable Player player, UiTone tone, String key, Object... args) {
        send(player, tone, Component.translatable(key, args));
    }

    /** Client side: hands a notice to the HUD strip, if this is a client at all. */
    public static void show(NoticePayload notice) {
        ReflectiveDispatcher.invoke(HUD, "push", new Class<?>[] {NoticePayload.class}, notice);
    }
}
