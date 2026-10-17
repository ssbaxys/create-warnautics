package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.ui.UiTone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-bound: one Warnautics notice, shown on the notice strip above the hotbar. */
public record NoticePayload(UiTone tone, Component message) implements CustomPacketPayload {

    public static final Type<NoticePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoticePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(UiTone::byId, UiTone::ordinal),
            NoticePayload::tone,
            ComponentSerialization.TRUSTED_STREAM_CODEC,
            NoticePayload::message,
            NoticePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
