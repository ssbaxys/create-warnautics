package com.cbc_more_content.effects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.RagdollBlastCompat;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.event.WarnauticsBlockDetonateEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.block_hit_effects.BlockImpactTransformationHandler;
import rbasamoyai.createbigcannons.multiloader.IndexPlatform;
import rbasamoyai.createbigcannons.munitions.ProjectileDamageHooks;
import rbasamoyai.createbigcannons.munitions.ShellExplosion;

/**
 * HE-style blast via CBC {@link ShellExplosion} crater + custom blast FX.
 * With Sable Destructive installed, blasts intersecting a moving sub-level use a
 * bounded entity/FX path. Its Detonate handler can otherwise request plot-offset
 * chunks at absurd coordinates and crash chunk generation.
 */
public final class BombExplosionHandler {
    /** No neighbor updates — critical when vaporizing thousands of blocks in one tick. */
    private static final int VAPORIZE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final float BOMB_SUB_LEVEL_RADIUS_SCALE = 0.5f;
    private static final float MEDIUM_BOMB_SUB_LEVEL_RADIUS_SCALE = BOMB_SUB_LEVEL_RADIUS_SCALE / 1.4f;
    private static final float LARGE_BOMB_SUB_LEVEL_RADIUS_SCALE = BOMB_SUB_LEVEL_RADIUS_SCALE / 1.4f;
    private static final float MINE_SUB_LEVEL_RADIUS_SCALE = 1.0f / 1.3f;

    private BombExplosionHandler() {
    }

    public static void detonate(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower,
            BombSize size) {
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, pos);
            level = target.level();
            pos = target.pos();
        }
        float subLevelRadiusScale = switch (size) {
            case MEDIUM -> MEDIUM_BOMB_SUB_LEVEL_RADIUS_SCALE;
            case LARGE -> LARGE_BOMB_SUB_LEVEL_RADIUS_SCALE;
            default -> BOMB_SUB_LEVEL_RADIUS_SCALE;
        };
        detonateInternal(level, source, damageSource, pos,
                blockPower, entityPower, size, false,
                1.0f, subLevelRadiusScale);
    }

    /**
     * Compact anti-vehicle mine profile: nearly the same physical impulse, fewer
     * visual emitters, and extra energy reserved for intersecting Sable sub-levels.
     */
    public static void detonateAntiTankMine(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower) {
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, pos);
            level = target.level();
            pos = target.pos();
        }
        detonateInternal(level, source, damageSource, pos, blockPower, entityPower,
                BombSize.LARGE, true, 1.55f, MINE_SUB_LEVEL_RADIUS_SCALE);
    }

    /**
     * Entity-only pressure wave for the antipersonnel mine.
     * <p>
     * Its CBC {@code ShrapnelExplosion} deliberately has zero block radius so it
     * cannot dig an HE crater. Vanilla's explosion loop unfortunately also uses that
     * block radius to collect entities, ignoring CBC's separate entity radius; the
     * result was no guaranteed damage at all, only whichever spawned fragments happened
     * to collide later. This pass restores the intended pressure damage without touching
     * terrain or replacing the fragment fan.
     */
    static void applySmallMinePressureDamage(
            ServerLevel level,
            DamageSource damageSource,
            Vec3 center,
            float entityPower) {
        if (level == null || damageSource == null || center == null
                || !Float.isFinite(entityPower) || entityPower <= 0.0f) {
            return;
        }

        double radius = Math.max(entityPower * 2.0D, 5.0D);
        AABB area = new AABB(center, center).inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, area, LivingEntity::isAlive)) {
            if (entity.isSpectator()) {
                continue;
            }

            Vec3 body = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
            double distance = body.distanceTo(center);
            if (distance > radius) {
                continue;
            }

            BlastCover.Result cover = BlastCover.evaluate(level, center, entity, Set.of());
            double falloff = 1.0D - distance / radius;
            if (entity instanceof ServerPlayer player && player.isAlive()) {
                ConcussionHandler.offer(player, entityPower, falloff,
                        cover.transmission(), cover.hasLineOfSight());
            }

            float damage = (float) (cbcBlastDamage(distance, entityPower) * cover.transmission());
            if (damage > 0.5f) {
                entity.hurt(damageSource, damage);
            }
        }
    }

    private static void detonateInternal(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower,
            BombSize size,
            boolean compactFx,
            float subLevelPowerScale,
            float subLevelRadiusScale) {
        BombBurstBudget.Snapshot budget = BombBurstBudget.begin(level);
        double shipReach = Math.max(blockPower * subLevelPowerScale * 2.0D, 12.0D);
        float terrainPower = blockPower * budget.lod().terrainPowerScale();

        // ShellExplosion.explode() -> Detonate -> Sable Destructive may scan plot
        // storage using global offsets. The same bug affects adjacent and direct hits.
        if (ModList.get().isLoaded("sable")
                && SableDropCompat.requiresSafeExplosion(level, pos, shipReach)) {
            // Same single guard as the world path: hull damage and entity damage on a
            // ship must not re-enter bomb cook-off either.
            BombSympatheticDetonation.runBombBlast(() ->
                    detonateNearShip(level, source, damageSource, pos, terrainPower,
                            terrainPower * subLevelPowerScale, subLevelRadiusScale,
                            entityPower, size, budget, compactFx));
            return;
        }

        ShellExplosion explosion = new TerrainShellExplosion(
                level,
                source,
                damageSource,
                pos.x,
                pos.y,
                pos.z,
                terrainPower,
                entityPower,
                false,
                CBCConfigs.server().munitions.damageRestriction.get().explosiveInteraction(),
                true);

        if (IndexPlatform.onExplosionStart(level, explosion)) {
            return;
        }

        boolean canDamageTerrain = ProjectileDamageHooks.canDamageTerrain(level, BlockPos.containing(pos));
        explosion.setCanDamageTerrain(canDamageTerrain);
        // Send sound, hot particles and the screen flash before crater work. Large
        // CBC/Sable block scans can take noticeable time, but feedback must begin on
        // the collision tick rather than after terrain processing has finished.
        if (compactFx) {
            BombBlastFx.playCompactMine(level, pos, blockPower, budget);
        } else {
            BombBlastFx.play(level, pos, size, blockPower, budget);
        }

        // The whole detonation runs inside one guard, not just explode(). Entity
        // damage and Block#wasExploded are dispatched from finalizeExplosion, so a
        // guard that ended at explode() left every flying bomb and every placed bomb
        // in the crater free to cook off — that is the rack chain reaction that made
        // multi-bomb aircraft unusable.
        BombSympatheticDetonation.runBombBlast(() -> {
            explosion.explode();

            fireBlockDetonateEvent(level, explosion, pos, size);
            applyBlastToEntities(level, explosion, damageSource, pos, entityPower, size, budget.lod());
            RagdollBlastCompat.onBombBlast(level, pos, entityPower, size);

            if (!canDamageTerrain) {
                explosion.clearToBlow();
            } else {
                capToBlow(level, explosion);
                vaporizeBlastCore(level, explosion, size, budget.lod().vaporizeChance());
            }

            explosion.finalizeExplosion(false);
            explosion.clearToBlow();
        });

        sendBlastToNearbyPlayers(level, explosion, pos, size);
    }

    /** Safe Sable blast: bounded terrain/sub-level destruction, entities and FX. */
    private static void detonateNearShip(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float worldBlockPower,
            float subLevelBlockPower,
            float subLevelRadiusScale,
            float entityPower,
            BombSize size,
            BombBurstBudget.Snapshot budget,
            boolean compactFx) {
        if (compactFx) {
            BombBlastFx.playCompactMine(level, pos, worldBlockPower, budget);
        } else {
            BombBlastFx.play(level, pos, size, worldBlockPower, budget);
        }
        boolean canDamageTerrain = ProjectileDamageHooks.canDamageTerrain(level, BlockPos.containing(pos));
        if (canDamageTerrain) {
            SableDropCompat.applySafeBlockExplosion(
                    level, pos, worldBlockPower, subLevelBlockPower, subLevelRadiusScale);
        }

        double radius = Math.max(entityPower * 2.0D, 5.0D);
        float base = switch (size) {
            case SMALL -> 1.85f;
            case SEA -> 2.85f;
            case MEDIUM -> 2.9f;
            case LARGE -> 4.2f;
        };

        AABB area = new AABB(pos, pos).inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            Vec3 body = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
            double dx = body.x - pos.x;
            double dy = body.y - pos.y;
            double dz = body.z - pos.z;
            double distSqr = dx * dx + dy * dy + dz * dz;
            double dist = Math.sqrt(Math.max(distSqr, 1.0E-8D));
            if (dist > radius) {
                continue;
            }

            double falloff = 1.0D - (dist / radius);
            // Hull plating shields the crew the same way world terrain does.
            BlastCover.Result cover = BlastCover.evaluate(level, pos, entity, Set.of());
            float damage = (float) (cbcBlastDamage(dist, entityPower) * cover.transmission());
            if (damage > 0.5f) {
                entity.hurt(damageSource, damage);
            }
            if (entity instanceof ServerPlayer player && player.isAlive()) {
                ConcussionHandler.offer(player, entityPower, falloff,
                        cover.transmission(), cover.hasLineOfSight());
            }

            double inv = 1.0D / dist;
            double strength = Math.max(0.0D, falloff * cover.transmission() * base);
            if (strength >= 0.05D) {
                Vec3 knock = new Vec3(
                        dx * inv * strength,
                        Mth.clamp(dy * inv * strength * 0.55D + strength * 0.45D, 0.25D, strength * 1.1D),
                        dz * inv * strength);
                entity.setDeltaMovement(entity.getDeltaMovement().add(knock));
                entity.hasImpulse = true;
                entity.hurtMarked = true;
            }
        }

        RagdollBlastCompat.onBombBlast(level, pos, entityPower, size);
    }
    
    private static void fireBlockDetonateEvent(
            ServerLevel level,
            ShellExplosion explosion,
            Vec3 pos,
            BombSize size) {
        NeoForge.EVENT_BUS.post(new WarnauticsBlockDetonateEvent(level, explosion, pos, size));
    }

    /**
     * Every changed block becomes a client section re-mesh. An unbounded large-bomb
     * crater could hand a client tens of thousands of them in one tick, which is the
     * "very laggy, then instantly crashed" report: the renderer, not the server, is
     * what runs out of headroom. Keep the nearest blocks so the crater still reads
     * as a crater and only the far, thin rim is dropped.
     */
    private static void capToBlow(ServerLevel level, ShellExplosion explosion) {
        List<BlockPos> toBlow = explosion.getToBlow();
        // Vanilla rays also collect air positions. Letting those consume the cap can
        // discard real blocks and leave a large visual blast with no crater.
        toBlow.removeIf(pos -> {
            try {
                BlockState state = level.getBlockState(pos);
                return state.isAir() || state.getBlock() instanceof LiquidBlock;
            } catch (Throwable ignored) {
                return true;
            }
        });
        int cap = WarnauticsConfig.maxBlocksPerDetonation();
        if (toBlow.size() <= cap) {
            return;
        }
        Vec3 center = explosion.center();
        List<BlockPos> kept = new ArrayList<>(toBlow);
        kept.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(center.x, center.y, center.z)));
        toBlow.clear();
        toBlow.addAll(kept.subList(0, cap));
    }

    /**
     * The blast damage curve CBC applies to every other munition, reproduced for the
     * paths that bypass {@code Explosion#explode}.
     * <p>
     * The ship-adjacent path used to roll its own {@code power * falloff * 1.35}, which
     * for a small bomb at point-blank range worked out around a tenth of what the same
     * bomb does away from a ship. On a Sable server nearly every detonation takes that
     * path, so bombs read as doing no damage at all.
     * <p>
     * Mirrors {@code CustomExplosion.CustomDamageCalculator#getEntityDamageAmount}.
     */
    private static float cbcBlastDamage(double distance, float entityPower) {
        float reach = entityPower * 2.0f;
        if (reach <= 0.0f) {
            return 0.0f;
        }
        double normalized = distance / reach;
        if (normalized >= 1.0D) {
            return 0.0f;
        }
        // Cover is sampled separately by BlastCover and applied exactly once by the
        // caller. Calling Explosion#getSeenPercent here as well made an impact point
        // just inside a surface report zero exposure for every body sample, which
        // reduced even a point-blank large bomb to zero damage.
        double pressure = 1.0D - normalized;
        return (float) ((pressure * pressure + pressure) / 2.0D * 7.0D * reach + 1.0D);
    }

    private static void sendBlastToNearbyPlayers(ServerLevel level, ShellExplosion explosion, Vec3 pos, BombSize size) {
        double syncDistSqr = switch (size) {
            case SMALL -> 180.0D * 180.0D;
            case SEA -> 210.0D * 210.0D;
            case MEDIUM -> 260.0D * 260.0D;
            case LARGE -> 360.0D * 360.0D;
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
     * Warnautics owns this pass rather than leaving it to the vanilla loop inside
     * {@code Explosion#explode}. That loop selects and gates entities on the explosion's
     * <em>block</em> radius, not the entity radius, and then routes the amount through
     * CBC's damage calculator — a combination that could leave someone standing on top
     * of a detonating bomb entirely unhurt. Doing it here also makes the world path and
     * the Sable path apply the same numbers.
     */
    private static void applyBlastToEntities(
            ServerLevel level,
            ShellExplosion explosion,
            DamageSource damageSource,
            Vec3 center,
            float entityPower,
            BombSize size,
            BombBurstBudget.Lod lod) {
        double radius = Math.max(entityPower * 2.0D, 5.0D);
        float base = switch (size) {
            case SMALL -> 1.85f;
            case SEA -> 2.85f;
            case MEDIUM -> 2.9f;
            case LARGE -> 4.2f;
        };
        boolean raycast = lod.useExplosionExposureRays();
        // Blocks this blast is about to remove must not shield anyone: the cover that
        // fails absorbs its share and lets the rest through. applyBlastToEntities runs
        // before finalizeExplosion, so they are still standing at this point and have to
        // be excluded explicitly.
        Set<BlockPos> destroyed = new HashSet<>(explosion.getToBlow());

        AABB area = new AABB(center, center).inflate(radius);
        for (Entity entity : level.getEntities((Entity) null, area, Entity::isAlive)) {
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

            // Cover now attenuates by what it is made of, not merely by whether a line
            // is clear. A dirt berm barely helps; a reinforced wall that survives the
            // blast stops it outright.
            BlastCover.Result cover = raycast
                    ? BlastCover.evaluate(level, center, entity, destroyed)
                    : BlastCover.OPEN;
            double exposure = cover.transmission();
            double falloff = 1.0D - (dist / radius);

            // Offered before the damage is applied, and gated on the entity being alive
            // beforehand. Checking afterwards meant a point-blank hit killed the player
            // first, so the one blast that should have rung hardest sent nothing at all.
            if (entity instanceof ServerPlayer player && player.isAlive()) {
                // Pressure finds its way around cover even when the blast is not in
                // sight, so the ringing is keyed to distance alone while the visual
                // shock needs an actual line to the fireball.
                ConcussionHandler.offer(player, entityPower, falloff, exposure, cover.hasLineOfSight());
            }

            float damage = (float) (cbcBlastDamage(dist, entityPower) * exposure);
            if (damage > 0.5f) {
                entity.hurt(damageSource, damage);
            }

            double inv = 1.0D / dist;
            double strength = Math.max(0.0D, falloff * exposure * base);
            if (strength < 0.05D) {
                continue;
            }

            Vec3 knock = new Vec3(
                    dx * inv * strength,
                    Mth.clamp(dy * inv * strength * 0.55D + strength * 0.45D, 0.25D, strength * 1.1D),
                    dz * inv * strength);

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

    private static void vaporizeBlastCore(
            ServerLevel level,
            ShellExplosion explosion,
            BombSize size,
            float vaporizeChance) {
        List<BlockPos> toBlow = explosion.getToBlow();
        if (toBlow.isEmpty()) {
            return;
        }

        boolean sable = ModList.get().isLoaded("sable");
        if (sable && SableDropCompat.shouldSkipVaporize(level, explosion.center(), explosion.radius())) {
            return;
        }

        double coreScale = switch (size) {
            case SMALL -> 0.28D;
            case SEA -> 0.31D;
            case MEDIUM -> 0.37D;
            case LARGE -> 0.43D;
        };
        double coreRadius = Math.max(1.35D, explosion.radius() * coreScale);
        double coreRadiusSqr = coreRadius * coreRadius;
        Vec3 center = explosion.center();

        // Keep the ray-shaped outer crater for CBC/vanilla destruction and drops.
        // Only the superheated inner core is vaporized without drops.
        List<BlockPos> remaining = new ArrayList<>(Math.max(4, toBlow.size() / 2));
        for (BlockPos blockPos : toBlow) {
            if (sable && SableDropCompat.isInsideSubLevel(level, blockPos)) {
                remaining.add(blockPos.immutable());
                continue;
            }
            double distanceSqr = blockPos.distToCenterSqr(center.x, center.y, center.z);
            if (distanceSqr > coreRadiusSqr) {
                remaining.add(blockPos.immutable());
                continue;
            }

            double heat = 1.0D - Math.sqrt(distanceSqr) / coreRadius;
            float localChance = vaporizeChance * (0.35f + 0.65f * (float) heat);
            if (level.random.nextFloat() < localChance) {
                BlockState state = level.getBlockState(blockPos);
                if (!state.isAir()) {
                    level.setBlock(blockPos,
                            BlastRubble.replacementFor(level, blockPos, state), VAPORIZE_FLAGS);
                }
            } else {
                remaining.add(blockPos.immutable());
            }
        }
        toBlow.clear();
        toBlow.addAll(remaining);
    }

    /**
     * CBC terrain transformations with its client-side duplicate plume disabled.
     * The packet remains SHELL_NO_EFFECTS because Warnautics supplies its own
     * scalable blast particles and flash.
     */
    private static final class TerrainShellExplosion extends ShellExplosion {
        private final Set<BlockPos> transformed = new HashSet<>();

        TerrainShellExplosion(
                Level level,
                @Nullable Entity source,
                @Nullable DamageSource damageSource,
                double x,
                double y,
                double z,
                float blockRadius,
                float entityRadius,
                boolean fire,
                BlockInteraction interaction,
                boolean noEffects) {
            super(level, source, damageSource, x, y, z,
                    blockRadius, entityRadius, fire, interaction, noEffects);
        }

        @Override
        public void editBlock(Level level, BlockPos pos, BlockState state, FluidState fluid, float power) {
            if (!CBCConfigs.server().munitions.projectilesChangeSurroundings.get()
                    || !this.transformed.add(pos.immutable())) {
                return;
            }
            BlockState changed = BlockImpactTransformationHandler.transformBlock(state);
            if (!changed.equals(state)) {
                level.setBlock(pos, changed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }
}
