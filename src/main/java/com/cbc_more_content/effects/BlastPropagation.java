package com.cbc_more_content.effects;

import com.cbc_more_content.bomb.BombSize;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

/** Isotropic pressure rays share one scene for terrain and moving hulls. */
public final class BlastPropagation {
    private static final double STEP = 0.3D;

    private BlastPropagation() {}

    public static List<BlockPos> gather(
            ServerLevel level, Explosion explosion, float basePower, BombSize.BlastVolume volume, long seed, int cap) {
        return gather(level, explosion, basePower, volume, seed, cap, null);
    }

    public static List<BlockPos> gather(
            ServerLevel level,
            Explosion explosion,
            float basePower,
            BombSize.BlastVolume volume,
            long seed,
            int cap,
            @Nullable BlastImpulse impulses) {
        return gather(level, explosion, explosion.radius(), basePower, volume, seed, cap, impulses);
    }

    /** Fragmentation mines transfer a small pressure impulse without excavating an HE crater. */
    public static void pushSubLevels(ServerLevel level, Explosion explosion, float power) {
        BlastImpulse impulses = new BlastImpulse();
        gather(level, explosion, power, power, BombSize.BlastVolume.SPHERE, level.random.nextLong(), 0, impulses);
        impulses.apply(java.util.Set.of());
    }

    private static List<BlockPos> gather(
            ServerLevel level,
            Explosion explosion,
            float power,
            float basePower,
            BombSize.BlastVolume volume,
            long seed,
            int cap,
            @Nullable BlastImpulse impulses) {
        double fracturePower = Math.min(power * 1.25D, basePower);
        double limit = volume.isSphere() ? power * 2.0D : power;
        Vec3 center = explosion.center();
        BlastScene scene = new BlastScene(level, center, limit * Math.max(volume.h(), volume.v()) + 1);
        RandomSource random = RandomSource.create(seed);
        Map<BlockPos, BlastScene.Sample> affected = new HashMap<>();
        // Thousands of rays revisit the same cells. Material queries and pow only need
        // to run once per cell in this immutable explosion snapshot.
        Map<BlockPos, Resistance> materials = new HashMap<>();
        List<BlastScene.Sample> samples = new ArrayList<>();
        // A fixed cube-face grid leaves axis-aligned lobes and gaps when widened for large payloads.
        // Equal-area directions cover the sphere without favouring world axes; density grows with the charge.
        int rays =
                (int) Math.clamp(Math.ceil(4 * Math.PI * power * power * Math.max(volume.h(), volume.v())), 1352, 4096);
        double phase = random.nextDouble() * Math.PI * 2;
        double goldenAngle = Math.PI * (3 - Math.sqrt(5));
        for (int ray = 0; ray < rays; ray++) {
            double dy = 1 - 2 * (ray + 0.5D) / rays;
            double ring = Math.sqrt(1 - dy * dy);
            double angle = ray * goldenAngle + phase;
            Vec3 direction = new Vec3(Math.cos(angle) * ring, dy, Math.sin(angle) * ring);
            Vec3 stretched = direction.multiply(volume.h(), volume.v(), volume.h());
            Vec3 worldDirection = stretched.normalize();
            double normalizedStep = STEP / stretched.length();
            Vec3 step = stretched.scale(normalizedStep);
            double pressure = power * (0.7D + random.nextFloat() * 0.6D);
            double fracture = fracturePower >= 5.0D ? fracturePower * 1.3D * (0.85D + random.nextFloat() * 0.3D) : 0;
            Vec3 point = center;
            for (double distance = 0; distance <= limit && (pressure > 0 || fracture > 0); distance += normalizedStep) {
                if (!level.hasChunkAt(BlockPos.containing(point))) {
                    break;
                }
                if (distance >= fracturePower) {
                    fracture = 0;
                }
                scene.sample(point, samples);
                for (BlastScene.Sample block : samples) {
                    Resistance material = materials.computeIfAbsent(block.pos(), ignored -> {
                        float resistance = block.state().getExplosionResistance(level, block.pos(), explosion);
                        return new Resistance(
                                (resistance + 0.3D) * STEP,
                                (Math.pow(Math.max(0, resistance), 0.58D) + 0.3D) * 0.3D * STEP / 0.5D,
                                block.state().getDestroySpeed(level, block.pos()) < 0 || resistance >= 3600);
                    });
                    boolean unbreakable = material.unbreakable;
                    double before = pressure;
                    pressure -= material.pressureLoss;
                    fracture -= material.fractureLoss;
                    if (unbreakable) {
                        pressure = 0;
                        fracture = 0;
                    }
                    if (cap > 0 && !unbreakable && (pressure > 0 || fracture > 0)) {
                        affected.putIfAbsent(block.pos(), block);
                    }
                    if (impulses != null && before > 0) {
                        impulses.absorb(block, worldDirection, before - Math.max(0, pressure), basePower, rays);
                    }
                }
                pressure -= 0.225D * normalizedStep / STEP;
                fracture -= 1.3D * normalizedStep;
                point = point.add(step);
            }
        }
        return affected.values().stream()
                .map(block -> new Ranked(block, selectionDistance(block.worldCenter(), center, volume, seed)))
                .sorted(Comparator.comparingDouble(Ranked::distance)
                        .thenComparingDouble(ranked -> ranked.block.worldCenter().x)
                        .thenComparingDouble(ranked -> ranked.block.worldCenter().y)
                        .thenComparingDouble(ranked -> ranked.block.worldCenter().z))
                .limit(cap)
                .map(ranked -> ranked.block.pos())
                .toList();
    }

    private record Resistance(double pressureLoss, double fractureLoss, boolean unbreakable) {}

    private record Ranked(BlastScene.Sample block, double distance) {}

    private static double selectionDistance(Vec3 point, Vec3 center, BombSize.BlastVolume volume, long seed) {
        Vec3 offset = point.subtract(center);
        Vec3 delta = offset.multiply(1 / volume.h(), 1 / volume.v(), 1 / volume.h());
        // Keep a solid core, but feather a capped crater into a broken rim instead of slicing a perfect shell.
        // Hash relative world coordinates so translated world/plot copies make exactly the same choice.
        long hash = seed
                ^ Math.round(offset.x * 4096) * 0x9E3779B97F4A7C15L
                ^ Math.round(offset.y * 4096) * 0xC2B2AE3D27D4EB4FL
                ^ Math.round(offset.z * 4096) * 0x165667B19E3779F9L;
        hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        double variation = 0.8D + (hash >>> 11) * 0x1.0p-53 * 0.4D;
        return delta.lengthSqr() / (variation * variation);
    }
}
