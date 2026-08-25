package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.radar.InterceptSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-bound: open the intercept panel for a radar network, already filled in.
 * <p>
 * The settings live on the server, so the screen is opened by the server rather than
 * guessed at by the client — otherwise every operator would be shown defaults and would
 * overwrite whatever the network was actually set to.
 */
public record OpenRadarSettingsPayload(BlockPos controller, InterceptSettings settings)
        implements CustomPacketPayload {

    public static final Type<OpenRadarSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "open_radar_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRadarSettingsPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.controller);
                        buf.writeFloat(payload.settings.minSpeed());
                        buf.writeVarInt(payload.settings.maxRange());
                        buf.writeBoolean(payload.settings.hullsOnly());
                    },
                    buf -> new OpenRadarSettingsPayload(
                            buf.readBlockPos(),
                            new InterceptSettings(buf.readFloat(), buf.readVarInt(), buf.readBoolean())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
