package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.siren.SirenSource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A renewable sound lease; zero remaining ticks explicitly stops both voices. */
public record SirenWailPayload(SirenSource source, int remainingTicks, float voice) implements CustomPacketPayload {
    public static final int MAX_LEASE_TICKS = 120;
    public static final Type<SirenWailPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "siren_wail"));

    public SirenWailPayload {
        remainingTicks = Mth.clamp(remainingTicks, 0, MAX_LEASE_TICKS);
        voice = Float.isFinite(voice) ? Mth.clamp(voice, 0, 1) : 0;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SirenWailPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.source.pos());
                buf.writeNullable(payload.source.subLevelId(), (out, id) -> out.writeUUID(id));
                Vec3 at = payload.source.worldPosition();
                buf.writeDouble(at.x);
                buf.writeDouble(at.y);
                buf.writeDouble(at.z);
                buf.writeVarInt(payload.remainingTicks);
                buf.writeFloat(payload.voice);
            },
            buf -> new SirenWailPayload(
                    new SirenSource(
                            buf.readBlockPos(),
                            buf.readNullable(in -> in.readUUID()),
                            new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())),
                    buf.readVarInt(),
                    buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
