package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: fuse length chosen on the C4 screen, whether to start the countdown, and
 * whether the charge answers to a detonator instead of its own clock.
 */
public record ConfigureC4Payload(BlockPos pos, int seconds, boolean arm, boolean remote)
        implements CustomPacketPayload {

    public static final Type<ConfigureC4Payload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "configure_c4"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureC4Payload> STREAM_CODEC =
            StreamCodec.of(ConfigureC4Payload::write, ConfigureC4Payload::read);

    private static void write(RegistryFriendlyByteBuf buf, ConfigureC4Payload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeVarInt(payload.seconds);
        buf.writeBoolean(payload.arm);
        buf.writeBoolean(payload.remote);
    }

    private static ConfigureC4Payload read(RegistryFriendlyByteBuf buf) {
        return new ConfigureC4Payload(buf.readBlockPos(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
