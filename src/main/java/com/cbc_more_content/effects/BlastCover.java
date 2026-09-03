package com.cbc_more_content.effects;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How much of a blast reaches an entity through whatever is in the way.
 * <p>
 * Vanilla only asks whether a straight line is clear, so a pane of glass and a metre of
 * reinforced concrete stop a blast equally well. This walks each sample ray and charges
 * the explosion resistance of everything it passes through.
 * <p>
 * Blocks the same blast is about to destroy are excluded: the wall that fails absorbs
 * its share, breaks, and the rest carries through.
 */
public final class BlastCover {
    /** Ray step in blocks — fine enough to catch a single pane. */
    private static final double STEP = 0.5D;

    private static final double HALF_ABSORB = 12.0D;
    private static final int FULL_SAMPLES = 3;
    private static final double MAX_BLOCK_RESISTANCE = 1800.0D;
    private static final int MAX_STEPS_PER_DETONATION = 60_000;
    private static final int MAX_STEPS_PER_RAY = 160;

    private static final ThreadLocal<int[]> STEP_BUDGET = new ThreadLocal<>();

    public static final Result OPEN = new Result(1.0D, 1.0D);

    private BlastCover() {}

    public static void beginDetonation() {
        STEP_BUDGET.set(new int[] {MAX_STEPS_PER_DETONATION});
    }

    public static void endDetonation() {
        STEP_BUDGET.remove();
    }

    public static int samplesForDistance(double distance, double entityRadius) {
        if (distance < entityRadius * 0.45D) {
            return FULL_SAMPLES;
        }
        if (distance < entityRadius * 0.75D) {
            return 2;
        }
        return 1;
    }

    public record Result(double transmission, double openFraction) {
        public boolean hasLineOfSight() {
            return this.openFraction > 0.0D;
        }
    }

    public static Result evaluate(Level level, Vec3 center, Entity entity) {
        return evaluate(level, center, entity, LongSets.emptySet());
    }

    public static Result evaluate(Level level, Vec3 center, Entity entity, LongSet destroyed) {
        return evaluate(level, center, entity, destroyed, FULL_SAMPLES);
    }

    public static Result evaluate(Level level, Vec3 center, Entity entity, LongSet destroyed, int samplesPerAxis) {
        AABB box = entity.getBoundingBox();
        int perAxis = Mth.clamp(samplesPerAxis, 1, FULL_SAMPLES);
        int[] budget = STEP_BUDGET.get();
        if (budget != null && budget[0] <= 0) {
            return OPEN;
        }
        int rays = perAxis * perAxis * perAxis;
        int slice = budget == null ? Integer.MAX_VALUE : Math.min(budget[0], rays * MAX_STEPS_PER_RAY);
        if (budget != null) {
            budget[0] -= slice;
        }

        double transmissionSum = 0.0D;
        int open = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int xi = 0; xi < perAxis; xi++) {
            double x = sampleAxis(box.minX, box.maxX, xi, perAxis);
            for (int yi = 0; yi < perAxis; yi++) {
                double y = sampleAxis(box.minY, box.maxY, yi, perAxis);
                for (int zi = 0; zi < perAxis; zi++) {
                    double z = sampleAxis(box.minZ, box.maxZ, zi, perAxis);
                    double absorbed = absorbAlong(level, center, x, y, z, destroyed, cursor, slice);
                    if (absorbed <= 0.0D) {
                        open++;
                    }
                    transmissionSum += 1.0D / (1.0D + absorbed / HALF_ABSORB);
                }
            }
        }

        int samples = rays;
        return new Result(Mth.clamp(transmissionSum / samples, 0.0D, 1.0D), open / (double) samples);
    }

    private static double sampleAxis(double min, double max, int index, int count) {
        if (count == 1) {
            return (min + max) * 0.5D;
        }
        return min + 0.1D + (max - min - 0.2D) * index / (count - 1.0D);
    }

    private static double absorbAlong(
            Level level,
            Vec3 from,
            double toX,
            double toY,
            double toZ,
            LongSet destroyed,
            BlockPos.MutableBlockPos cursor,
            int stepBudget) {
        double dx = toX - from.x;
        double dy = toY - from.y;
        double dz = toZ - from.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4D) {
            return 0.0D;
        }
        int steps = Math.min(Mth.ceil(distance / STEP), MAX_STEPS_PER_RAY);
        if (stepBudget <= 0) {
            return 0.0D;
        }
        steps = Math.min(steps, stepBudget);
        double sx = dx / steps;
        double sy = dy / steps;
        double sz = dz / steps;

        double absorbed = 0.0D;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;

        for (int i = 1; i < steps; i++) {
            int x = Mth.floor(from.x + sx * i);
            int y = Mth.floor(from.y + sy * i);
            int z = Mth.floor(from.z + sz * i);
            if (x == lastX && y == lastY && z == lastZ) {
                continue;
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            cursor.set(x, y, z);

            if (destroyed.contains(cursor.asLong())) {
                continue;
            }
            BlockState state;
            try {
                state = level.getBlockState(cursor);
            } catch (Throwable ignored) {
                continue;
            }
            if (state.isAir()) {
                continue;
            }
            double resistance;
            try {
                resistance = state.getExplosionResistance(level, cursor, null);
            } catch (Throwable ignored) {
                resistance = state.getBlock().getExplosionResistance();
            }
            if (resistance > 0.0D) {
                absorbed += Math.min(resistance, MAX_BLOCK_RESISTANCE) * STEP;
            }
        }
        return absorbed;
    }
}
