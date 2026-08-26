package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-bound: open the switchboard, with the switches already where the server has them. */
public record OpenControlPanelPayload(boolean cannonFx) implements CustomPacketPayload {

    public static final Type<OpenControlPanelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "open_control_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenControlPanelPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBoolean(payload.cannonFx),
                    buf -> new OpenControlPanelPayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
