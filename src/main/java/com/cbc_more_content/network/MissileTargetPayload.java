package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-bound: the flight plan typed into a placed missile. */
public record MissileTargetPayload(BlockPos pos, int x, int y, int z)
        implements CustomPacketPayload {

    public static final Type<MissileTargetPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "missile_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MissileTargetPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.pos);
                        buf.writeInt(payload.x);
                        buf.writeInt(payload.y);
                        buf.writeInt(payload.z);
                    },
                    buf -> new MissileTargetPayload(
                            buf.readBlockPos(), buf.readInt(), buf.readInt(), buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
