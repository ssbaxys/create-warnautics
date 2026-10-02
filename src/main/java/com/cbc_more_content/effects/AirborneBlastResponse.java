package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.munitions.C4Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.munitions.DropBombProjectile;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Pressure deflects airborne munitions; a nearby, exposed charge can damage an armed fuze. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class AirborneBlastResponse {
    private static final Set<UUID> PRIMED = new HashSet<>();

    private AirborneBlastResponse() {}

    private static boolean munition(Entity entity) {
        return entity instanceof CruiseMissileProjectile
                || entity instanceof Aim9Projectile
                || entity instanceof AbstractCannonProjectile
                || entity instanceof C4Projectile;
    }

    public static boolean apply(Entity entity, Explosion explosion, double pressure, Vec3 impulse) {
        if (!munition(entity) || !(entity.level() instanceof ServerLevel level) || !entity.isAlive()) {
            return false;
        }
        double mass = entity instanceof DropBombProjectile bomb
                ? Math.max(1, bomb.bombSize().entitySize * 3)
                : entity instanceof CruiseMissileProjectile ? 1.8 : entity instanceof Aim9Projectile ? 1.25 : 2;
        Vec3 kick = impulse.scale(1 / mass);
        if (entity instanceof CruiseMissileProjectile missile) {
            missile.applyBlastImpulse(kick);
        } else if (entity instanceof Aim9Projectile missile) {
            missile.applyBlastImpulse(kick);
        } else {
            entity.setDeltaMovement(entity.getDeltaMovement().add(kick));
            entity.hasImpulse = true;
            entity.hurtMarked = true;
        }
        boolean armed = entity instanceof CruiseMissileProjectile cruise && !cruise.isEjecting()
                || entity instanceof Aim9Projectile aim9 && aim9.isPowered()
                || entity instanceof DropBombProjectile && entity.tickCount >= 6
                || entity instanceof C4Projectile charge && charge.isArmed();
        if (pressure >= .68
                && armed
                && BombSympatheticDetonation.allowsCookoffFrom(explosion)
                && PRIMED.add(entity.getUUID())) {
            // Do not recursively detonate a salvo inside the initiating explosion's entity loop.
            BombSympatheticDetonation.scheduleDestroyedCharge(level, entity.position(), () -> {
                PRIMED.remove(entity.getUUID());
                if (!entity.isAlive()) {
                    return;
                }
                if (entity instanceof DropBombProjectile bomb) {
                    bomb.sympatheticDetonate();
                } else if (entity instanceof C4Projectile charge) {
                    charge.sympatheticDetonate();
                } else {
                    entity.hurt(level.damageSources().explosion(explosion.getDirectSourceEntity(), null), 100);
                }
            });
        }
        return true;
    }

    @SubscribeEvent
    public static void externalExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getExplosion() instanceof WarnauticsExplosion) {
            return;
        }
        var blast = event.getExplosion();
        double radius = Math.max(1, blast.radius() * 2);
        // Vanilla and CBC otherwise call hurt on every nearby projectile, regardless of pressure.
        event.getAffectedEntities().removeIf(entity -> {
            if (!munition(entity)) {
                return false;
            }
            Vec3 offset = entity.getBoundingBox().getCenter().subtract(blast.center());
            double range = offset.length();
            double pressure = Math.max(0, 1 - range / radius) * Explosion.getSeenPercent(blast.center(), entity);
            Vec3 direction = range < .001 ? new Vec3(0, 1, 0) : offset.scale(1 / range);
            apply(entity, blast, pressure, direction.scale(pressure * Math.min(4, blast.radius() * .5)));
            return true;
        });
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent event) {
        PRIMED.clear();
    }
}
