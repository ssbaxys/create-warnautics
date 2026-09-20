package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, CBCMoreContent.MOD_ID);

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> BOMB_VEST = ARMOR_MATERIALS.register(
            "bomb_vest",
            () -> new ArmorMaterial(
                    Util.make(new EnumMap<>(ArmorItem.Type.class), defence -> {
                        // Leather-tier: the vest is padding around a charge, not a plate.
                        defence.put(ArmorItem.Type.BOOTS, 1);
                        defence.put(ArmorItem.Type.LEGGINGS, 2);
                        defence.put(ArmorItem.Type.CHESTPLATE, 3);
                        defence.put(ArmorItem.Type.HELMET, 1);
                        defence.put(ArmorItem.Type.BODY, 3);
                    }),
                    15,
                    SoundEvents.ARMOR_EQUIP_LEATHER,
                    () -> Ingredient.of(net.minecraft.world.item.Items.STRING),
                    List.of(), // no outer layer texture; the vest draws itself, see BombVestLayer
                    0.0f,
                    0.0f));

    private ModArmorMaterials() {}
}
