package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: the designator finished a lock. Reports only which missile is paired and
 * which block the player was painting — the server works out what that block belongs to.
 */
public record MissileLockPayload(BlockPos missile, BlockPos painted)
        implements CustomPacketPayload {

    public static final Type<MissileLockPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "missile_lock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MissileLockPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.missile);
                        buf.writeBlockPos(payload.painted);
                    },
                    buf -> new MissileLockPayload(buf.readBlockPos(), buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
