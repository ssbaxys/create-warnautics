package com.cbc_more_content.effects;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How much of a blast actually reaches an entity through whatever is in the way.
 * <p>
 * Vanilla only asks whether a straight line is clear, so a pane of glass and a metre of
 * reinforced concrete stop a blast equally well. This walks each sample ray and charges
 * the explosion resistance of every block it passes through, so cover protects in
 * proportion to what it is made of.
 * <p>
 * Blocks the same blast is about to destroy are excluded. The wall that fails does not
 * shield you: it absorbs its share, breaks, and the remaining energy carries through —
 * which is the behaviour players expect when a charge blows their cover apart.
 */
public final class BlastCover {
    /** Ray step, in blocks. Fine enough to catch a single pane, cheap enough per entity. */
    private static final double STEP = 0.5D;
    /** Resistance that roughly halves the blast. Stone is 6, so ~2 blocks of it. */
    private static final double HALF_ABSORB = 12.0D;
    /** No single block may count for more than this, so bedrock cannot go infinite. */
    private static final double MAX_BLOCK_RESISTANCE = 1800.0D;

    private BlastCover() {
    }

    /**
     * @param transmission  share of the blast energy that arrives, 0 fully shielded to 1 in the open
     * @param openFraction  share of sample rays with a completely clear path
     */
    public record Result(double transmission, double openFraction) {
        public boolean hasLineOfSight() {
            return this.openFraction > 0.0D;
        }
    }

    /** Fully exposed, used when the raycast budget is spent. */
    public static final Result OPEN = new Result(1.0D, 1.0D);

    /**
     * @param destroyed positions this blast is removing; treated as already gone
     */
    public static Result evaluate(Level level, Vec3 center, Entity entity, Set<BlockPos> destroyed) {
        return evaluate(level, center, entity, destroyed, 3);
    }

    /**
     * Evaluates cover with a configurable number of samples per body axis. Full-quality
     * blasts use three samples (27 rays); burst LOD can use two (8 rays) without changing
     * the normal path or making a single point decide whether a target is exposed.
     */
    public static Result evaluate(
            Level level,
            Vec3 center,
            Entity entity,
            Set<BlockPos> destroyed,
            int samplesPerAxis) {
        AABB box = entity.getBoundingBox();
        int axisSamples = Math.max(1, Math.min(3, samplesPerAxis));
        double transmissionSum = 0.0D;
        int open = 0;
        int samples = 0;

        // Sample the body rather than a single point: partial cover behind a low wall
        // should protect the legs and leave the head exposed.
        for (int xi = 0; xi < axisSamples; xi++) {
            double x = sampleAxis(box.minX, box.maxX, xi, axisSamples);
            for (int yi = 0; yi < axisSamples; yi++) {
                double y = sampleAxis(box.minY, box.maxY, yi, axisSamples);
                for (int zi = 0; zi < axisSamples; zi++) {
                    double z = sampleAxis(box.minZ, box.maxZ, zi, axisSamples);
                    double absorbed = absorbAlong(level, center, new Vec3(x, y, z), destroyed);
                    if (absorbed <= 0.0D) {
                        open++;
                    }
                    transmissionSum += 1.0D / (1.0D + absorbed / HALF_ABSORB);
                    samples++;
                }
            }
        }

        if (samples == 0) {
            return OPEN;
        }
        return new Result(
                Mth.clamp(transmissionSum / samples, 0.0D, 1.0D),
                open / (double) samples);
    }

    private static double sampleAxis(double min, double max, int index, int count) {
        if (count == 1) {
            return (min + max) * 0.5D;
        }
        return min + 0.1D + (max - min - 0.2D) * index / (count - 1.0D);
    }

    /** Total explosion resistance standing between two points. */
    private static double absorbAlong(Level level, Vec3 from, Vec3 to, Set<BlockPos> destroyed) {
        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        if (distance < 1.0E-4D) {
            return 0.0D;
        }
        int steps = Mth.ceil(distance / STEP);
        Vec3 stride = delta.scale(1.0D / steps);
        double absorbed = 0.0D;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos last = null;

        // Skip the endpoints: the block the blast is inside and the block the entity
        // stands in are not cover.
        for (int i = 1; i < steps; i++) {
            Vec3 point = from.add(stride.scale(i));
            cursor.set(Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z));
            if (last != null && last.equals(cursor)) {
                continue;
            }
            last = cursor.immutable();

            if (destroyed.contains(last)) {
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
            if (resistance <= 0.0D) {
                continue;
            }
            absorbed += Math.min(resistance, MAX_BLOCK_RESISTANCE) * STEP;
        }
        return absorbed;
    }
}
