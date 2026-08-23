package com.cbc_more_content.compat;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Soft compat for sable-player-ragdoll and ragdoll-reactions.
 * <p>
 * With {@code ragdoll_reactions} present our {@code ShellExplosion} already fires
 * {@code ExplosionEvent.Detonate} and reactions tumble everyone nearby, so we must not
 * double-launch. With only the ragdoll API, launch them here.
 */
public final class RagdollBlastCompat {
    private static final String API_MOD = "sable_player_ragdoll";
    private static final String REACTIONS_MOD = "ragdoll_reactions";

    private static final boolean API_LOADED = ModList.get().isLoaded(API_MOD);
    private static final boolean REACTIONS_LOADED = ModList.get().isLoaded(REACTIONS_MOD);

    @Nullable
    private static Method launchPlayer;
    @Nullable
    private static Method isPlayerRagdolled;
    @Nullable
    private static Method launchMob;
    @Nullable
    private static Method isMobRagdolled;
    private static boolean resolved;
    private static boolean available;

    private RagdollBlastCompat() {
    }

    /**
     * Call after bomb knockback is computed. No-op if ragdoll mods absent or reactions
     * already handled the Detonate pass.
     */
    public static void onBombBlast(ServerLevel level, Vec3 center, float entityPower, BombSize size) {
        if (!API_LOADED) {
            return;
        }
        // reactions already tumble from ShellExplosion Detonate (CBC package check).
        if (REACTIONS_LOADED) {
            return;
        }
        if (!resolveApi()) {
            return;
        }

        double radius = Math.max(entityPower * 2.0D, 5.0D);
        float launchMul = switch (size) {
            case SMALL -> 12.0f;
            case SEA -> 14.0f;
            case MEDIUM -> 15.0f;
            case LARGE -> 18.0f;
        };

        AABB area = new AABB(center, center).inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            Vec3 body = entity.getBoundingBox().getCenter();
            double distSqr = body.distanceToSqr(center);
            if (distSqr > radius * radius) {
                continue;
            }

            Vec3 direction = body.subtract(center);
            if (direction.lengthSqr() < 1.0E-8D) {
                direction = new Vec3(0.0D, 1.0D, 0.0D);
            } else {
                direction = direction.normalize();
            }

            double dist = Math.sqrt(distSqr);
            double falloff = Math.max(0.25D, 1.0D - dist / radius);
            // RagdollAPI expects m/s (same convention as ragdoll-reactions cannon path).
            double speed = entityPower * launchMul * falloff;
            Vec3 velocity = direction.scale(speed);

            try {
                if (entity instanceof ServerPlayer player) {
                    if (Boolean.TRUE.equals(isPlayerRagdolled.invoke(null, player))) {
                        continue;
                    }
                    launchPlayer.invoke(null, player, velocity);
                } else if (entity instanceof Mob) {
                    if (Boolean.TRUE.equals(isMobRagdolled.invoke(null, entity))) {
                        continue;
                    }
                    launchMob.invoke(null, level, entity, velocity);
                }
            } catch (Throwable t) {
                CBCMoreContent.LOGGER.debug("Ragdoll launch failed for {}: {}", entity, t.toString());
            }
        }
    }

    private static boolean resolveApi() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName("dev.leo.sableplayerragdoll.api.RagdollAPI");
            launchPlayer = api.getMethod("launch", ServerPlayer.class, Vec3.class);
            isPlayerRagdolled = api.getMethod("isRagdolled", ServerPlayer.class);
            launchMob = api.getMethod("launchMob", ServerLevel.class, LivingEntity.class, Vec3.class);
            isMobRagdolled = api.getMethod("isMobRagdolled", LivingEntity.class);
            available = true;
            CBCMoreContent.LOGGER.info("Sable Player Ragdoll API hooked (reactions={})", REACTIONS_LOADED);
        } catch (Throwable t) {
            available = false;
            CBCMoreContent.LOGGER.warn("sable_player_ragdoll present but RagdollAPI unavailable: {}", t.toString());
        }
        return available;
    }
}
