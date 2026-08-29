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
    /** Resistance that roughly halves the blast. Stone is 6, so about two blocks of it. */
    private static final double HALF_ABSORB = 12.0D;
    /** Samples per body axis at full quality: 3 gives 27 rays. */
    private static final int FULL_SAMPLES = 3;
    /** No single block may count for more, so bedrock cannot go infinite. */
    private static final double MAX_BLOCK_RESISTANCE = 1800.0D;

    /** Fully exposed, used when the raycast budget is spent. */
    public static final Result OPEN = new Result(1.0D, 1.0D);

    private BlastCover() {}

    /**
     * @param transmission share of blast energy that arrives, 0 shielded to 1 in the open
     * @param openFraction share of sample rays with a completely clear path
     */
    public record Result(double transmission, double openFraction) {
        public boolean hasLineOfSight() {
            return this.openFraction > 0.0D;
        }
    }

    public static Result evaluate(Level level, Vec3 center, Entity entity) {
        return evaluate(level, center, entity, LongSets.emptySet());
    }

    /**
     * @param destroyed packed positions this blast is removing; treated as already gone
     */
    public static Result evaluate(Level level, Vec3 center, Entity entity, LongSet destroyed) {
        return evaluate(level, center, entity, destroyed, FULL_SAMPLES);
    }

    /**
     * Cover at a chosen number of samples per body axis. Full quality takes three (27
     * rays); a burst running at reduced detail takes two (8 rays), which is coarser
     * without letting one point decide whether a target is in the open.
     */
    public static Result evaluate(Level level, Vec3 center, Entity entity, LongSet destroyed, int samplesPerAxis) {
        AABB box = entity.getBoundingBox();
        int perAxis = Mth.clamp(samplesPerAxis, 1, FULL_SAMPLES);

        double transmissionSum = 0.0D;
        int open = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Sample the body rather than one point: partial cover behind a low wall should
        // protect the legs and leave the head exposed.
        for (int xi = 0; xi < perAxis; xi++) {
            double x = sampleAxis(box.minX, box.maxX, xi, perAxis);
            for (int yi = 0; yi < perAxis; yi++) {
                double y = sampleAxis(box.minY, box.maxY, yi, perAxis);
                for (int zi = 0; zi < perAxis; zi++) {
                    double z = sampleAxis(box.minZ, box.maxZ, zi, perAxis);
                    double absorbed = absorbAlong(level, center, x, y, z, destroyed, cursor);
                    if (absorbed <= 0.0D) {
                        open++;
                    }
                    transmissionSum += 1.0D / (1.0D + absorbed / HALF_ABSORB);
                }
            }
        }

        int samples = perAxis * perAxis * perAxis;
        return new Result(Mth.clamp(transmissionSum / samples, 0.0D, 1.0D), open / (double) samples);
    }

    /** Spreads the samples across the body, keeping clear of its very edges. */
    private static double sampleAxis(double min, double max, int index, int count) {
        if (count == 1) {
            return (min + max) * 0.5D;
        }
        return min + 0.1D + (max - min - 0.2D) * index / (count - 1.0D);
    }

    /** Total explosion resistance standing between two points. */
    private static double absorbAlong(
            Level level,
            Vec3 from,
            double toX,
            double toY,
            double toZ,
            LongSet destroyed,
            BlockPos.MutableBlockPos cursor) {
        double dx = toX - from.x;
        double dy = toY - from.y;
        double dz = toZ - from.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4D) {
            return 0.0D;
        }
        int steps = Mth.ceil(distance / STEP);
        double sx = dx / steps;
        double sy = dy / steps;
        double sz = dz / steps;

        double absorbed = 0.0D;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;

        // Skip the endpoints: the block the blast is inside and the block the entity
        // stands in are not cover.
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
