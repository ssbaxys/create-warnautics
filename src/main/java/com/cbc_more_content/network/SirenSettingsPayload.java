package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.siren.SirenSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-bound: what a siren post has been told to watch for. */
public record SirenSettingsPayload(BlockPos pos, SirenSettings settings) implements CustomPacketPayload {

    public static final Type<SirenSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "siren_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SirenSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeBoolean(payload.settings.auto());
                buf.writeVarInt(payload.settings.radius());
                buf.writeVarInt(payload.settings.lingerSeconds());
                buf.writeBoolean(payload.settings.watchMissiles());
                buf.writeBoolean(payload.settings.watchBombs());
            },
            buf -> new SirenSettingsPayload(
                    buf.readBlockPos(),
                    new SirenSettings(
                            buf.readBoolean(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readBoolean(),
                            buf.readBoolean())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
