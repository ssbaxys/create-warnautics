package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: release interval chosen on the bomb settings dial.
 * <p>
 * The interval always covers the whole contiguous rack the clicked bomb belongs to,
 * so there is nothing to negotiate per-block here.
 */
public record ConfigureBombPayload(
        BlockPos pos,
        int delayTicks) implements CustomPacketPayload {

    public static final Type<ConfigureBombPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "configure_bomb"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureBombPayload> STREAM_CODEC =
            StreamCodec.of(ConfigureBombPayload::write, ConfigureBombPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ConfigureBombPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeVarInt(payload.delayTicks);
    }

    private static ConfigureBombPayload read(RegistryFriendlyByteBuf buf) {
        return new ConfigureBombPayload(buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
