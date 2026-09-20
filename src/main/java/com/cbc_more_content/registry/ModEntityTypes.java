package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.entity.TripwireEntity;
import com.cbc_more_content.munitions.C4Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
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

    public static final DeferredHolder<EntityType<?>, EntityType<SeaBombProjectile>> SEA_BOMB = ENTITY_TYPES.register(
            "sea_bomb", () -> EntityType.Builder.<SeaBombProjectile>of(SeaBombProjectile::new, MobCategory.MISC)
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

    public static final DeferredHolder<EntityType<?>, EntityType<DropBombProjectile>> MOAB =
            dropBomb("moab", BombSize.MOAB);

    public static final DeferredHolder<EntityType<?>, EntityType<CruiseMissileProjectile>> CRUISE_MISSILE =
            ENTITY_TYPES.register("cruise_missile", () -> EntityType.Builder.<CruiseMissileProjectile>of(
                            CruiseMissileProjectile::new, MobCategory.MISC)
                    // Three blocks long, so it needs a hitbox and a tracking range to match.
                    .sized(1.0f, 0.8f)
                    .fireImmune()
                    // The exhaust is drawn client-side, so it only exists while the
                    // client is tracking the entity. A short range made a missile that
                    // outran its own tracking distance lose its plume mid-flight.
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build("cruise_missile"));

    public static final DeferredHolder<EntityType<?>, EntityType<C4Projectile>> C4 =
            ENTITY_TYPES.register("c4", () -> EntityType.Builder.<C4Projectile>of(C4Projectile::new, MobCategory.MISC)
                    .sized(0.35f, 0.35f)
                    .clientTrackingRange(8)
                    // A live charge is worth watching fall, so it is synced every tick
                    // instead of teleporting between ten-tick snapshots.
                    .updateInterval(1)
                    .build("c4"));

    /** Strung between two posts and then left alone, so it is tracked far and rarely updated. */
    public static final DeferredHolder<EntityType<?>, EntityType<TripwireEntity>> TRIPWIRE = ENTITY_TYPES.register(
            "tripwire", () -> EntityType.Builder.<TripwireEntity>of(TripwireEntity::new, MobCategory.MISC)
                    .sized(0.2f, 0.2f)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("tripwire"));

    /** A bounding charge in the air, for the second or so between the pop and the burst. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.cbc_more_content.entity.BoundingMineEntity>>
            BOUNDING_MINE = ENTITY_TYPES.register(
                    "bounding_mine", () -> EntityType.Builder.<com.cbc_more_content.entity.BoundingMineEntity>of(
                                    com.cbc_more_content.entity.BoundingMineEntity::new, MobCategory.MISC)
                            .sized(0.3f, 0.4f)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build("bounding_mine"));

    /** A chunk thrown clear of a blast; short-lived, so it never needs to travel far. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.cbc_more_content.entity.BlastDebrisEntity>>
            BLAST_DEBRIS = ENTITY_TYPES.register(
                    "blast_debris", () -> EntityType.Builder.<com.cbc_more_content.entity.BlastDebrisEntity>of(
                                    com.cbc_more_content.entity.BlastDebrisEntity::new, MobCategory.MISC)
                            .sized(0.4f, 0.4f)
                            .clientTrackingRange(6)
                            .updateInterval(2)
                            .build("blast_debris"));

    private static DeferredHolder<EntityType<?>, EntityType<DropBombProjectile>> dropBomb(String id, BombSize size) {
        return ENTITY_TYPES.register(id, () -> EntityType.Builder.<DropBombProjectile>of(
                        DropBombProjectile::new, MobCategory.MISC)
                .sized(size == BombSize.MOAB ? 1.6f : size.entitySize, size == BombSize.MOAB ? 2.4f : size.entitySize)
                .fireImmune()
                .clientTrackingRange(16)
                .updateInterval(1)
                .setShouldReceiveVelocityUpdates(false)
                .build(id));
    }

    private ModEntityTypes() {}

    public static void registerMunitionHandlers() {
        MunitionPropertiesHandler.registerProjectileHandler(
                SMALL_BOMB.get(), CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                SEA_BOMB.get(), CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                MEDIUM_BOMB.get(), CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                LARGE_BOMB.get(), CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(
                MOAB.get(), CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE);
    }
}
