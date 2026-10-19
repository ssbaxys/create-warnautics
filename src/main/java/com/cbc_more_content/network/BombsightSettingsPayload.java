package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The bombsight panel's settings: mode, target, automatic release and the stick. */
public record BombsightSettingsPayload(
        BlockPos pos,
        byte mode,
        boolean hasTarget,
        int targetX,
        int targetZ,
        boolean autoRelease,
        int seriesTicks,
        boolean centred)
        implements CustomPacketPayload {
    public static final Type<BombsightSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bombsight_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BombsightSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeByte(p.mode);
                buf.writeBoolean(p.hasTarget);
                buf.writeInt(p.targetX);
                buf.writeInt(p.targetZ);
                buf.writeBoolean(p.autoRelease);
                buf.writeVarInt(p.seriesTicks);
                buf.writeBoolean(p.centred);
            },
            buf -> new BombsightSettingsPayload(
                    buf.readBlockPos(),
                    buf.readByte(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
