package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-bound verdict on a disarm attempt. Carries no code — only whether the one the
 * player already typed was right, so nothing here helps someone guess it.
 */
public record C4CodeResultPayload(boolean accepted) implements CustomPacketPayload {

    public static final Type<C4CodeResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "c4_code_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C4CodeResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBoolean(payload.accepted),
                    buf -> new C4CodeResultPayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
