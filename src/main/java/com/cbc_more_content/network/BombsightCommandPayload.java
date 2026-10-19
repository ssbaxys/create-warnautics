package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound, from a player looking through a bombsight: mark the spot under the reticle
 * as the target, drop now, switch automatic release, or clear the target.
 */
public record BombsightCommandPayload(BlockPos sight, byte action, int x, int z) implements CustomPacketPayload {
    public static final byte MARK = 0;
    public static final byte RELEASE = 1;
    public static final byte TOGGLE_AUTO = 2;
    public static final byte CLEAR = 3;

    public static final Type<BombsightCommandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bombsight_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BombsightCommandPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.sight);
                buf.writeByte(p.action);
                buf.writeInt(p.x);
                buf.writeInt(p.z);
            },
            buf -> new BombsightCommandPayload(buf.readBlockPos(), buf.readByte(), buf.readInt(), buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
