package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.radar.InterceptSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-bound: intercept conditions set on the panel for a radar network. */
public record RadarSettingsPayload(BlockPos controller, InterceptSettings settings) implements CustomPacketPayload {

    public static final Type<RadarSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "radar_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RadarSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controller);
                buf.writeFloat(payload.settings.minSpeed());
                buf.writeVarInt(payload.settings.maxRange());
                buf.writeBoolean(payload.settings.hullsOnly());
            },
            buf -> new RadarSettingsPayload(
                    buf.readBlockPos(), new InterceptSettings(buf.readFloat(), buf.readVarInt(), buf.readBoolean())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
