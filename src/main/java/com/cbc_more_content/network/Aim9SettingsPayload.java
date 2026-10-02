package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record Aim9SettingsPayload(BlockPos pos, boolean enabled, boolean interceptCruise, int range)
        implements CustomPacketPayload {
    public static final Type<Aim9SettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "aim9_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Aim9SettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.pos());
                buf.writeBoolean(packet.enabled());
                buf.writeBoolean(packet.interceptCruise());
                buf.writeVarInt(packet.range());
            },
            buf -> new Aim9SettingsPayload(buf.readBlockPos(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
