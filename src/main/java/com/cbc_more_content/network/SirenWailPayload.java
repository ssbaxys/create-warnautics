package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-bound: a post has started wailing, or is still at it.
 * <p>
 * Only ever a start. Stopping is not sent, because the client already knows — the voices
 * watch the block's own sounding state, which is what makes a siren go quiet the moment
 * it is broken rather than finishing its sample first.
 */
public record SirenWailPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<SirenWailPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "siren_wail"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SirenWailPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos),
                    buf -> new SirenWailPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
