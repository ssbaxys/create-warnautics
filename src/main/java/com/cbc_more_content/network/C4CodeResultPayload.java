package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4BlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-bound verdict on a disarm attempt. Carries no code, only what became of the one
 * the player already typed: accepted, wrong (with the attempts left), or locked (with the
 * seconds left), so nothing here helps someone guess it.
 */
public record C4CodeResultPayload(byte outcome, int value) implements CustomPacketPayload {

    public static final Type<C4CodeResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "c4_code_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C4CodeResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeByte(payload.outcome);
                buf.writeVarInt(payload.value);
            },
            buf -> new C4CodeResultPayload(buf.readByte(), buf.readVarInt()));

    public C4BlockEntity.Outcome verdict() {
        var all = C4BlockEntity.Outcome.values();
        return this.outcome >= 0 && this.outcome < all.length ? all[this.outcome] : C4BlockEntity.Outcome.WRONG;
    }

    public boolean accepted() {
        return this.verdict() == C4BlockEntity.Outcome.ACCEPTED;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
