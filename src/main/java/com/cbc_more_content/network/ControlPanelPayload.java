package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: a switch has been thrown on the panel.
 * <p>
 * The permission check is repeated where this is handled. Opening the screen went through
 * an operator-only command, but nothing stops a client from sending this without ever
 * having run it.
 */
public record ControlPanelPayload(boolean cannonFx) implements CustomPacketPayload {

    public static final Type<ControlPanelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "control_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlPanelPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBoolean(payload.cannonFx),
                    buf -> new ControlPanelPayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
