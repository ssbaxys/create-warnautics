package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-bound: the preset chosen for the C4 stack in the player's hand. */
public record C4PresetPayload(boolean offhand, byte activation, boolean remote, int seconds, int code)
        implements CustomPacketPayload {
    public static final Type<C4PresetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "c4_preset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C4PresetPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.offhand);
                buf.writeByte(p.activation);
                buf.writeBoolean(p.remote);
                buf.writeVarInt(p.seconds);
                buf.writeInt(p.code);
            },
            buf -> new C4PresetPayload(
                    buf.readBoolean(), buf.readByte(), buf.readBoolean(), buf.readVarInt(), buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
