package com.cbc_more_content.effects;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.RagdollBlastCompat;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.event.WarnauticsBlockDetonateEvent;
import com.cbc_more_content.siren.BlastLog;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import rbasamoyai.createbigcannons.multiloader.IndexPlatform;
import rbasamoyai.createbigcannons.munitions.ProjectileDamageHooks;

/** Shared pressure, block callbacks, entity damage and FX for every Warnautics explosive charge. */
public final class BombExplosionHandler {
    private BombExplosionHandler() {}

    private enum FxStyle {
        NORMAL,
        COMPACT,
        BREACHING
    }

    public static void detonate(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower,
            BombSize size) {
        var target = SableDropCompat.resolveWorldBlastChecked(level, pos);
        level = target.level();
        pos = target.pos();
        detonateInternal(level, source, damageSource, pos, blockPower, entityPower, size, FxStyle.NORMAL);
    }

    /** Breaching charge: same blast everywhere, hull or ground. */
    public static void detonateBreachingCharge(
            ServerLevel level, DamageSource damageSource, Vec3 pos, float blockPower, float entityPower) {
        var target = SableDropCompat.resolveWorldBlastChecked(level, pos);
        level = target.level();
        pos = target.pos();
        detonateInternal(level, null, damageSource, pos, blockPower, entityPower, BombSize.MEDIUM, FxStyle.BREACHING);
    }

    /** Compact anti-vehicle mine profile: same impulse, far fewer visual emitters. */
    public static void detonateAntiTankMine(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower) {
        var target = SableDropCompat.resolveWorldBlastChecked(level, pos);
        level = target.level();
        pos = target.pos();
        detonateInternal(level, source, damageSource, pos, blockPower, entityPower, BombSize.LARGE, FxStyle.COMPACT);
    }

    /** Underwater charge using the same damage rules for terrain and sub-levels. */
    public static void detonateSeaMine(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower) {
        var target = SableDropCompat.resolveWorldBlastChecked(level, pos);
        level = target.level();
        pos = target.pos();
        detonateInternal(level, source, damageSource, pos, blockPower, entityPower, BombSize.SEA, FxStyle.NORMAL);
    }

    private static void detonateInternal(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower,
            BombSize size,
            FxStyle fxStyle) {
        // Sirens ask this rather than trying to watch for a blast that is already
        // over by the time they next look around. Placed here, after the hull
        // remapping, so a post is told where the blast actually landed.
        BlastLog.record(level, pos);
        BombBurstBudget.Snapshot budget = BombBurstBudget.begin(level);
        BombSize.BlastVolume volume = size.blastVolume();
        WarnauticsExplosion explosion =
                new WarnauticsExplosion(level, source, damageSource, pos, blockPower, entityPower, volume);

        if (IndexPlatform.onExplosionStart(level, explosion)) {
            return;
        }

        boolean canDamageTerrain = ProjectileDamageHooks.canDamageTerrain(level, BlockPos.containing(pos));
        explosion.setCanDamageTerrain(canDamageTerrain);
        // Send sound, hot particles and the screen flash before crater work. Large
        // CBC/Sable block scans can take noticeable time, but feedback must begin on
        // the collision tick rather than after terrain processing has finished.
        if (fxStyle == FxStyle.COMPACT) {
            BombBlastFx.playCompactMine(level, pos, blockPower, budget);
        } else if (fxStyle == FxStyle.BREACHING) {
            BombBlastFx.playBreachingCharge(level, pos, blockPower, budget);
        } else {
            BombBlastFx.play(
                    level,
                    pos,
                    size,
                    blockPower,
                    budget,
                    source instanceof com.cbc_more_content.munitions.SeaBombProjectile);
        }

        BombSympatheticDetonation.runBombBlast(() -> {
            CannonBlastFx.own(explosion::explode);
            NeoForge.EVENT_BUS.post(new WarnauticsBlockDetonateEvent(level, explosion, pos, size));
            BlastCover.beginDetonation();
            try {
                applyBlastToEntities(level, explosion, damageSource, pos, entityPower, size, budget.lod());
            } finally {
                BlastCover.endDetonation();
            }
            RagdollBlastCompat.onBombBlast(level, pos, entityPower, size);
            if (canDamageTerrain) {
                BlastDebris.fling(level, pos, explosion.destroyedBlocks());
            }
            explosion.finalizeExplosion(false);
        });
        sendBlastToNearbyPlayers(level, explosion, pos, size);
    }

    /** A burst already dropping detail does not need 27 rays per victim. */
    private static int coverSamples(BombBurstBudget.Lod lod) {
        return lod == BombBurstBudget.Lod.REDUCED ? 2 : 3;
    }

    private static float cbcBlastDamage(double distance, float entityPower) {
        float reach = entityPower * 2.0f;
        if (reach <= 0.0f) {
            return 0.0f;
        }
        double normalized = distance / reach;
        if (normalized >= 1.0D) {
            return 0.0f;
        }
        double pressure = 1.0D - normalized;
        return (float) ((pressure * pressure + pressure) / 2.0D * 7.0D * reach + 1.0D);
    }

    private static void sendBlastToNearbyPlayers(
            ServerLevel level, WarnauticsExplosion explosion, Vec3 pos, BombSize size) {
        double syncDistSqr =
                switch (size) {
                    case SMALL -> 180.0D * 180.0D;
                    case SEA -> 210.0D * 210.0D;
                    case MEDIUM -> 260.0D * 260.0D;
                    case LARGE -> 360.0D * 360.0D;
                    case MOAB -> 540.0D * 540.0D;
                };
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= syncDistSqr) {
                explosion.sendExplosionToClient(player);
            }
        }
    }

    /**
     * Blast damage and knockback for the world path.
     * <p>
     * Owned here rather than left to the loop inside {@code Explosion#explode}, which
     * gates entities on the <em>block</em> radius instead of the entity radius and so
     * could leave someone standing on a detonating bomb unhurt.
     */
    private static void applyBlastToEntities(
            ServerLevel level,
            WarnauticsExplosion explosion,
            DamageSource damageSource,
            Vec3 center,
            float entityPower,
            BombSize size,
            BombBurstBudget.Lod lod) {
        double radius = Math.max(entityPower * 2.0D, 5.0D);
        float base =
                switch (size) {
                    case SMALL -> 1.85f;
                    case SEA -> 2.85f;
                    case MEDIUM -> 2.9f;
                    case LARGE -> 4.2f;
                    case MOAB -> 6.2f;
                };
        boolean raycast = lod.useExplosionExposureRays();
        var removedBlocks = explosion.destroyedBlocks();
        LongSet destroyed = new LongOpenHashSet(removedBlocks.size());
        for (BlockPos pos : removedBlocks) {
            destroyed.add(pos.asLong());
        }

        for (Entity entity : explosion.affectedEntities()) {
            if (entity.ignoreExplosion(explosion) || entity.isSpectator()) {
                continue;
            }

            Vec3 body = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
            double dx = body.x - center.x;
            double dy = body.y - center.y;
            double dz = body.z - center.z;
            double distSqr = dx * dx + dy * dy + dz * dz;
            double dist = Math.sqrt(distSqr);

            if (distSqr < 1.0E-8D) {
                double yaw = level.random.nextDouble() * Math.PI * 2.0D;
                dx = Math.cos(yaw);
                dy = 0.0D;
                dz = Math.sin(yaw);
                dist = 0.25D;
            }

            if (dist > radius) {
                continue;
            }

            int samples = raycast ? Math.min(coverSamples(lod), BlastCover.samplesForDistance(dist, radius)) : 1;
            BlastCover.Result cover =
                    raycast ? BlastCover.evaluate(level, center, entity, destroyed, samples) : BlastCover.OPEN;
            double exposure = cover.transmission();
            double falloff = 1.0D - (dist / radius);

            if (entity instanceof ServerPlayer player && player.isAlive()) {
                ConcussionHandler.offer(player, entityPower, falloff, exposure, cover.hasLineOfSight());
            }

            float damage = (float) (cbcBlastDamage(dist, entityPower) * exposure);
            double inv = 1.0D / dist;
            double strength = Math.max(0.0D, falloff * exposure * base);
            if (strength < 0.05D) {
                if (!AirborneBlastResponse.apply(entity, explosion, falloff * exposure, Vec3.ZERO) && damage > .5f) {
                    entity.hurt(damageSource, damage);
                }
                continue;
            }

            Vec3 knock = new Vec3(
                    dx * inv * strength,
                    Mth.clamp(dy * inv * strength * 0.55D + strength * 0.45D, 0.25D, strength * 1.1D),
                    dz * inv * strength);

            if (AirborneBlastResponse.apply(entity, explosion, falloff * exposure, knock)) {
                continue;
            }
            if (damage > .5f) {
                entity.hurt(damageSource, damage);
            }
            if (entity instanceof ServerPlayer player) {
                explosion.getHitPlayers().put(player, knock);
                player.setDeltaMovement(player.getDeltaMovement().add(knock));
                player.hurtMarked = true;
            } else {
                entity.setDeltaMovement(entity.getDeltaMovement().add(knock));
                entity.hasImpulse = true;
                entity.hurtMarked = true;
            }
        }
    }
}
