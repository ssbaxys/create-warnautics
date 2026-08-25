package com.cbc_more_content.network;

import java.util.UUID;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: the designator has a hull locked and the operator pulled the trigger.
 * <p>
 * The hull is named by uuid, which is the only identifier both sides share — runtime ids
 * exist on the server alone, and are what the missile ends up following.
 */
public record MissileFirePayload(BlockPos missile, UUID subLevel) implements CustomPacketPayload {

    public static final Type<MissileFirePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "missile_fire"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MissileFirePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.missile);
                        buf.writeUUID(payload.subLevel);
                    },
                    buf -> new MissileFirePayload(buf.readBlockPos(), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
