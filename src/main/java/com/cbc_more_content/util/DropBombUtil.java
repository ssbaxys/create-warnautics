package com.cbc_more_content.util;

import javax.annotation.Nullable;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.registry.ModEntityTypes;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import rbasamoyai.createbigcannons.index.CBCItems;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBigCannonProjectile;

/**
 * Spawns drop bombs. From a Sable plot, maps pose + ship inertia into the parent world first
 * (see Sable wiki entity kicking) so we do not kick mid-physics-tick.
 */
public final class DropBombUtil {
    private DropBombUtil() {
    }

    public static DropBombProjectile spawn(BombSize size, ServerLevel level, Vec3 localPos, Vec3 localVel, @Nullable Entity owner) {
        return spawn(size, level, localPos, localVel, null, owner);
    }

    public static DropBombProjectile spawn(
            BombSize size,
            ServerLevel level,
            Vec3 localPos,
            Vec3 localVel,
            @Nullable Vec3 localOrientation,
            @Nullable Entity owner) {
        ServerLevel spawnLevel = level;
        Vec3 pos = localPos;
        Vec3 vel = localVel;
        Vec3 orientation = localOrientation;

        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.LaunchFrame frame = SableDropCompat.resolveLaunch(
                    level, localPos, localVel, localOrientation);
            spawnLevel = frame.level();
            pos = frame.pos();
            vel = frame.vel();
            orientation = frame.orientation();
        }

        DropBombProjectile bomb = createBomb(size, spawnLevel);
        configureAndAdd(spawnLevel, bomb, pos, vel, orientation, owner);
        return bomb;
    }

    /**
     * Detonates a Sable physics-block bomb through its real projectile type without
     * briefly adding a duplicate entity to the world. This preserves CBC datapack
     * powers and sea-bomb underwater behaviour used by redstone-released bombs.
     */
    public static void detonateAsReleasedProjectile(
            BombSize size,
            ServerLevel level,
            Vec3 worldPos) {
        DropBombProjectile bomb = createBomb(size, level);
        configure(bomb, worldPos, Vec3.ZERO, new Vec3(0.0D, -1.0D, 0.0D), null);
        bomb.detonateFromPhysicalImpact(worldPos);
        bomb.discard();
    }

    private static DropBombProjectile createBomb(BombSize size, ServerLevel level) {
        DropBombProjectile bomb;
        if (size == BombSize.SEA) {
            bomb = ModEntityTypes.SEA_BOMB.get().create(level);
        } else {
            EntityType<DropBombProjectile> type = switch (size) {
                case SMALL -> ModEntityTypes.SMALL_BOMB.get();
                case MEDIUM -> ModEntityTypes.MEDIUM_BOMB.get();
                case LARGE -> ModEntityTypes.LARGE_BOMB.get();
                case SEA -> throw new IllegalStateException("unreachable");
            };
            bomb = type.create(level);
        }
        if (bomb == null) {
            throw new IllegalStateException("Failed to create " + size.name().toLowerCase() + "_bomb entity");
        }
        return bomb;
    }

    private static void configureAndAdd(
            ServerLevel spawnLevel,
            FuzedBigCannonProjectile bomb,
            Vec3 pos,
            Vec3 vel,
            @Nullable Vec3 launchOrientation,
            @Nullable Entity owner) {
        configure(bomb, pos, vel, launchOrientation, owner);
        spawnLevel.addFreshEntity(bomb);
        // Do not call kickIfInSubLevel — already in parent world when launched from a plot.
    }

    private static void configure(
            FuzedBigCannonProjectile bomb,
            Vec3 pos,
            Vec3 vel,
            @Nullable Vec3 launchOrientation,
            @Nullable Entity owner) {
        bomb.setPos(pos);
        bomb.setDeltaMovement(vel);
        bomb.setOwner(owner);

        Vec3 orientation = launchOrientation != null && launchOrientation.lengthSqr() > 1.0E-6D
                ? launchOrientation.normalize()
                : vel.lengthSqr() > 1.0E-6D
                        ? vel.normalize()
                        : new Vec3(0.0D, -1.0D, 0.0D);
        bomb.setOrientation(orientation);
        bomb.setChargePower(1.0f);
        bomb.setFuze(new ItemStack(CBCItems.IMPACT_FUZE.get()));

        // Both sets of previous-tick fields are seeded from where the bomb was just put,
        // so its first tick does not interpolate in from wherever the entity was created.
        //
        // Through the vanilla method rather than by assignment. xOld and its pair are
        // widened for the development workspace and are private in a shipped game, so
        // writing them compiled cleanly here and then threw IllegalAccessError on the
        // first bomb any real player released — see issue #2.
        bomb.setOldPosAndRot();
    }
}
