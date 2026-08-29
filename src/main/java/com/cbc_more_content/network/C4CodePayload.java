package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: a code typed on the C4 keypad. {@code disarm} false sets the code on a
 * charge being planted, true offers it against a live one.
 */
public record C4CodePayload(BlockPos pos, int code, boolean disarm) implements CustomPacketPayload {

    public static final Type<C4CodePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "c4_code"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C4CodePayload> STREAM_CODEC =
            StreamCodec.of(C4CodePayload::write, C4CodePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, C4CodePayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeVarInt(payload.code);
        buf.writeBoolean(payload.disarm);
    }

    private static C4CodePayload read(RegistryFriendlyByteBuf buf) {
        return new C4CodePayload(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
