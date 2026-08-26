package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-bound: a post is wailing, and for roughly how much longer.
 * <p>
 * The remaining time is what lets a listener walk away from a raid and still hear it out.
 * Past the simulation distance the server stops ticking the post entirely — no more
 * keepalives, and the client's own copy of that chunk is gone too — so a voice that
 * depended on either went quiet at about a hundred and sixty blocks, well inside the range
 * the far layer is mixed for. Told how long it has, the client can run the rest itself.
 * <p>
 * The loudness travels with it because it is the rotor's, not the listener's: how hard
 * the shaft is turning decides how much voice the post has, and a listener too far off to
 * have the block loaded has no other way to know.
 * <p>
 * Stopping is still never sent. A listener close enough to have the chunk loaded sees the
 * block stop sounding, which is what makes a broken post go quiet at once.
 */
public record SirenWailPayload(BlockPos pos, int remainingTicks, float voice)
        implements CustomPacketPayload {

    public static final Type<SirenWailPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "siren_wail"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SirenWailPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.pos);
                        buf.writeVarInt(payload.remainingTicks);
                        buf.writeFloat(payload.voice);
                    },
                    buf -> new SirenWailPayload(
                            buf.readBlockPos(), buf.readVarInt(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
