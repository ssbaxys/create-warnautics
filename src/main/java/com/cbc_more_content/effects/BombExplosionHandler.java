package com.cbc_more_content.effects;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.RagdollBlastCompat;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.event.WarnauticsBlockDetonateEvent;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import rbasamoyai.createbigcannons.block_hit_effects.BlockImpactTransformationHandler;
import rbasamoyai.createbigcannons.config.CBCConfigs;
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

    private BombExplosionHandler() {}

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
            ServerLevel level, DamageSource damageSource, Vec3 pos, float blockPower, float entityPower) {
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, pos);
            level = target.level();
            pos = target.pos();
        }
        detonateInternal(level, null, damageSource, pos, blockPower, entityPower, BombSize.MEDIUM, false);
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
        detonateInternal(level, source, damageSource, pos, blockPower, entityPower, BombSize.LARGE, true);
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
        BombSize.BlastVolume volume = size.blastVolume();
        float shellPower = volume.shellPowerForSameVolume(terrainPower);
        float fracturePower = Math.min(shellPower * 1.25f, blockPower);

        ShellExplosion explosion = new TerrainShellExplosion(
                level,
                source,
                damageSource,
                pos.x,
                pos.y,
                pos.z,
                shellPower,
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

        LongSet craterBlocks = new LongOpenHashSet();

        BombSympatheticDetonation.runBombBlast(() -> {
            CannonBlastFx.own(explosion::explode);

            if (canDamageTerrain) {
                List<BlockPos> fractured = BlastFracture.gather(level, pos, fracturePower, explosion.getToBlow());
                if (!fractured.isEmpty()) {
                    explosion.getToBlow().addAll(BlastProtection.filter(level, pos, blockPower, fractured));
                }
            }

            if (canDamageTerrain && !volume.isSphere()) {
                clampToBlastVolume(explosion, pos, volume);
            }

            craterBlocks.clear();
            for (BlockPos craterPos : explosion.getToBlow()) {
                craterBlocks.add(craterPos.asLong());
            }

            NeoForge.EVENT_BUS.post(new WarnauticsBlockDetonateEvent(level, explosion, pos, size));
            BlastCover.beginDetonation();
            try {
                applyBlastToEntities(level, explosion, damageSource, pos, entityPower, size, budget.lod());
            } finally {
                BlastCover.endDetonation();
            }
            RagdollBlastCompat.onBombBlast(level, pos, entityPower, size);

            if (canDamageTerrain) {
                BlastDebris.fling(level, pos, explosion.getToBlow());
            }

            if (!canDamageTerrain) {
                explosion.clearToBlow();
            } else {
                capToBlow(level, explosion, volume);
                vaporizeBlastCore(level, explosion, size, budget.lod().vaporizeChance(), volume);
                double scuffRadius = Math.max(5.0D, volume.horizontal(blockPower) * (volume.isSphere() ? 1.9D : 0.55D));
                if (volume.isSphere()) {
                    BlastScorch.scuff(level, pos, scuffRadius, 0.85f);
                } else {
                    BlastScorch.scuffDeferred(level, pos, scuffRadius, 0.85f, 8);
                }
            }

            explosion.finalizeExplosion(false);
            explosion.clearToBlow();
        });

        sendBlastToNearbyPlayers(level, explosion, pos, size);

        if (canDamageTerrain) {
            BlastGlassShatter.scheduleFor(
                    level, pos, (float) volume.horizontal(explosion.radius()), blockPower, budget.lod(), craterBlocks);
        }
    }

    private static void capToBlow(ServerLevel level, ShellExplosion explosion, BombSize.BlastVolume volume) {
        List<BlockPos> toBlow = explosion.getToBlow();
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
        double horizontalRadius = volume.horizontal(explosion.radius());
        double verticalRadius = volume.vertical(explosion.radius());
        PriorityQueue<BlockPos> nearest = new PriorityQueue<>(
                cap + 1,
                Comparator.comparingDouble(
                        (BlockPos p) -> -normalizedDistSqr(p, center, horizontalRadius, verticalRadius)));
        for (BlockPos pos : toBlow) {
            if (nearest.size() < cap) {
                nearest.add(pos);
                continue;
            }
            BlockPos farthest = nearest.peek();
            if (farthest != null
                    && normalizedDistSqr(pos, center, horizontalRadius, verticalRadius)
                            < normalizedDistSqr(farthest, center, horizontalRadius, verticalRadius)) {
                nearest.poll();
                nearest.add(pos);
            }
        }
        List<BlockPos> kept = new ArrayList<>(nearest);
        kept.sort(Comparator.comparingDouble(p -> normalizedDistSqr(p, center, horizontalRadius, verticalRadius)));
        toBlow.clear();
        toBlow.addAll(kept);
    }

    /** Distance squared in volume-normalized coordinates: 1.0 at the volume's surface. */
    private static double normalizedDistSqr(BlockPos pos, Vec3 center, double horizontalRadius, double verticalRadius) {
        double nx = (pos.getX() + 0.5D - center.x) / horizontalRadius;
        double ny = (pos.getY() + 0.5D - center.y) / verticalRadius;
        double nz = (pos.getZ() + 0.5D - center.z) / horizontalRadius;
        return nx * nx + ny * ny + nz * nz;
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
        double pressure = 1.0D - normalized;
        return (float) ((pressure * pressure + pressure) / 2.0D * 7.0D * reach + 1.0D);
    }

    /**
     * Reshapes the gathered crater onto a non-spherical blast volume. The vanilla shell
     * cut a sphere at the volume-shrunk power and the fracture pass walked rays widened
     * to the saucer footprint, so positions outside the volume — deep spurs below the
     * flattened bottom edge and the fracture rim above it — are dropped here, once,
     * after all gathering. A saucer blast carves a saucer.
     */
    private static void clampToBlastVolume(ShellExplosion explosion, Vec3 pos, BombSize.BlastVolume volume) {
        explosion
                .getToBlow()
                .removeIf(blockPos -> !volume.contains(
                        blockPos.getX() + 0.5D - pos.x,
                        blockPos.getY() + 0.5D - pos.y,
                        blockPos.getZ() + 0.5D - pos.z,
                        explosion.radius()));
    }

    private static void sendBlastToNearbyPlayers(ServerLevel level, ShellExplosion explosion, Vec3 pos, BombSize size) {
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
            ShellExplosion explosion,
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

            int samples = raycast ? Math.min(coverSamples(lod), BlastCover.samplesForDistance(dist, radius)) : 1;
            BlastCover.Result cover =
                    raycast ? BlastCover.evaluate(level, center, entity, destroyed, samples) : BlastCover.OPEN;
            double exposure = cover.transmission();
            double falloff = 1.0D - (dist / radius);

            if (entity instanceof ServerPlayer player && player.isAlive()) {
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
            float vaporizeChance,
            BombSize.BlastVolume volume) {
        List<BlockPos> toBlow = explosion.getToBlow();
        if (toBlow.isEmpty()) {
            return;
        }

        boolean sable = ModList.get().isLoaded("sable");
        if (sable && SableDropCompat.shouldSkipVaporize(level, explosion.center(), explosion.radius())) {
            return;
        }

        double coreScale =
                switch (size) {
                    case SMALL -> 0.28D;
                    case SEA -> 0.31D;
                    case MEDIUM -> 0.37D;
                    case LARGE -> 0.43D;
                    case MOAB -> 0.50D;
                };
        double coreRadius = Math.max(1.35D, explosion.radius() * coreScale);
        double coreH = coreRadius * volume.h();
        double coreV = coreRadius * volume.v();
        Vec3 center = explosion.center();

        List<BlockPos> remaining = new ArrayList<>(Math.max(4, toBlow.size() / 2));
        for (BlockPos blockPos : toBlow) {
            if (sable && SableDropCompat.isInsideSubLevel(level, blockPos)) {
                remaining.add(blockPos.immutable());
                continue;
            }
            double nx = (blockPos.getX() + 0.5D - center.x) / coreH;
            double ny = (blockPos.getY() + 0.5D - center.y) / coreV;
            double nz = (blockPos.getZ() + 0.5D - center.z) / coreH;
            double normalized = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (normalized > 1.0D) {
                remaining.add(blockPos.immutable());
                continue;
            }

            double heat = 1.0D - normalized;
            float localChance = vaporizeChance * (0.35f + 0.65f * (float) heat);
            if (level.random.nextFloat() < localChance) {
                BlockState state = level.getBlockState(blockPos);
                if (!state.isAir()) {
                    level.setBlock(blockPos, BlastRubble.replacementFor(level, blockPos, state), VAPORIZE_FLAGS);
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
            super(level, source, damageSource, x, y, z, blockRadius, entityRadius, fire, interaction, noEffects);
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
