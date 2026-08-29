package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: the flight plan set on a placed missile. {@code mode} is 0 for a typed
 * point, 1 to hand it to a designator, 2 to put it on radar guidance.
 */
public record MissileTargetPayload(BlockPos pos, int x, int y, int z, int mode) implements CustomPacketPayload {

    public static final Type<MissileTargetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "missile_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MissileTargetPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeInt(payload.x);
                buf.writeInt(payload.y);
                buf.writeInt(payload.z);
                buf.writeVarInt(payload.mode);
            },
            buf -> new MissileTargetPayload(
                    buf.readBlockPos(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
