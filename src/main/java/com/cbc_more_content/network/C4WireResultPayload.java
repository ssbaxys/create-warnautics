package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-bound outcome of a cut: which wire, and what it did. Only ever sent about a wire
 * the player already cut, so it gives nothing away about the other two.
 */
public record C4WireResultPayload(int wire, int outcome) implements CustomPacketPayload {

    public static final Type<C4WireResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "c4_wire_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C4WireResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.wire);
                        buf.writeVarInt(payload.outcome);
                    },
                    buf -> new C4WireResultPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
