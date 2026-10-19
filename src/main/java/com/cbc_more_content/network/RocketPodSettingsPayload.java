package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Settings key on a rocket pod: ticks between rockets, and rockets per pulse (0 = all). */
public record RocketPodSettingsPayload(BlockPos pos, int ripple, int salvo) implements CustomPacketPayload {
    public static final Type<RocketPodSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "rocket_pod_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RocketPodSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.pos());
                buf.writeVarInt(packet.ripple());
                buf.writeVarInt(packet.salvo());
            },
            buf -> new RocketPodSettingsPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
