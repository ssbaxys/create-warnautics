package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import javax.annotation.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** One bounded water event, rather than a packet for every droplet. */
public record WaterBlastPayload(Vec3 water, @Nullable Vec3 surface, float power) implements CustomPacketPayload {
    public static final Type<WaterBlastPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "water_blast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WaterBlastPayload> STREAM_CODEC =
            StreamCodec.of(WaterBlastPayload::write, WaterBlastPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, WaterBlastPayload payload) {
        buf.writeVec3(payload.water);
        buf.writeBoolean(payload.surface != null);
        if (payload.surface != null) {
            buf.writeVec3(payload.surface);
        }
        buf.writeFloat(payload.power);
    }

    private static WaterBlastPayload read(RegistryFriendlyByteBuf buf) {
        Vec3 water = buf.readVec3();
        Vec3 surface = buf.readBoolean() ? buf.readVec3() : null;
        return new WaterBlastPayload(water, surface, buf.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
