package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.loot.AddDiscModifier;
import com.mojang.serialization.MapCodec;

import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModLootModifiers {
    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                    CBCMoreContent.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>,
            MapCodec<AddDiscModifier>> ADD_DISC =
            SERIALIZERS.register("add_disc", () -> AddDiscModifier.CODEC);

    private ModLootModifiers() {
    }
}
