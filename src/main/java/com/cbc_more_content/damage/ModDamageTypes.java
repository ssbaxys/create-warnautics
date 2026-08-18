package com.cbc_more_content.damage;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

public final class ModDamageTypes {
    public static final ResourceKey<DamageType> AERIAL_BOMBING = key("aerial_bombing");
    public static final ResourceKey<DamageType> LAND_MINE = key("land_mine");

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, path));
    }

    private ModDamageTypes() {
    }
}
