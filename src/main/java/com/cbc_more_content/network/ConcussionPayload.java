package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client cue: shell shock after surviving a near-miss blast.
 *
 * @param visual   strength of the white-out and defocus; zero when cover held
 * @param audio    strength of the ringing, which reaches around cover
 * @param durationTicks how long the effect runs
 */
public record ConcussionPayload(float visual, float audio, int durationTicks) implements CustomPacketPayload {

    public static final Type<ConcussionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "concussion"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConcussionPayload> STREAM_CODEC =
            StreamCodec.of(ConcussionPayload::write, ConcussionPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ConcussionPayload payload) {
        buf.writeFloat(payload.visual);
        buf.writeFloat(payload.audio);
        buf.writeVarInt(payload.durationTicks);
    }

    private static ConcussionPayload read(RegistryFriendlyByteBuf buf) {
        return new ConcussionPayload(buf.readFloat(), buf.readFloat(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
