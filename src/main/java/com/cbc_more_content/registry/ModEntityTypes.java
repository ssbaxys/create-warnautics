package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.munitions.SeaBombProjectile;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.config.MunitionPropertiesHandler;

public final class ModEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CBCMoreContent.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<DropBombProjectile>> SMALL_BOMB =
            dropBomb("small_bomb", BombSize.SMALL);

    public static final DeferredHolder<EntityType<?>, EntityType<SeaBombProjectile>> SEA_BOMB =
            ENTITY_TYPES.register("sea_bomb", () -> EntityType.Builder
                    .<SeaBombProjectile>of(SeaBombProjectile::new, MobCategory.MISC)
                    .sized(BombSize.SEA.entitySize, BombSize.SEA.entitySize)
                    .fireImmune()
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(false)
                    .build("sea_bomb"));

    public static final DeferredHolder<EntityType<?>, EntityType<DropBombProjectile>> MEDIUM_BOMB =
            dropBomb("medium_bomb", BombSize.MEDIUM);

    public static final DeferredHolder<EntityType<?>, EntityType<DropBombProjectile>> LARGE_BOMB =
            dropBomb("large_bomb", BombSize.LARGE);

    private static DeferredHolder<EntityType<?>, EntityType<DropBombProjectile>> dropBomb(String id, BombSize size) {
        return ENTITY_TYPES.register(id, () -> EntityType.Builder
                .<DropBombProjectile>of(DropBombProjectile::new, MobCategory.MISC)
                .sized(size.entitySize, size.entitySize)
                .fireImmune()
                .clientTrackingRange(16)
                .updateInterval(1)
                .setShouldReceiveVelocityUpdates(false)
                .build(id));
    }

    private ModEntityTypes() {
    }

    public static void registerMunitionHandlers() {
        MunitionPropertiesHandler.registerProjectileHandler(
                SMALL_BOMB.get(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                SEA_BOMB.get(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                MEDIUM_BOMB.get(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                LARGE_BOMB.get(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
    }
}
