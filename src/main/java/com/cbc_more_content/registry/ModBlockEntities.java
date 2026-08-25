package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.block.SirenBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CBCMoreContent.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<C4BlockEntity>> C4 =
            BLOCK_ENTITIES.register("c4", () -> BlockEntityType.Builder
                    .of(C4BlockEntity::new, ModBlocks.C4.get())
                    .build(null));

    /** Guidance package on the middle cell of a placed missile. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CruiseMissileBlockEntity>>
            CRUISE_MISSILE = BLOCK_ENTITIES.register("cruise_missile", () -> BlockEntityType.Builder
                    .of(CruiseMissileBlockEntity::new, ModBlocks.CRUISE_MISSILE.get())
                    .build(null));

    /** What an air-raid post is watching for, and how long it has left to wail. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SirenBlockEntity>> SIREN =
            BLOCK_ENTITIES.register("siren", () -> BlockEntityType.Builder
                    .of(SirenBlockEntity::new, ModBlocks.SIREN.get())
                    .build(null));

    private ModBlockEntities() {
    }
}
