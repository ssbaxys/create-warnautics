package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client cue: look-at bloom / jaundice flash for a bomb detonation. */
public record BombFlashPayload(
        double x,
        double y,
        double z,
        float intensity,
        byte sizeOrdinal) implements CustomPacketPayload {

    public static final Type<BombFlashPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bomb_flash"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BombFlashPayload> STREAM_CODEC =
            StreamCodec.of(BombFlashPayload::write, BombFlashPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, BombFlashPayload payload) {
        buf.writeDouble(payload.x);
        buf.writeDouble(payload.y);
        buf.writeDouble(payload.z);
        buf.writeFloat(payload.intensity);
        buf.writeByte(payload.sizeOrdinal);
    }

    private static BombFlashPayload read(RegistryFriendlyByteBuf buf) {
        return new BombFlashPayload(
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readByte());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
