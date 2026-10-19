package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The AIM-9 settings panel asking the server how the rack stands right now (readiness -1),
 * and the server's answer (readiness = ordinal of {@code Aim9Block.Readiness}).
 * <p>
 * Asked for rather than synced: a rack on a Sable ship lives in the ship's plot, whose block
 * updates do not reliably reach the client, so a synced status could be one the ship had
 * before it was turned over.
 */
public record Aim9StatusPayload(BlockPos pos, int readiness, boolean enabled, boolean interceptCruise, int range)
        implements CustomPacketPayload {
    /** The panel's question: only the position matters. */
    public static Aim9StatusPayload ask(BlockPos pos) {
        return new Aim9StatusPayload(pos, -1, false, false, 0);
    }

    public static final Type<Aim9StatusPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "aim9_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Aim9StatusPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.pos());
                buf.writeVarInt(packet.readiness() + 1);
                buf.writeBoolean(packet.enabled());
                buf.writeBoolean(packet.interceptCruise());
                buf.writeVarInt(packet.range());
            },
            buf -> new Aim9StatusPayload(
                    buf.readBlockPos(), buf.readVarInt() - 1, buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
