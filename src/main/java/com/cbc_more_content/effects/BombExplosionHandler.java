package com.cbc_more_content.effects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

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
 * <p>
 * There is one path for everything. Sable patches {@code ServerLevel#explode} and
 * {@code Explosion#explode} itself — it gathers sub-level blocks into the same toBlow set
 * and redirects explosion resistance to read from the plot — so a hull breaks here exactly
 * as ground does. The bounded stand-in this used to take for blasts near a sub-level was
 * working around Sable Destructive, and it did so by skipping {@code explode()}, which
 * also skipped the handling Sable does natively.
 */
public final class BombExplosionHandler {
    /** No neighbor updates — critical when vaporizing thousands of blocks in one tick. */
    private static final int VAPORIZE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

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
        detonateInternal(level, source, damageSource, pos, blockPower, entityPower, size, false);
    }

    /** Breaching charge: same blast everywhere, hull or ground. */
    public static void detonateBreachingCharge(
            ServerLevel level,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower) {
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, pos);
            level = target.level();
            pos = target.pos();
        }
        detonateInternal(level, null, damageSource, pos, blockPower, entityPower,
                BombSize.MEDIUM, false);
    }

    /** Compact anti-vehicle mine profile: same impulse, far fewer visual emitters. */
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
                BombSize.LARGE, true);
        // Wider and harder than the infantry charge: an anti-vehicle mine leaves the
        // ground around its crater visibly torn up, not just the hole itself.
        BlastScorch.scuff(level, pos, Math.max(6.0D, blockPower * 1.6D), 1.0f);
    }

        private static void detonateInternal(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float blockPower,
            float entityPower,
            BombSize size,
            boolean compactFx) {
        // Sirens ask this rather than trying to watch for a blast that is already
        // over by the time they next look around. Placed here, after the hull
        // remapping, so a post is told where the blast actually landed.
        com.cbc_more_content.siren.BlastLog.record(level, pos);
        BombBurstBudget.Snapshot budget = BombBurstBudget.begin(level);
        float terrainPower = blockPower * budget.lod().terrainPowerScale();

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
            // Marked as ours while it runs. This is a Big Cannons ShellExplosion, and
            // CannonBlastFx watches for exactly those — without this every bomb in the
            // mod would be dressed twice, once here and once by that listener.
            CannonBlastFx.own(explosion::explode);

            // Fired while getToBlow() is still intact so add-ons can veto individual
            // positions before the cap, the core vaporization and finalizeExplosion
            // consume it. It runs ahead of the entity pass because that pass treats
            // doomed blocks as no cover — a vetoed block has to keep shielding.
            NeoForge.EVENT_BUS.post(new WarnauticsBlockDetonateEvent(level, explosion, pos, size));
            applyBlastToEntities(level, explosion, damageSource, pos, entityPower, size, budget.lod());
            RagdollBlastCompat.onBombBlast(level, pos, entityPower, size);

            // Read while the crater is still standing: capToBlow and vaporizeBlastCore
            // are about to break or replace these positions, and the debris has to see
            // the real block that was there, not what it turned into.
            if (canDamageTerrain) {
                BlastDebris.fling(level, pos, explosion.getToBlow());
            }

            if (!canDamageTerrain) {
                explosion.clearToBlow();
            } else {
                capToBlow(level, explosion);
                vaporizeBlastCore(level, explosion, size, budget.lod().vaporizeChance());
                // Past the crater rim the ground is churned rather than removed, so a
                // bomb leaves a scar rather than a clean hole in an untouched field.
                BlastScorch.scuff(level, pos, Math.max(5.0D, blockPower * 1.9D), 0.85f);
            }

            explosion.finalizeExplosion(false);
            explosion.clearToBlow();
        });

        sendBlastToNearbyPlayers(level, explosion, pos, size);
    }

    /**
     * Every changed block is a client section re-mesh, and an unbounded large-bomb
     * crater can hand a client tens of thousands in one tick. Keep the nearest so the
     * crater still reads as one and only the thin outer rim is dropped.
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
        // Sorting the whole crater to throw most of it away is wasted work when several
        // bombs go off at once; keep the nearest cap as the list is walked instead.
        PriorityQueue<BlockPos> nearest = new PriorityQueue<>(
                cap + 1,
                Comparator.comparingDouble(p -> -p.distToCenterSqr(center.x, center.y, center.z)));
        for (BlockPos pos : toBlow) {
            if (nearest.size() < cap) {
                nearest.add(pos);
                continue;
            }
            BlockPos farthest = nearest.peek();
            if (farthest != null
                    && pos.distToCenterSqr(center.x, center.y, center.z)
                            < farthest.distToCenterSqr(center.x, center.y, center.z)) {
                nearest.poll();
                nearest.add(pos);
            }
        }
        List<BlockPos> kept = new ArrayList<>(nearest);
        kept.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(center.x, center.y, center.z)));
        toBlow.clear();
        toBlow.addAll(kept);
    }

    /**
     * CBC's blast damage curve, reproduced for the paths that bypass
     * {@code Explosion#explode}. Mirrors
     * {@code CustomExplosion.CustomDamageCalculator#getEntityDamageAmount}, so the
     * world path and the Sable path apply the same numbers.
     */
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
     * Owned here rather than left to the loop inside {@code Explosion#explode}, which
     * gates entities on the <em>block</em> radius instead of the entity radius and so
     * could leave someone standing on a detonating bomb unhurt.
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
        LongSet destroyed = new LongOpenHashSet(explosion.getToBlow().size());
        for (BlockPos pos : explosion.getToBlow()) {
            destroyed.add(pos.asLong());
        }

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
                    ? BlastCover.evaluate(level, center, entity, destroyed, coverSamples(lod))
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
