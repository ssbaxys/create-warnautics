package com.cbc_more_content.effects;

import com.cbc_more_content.entity.BlastDebrisEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Three chunks thrown clear of whatever a blast actually broke.
 * <p>
 * Sampled from the real crater rather than picked from a fixed list of rubble blocks, so
 * a charge set against sandstone throws sandstone and one set against a plank wall throws
 * planks — the wreckage always matches what was standing there.
 */
public final class BlastDebris {
    public static final int COUNT = 3;
    private static final double SPEED = 0.55D;
    private static final double UPWARD_BIAS = 0.35D;

    private BlastDebris() {}

    /**
     * @param candidates positions still holding their original block, read before the
     *                   crater is carved — the debris has to see what was really there
     */
    public static void fling(ServerLevel level, Vec3 center, List<BlockPos> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        RandomSource random = level.random;
        int thrown = 0;
        // A handful of draws rather than a full shuffle: the list can be the whole
        // crater, and every draw only has to clear the "is this worth showing" bar.
        int attempts = Math.min(candidates.size(), COUNT * 6);
        for (int i = 0; i < attempts && thrown < COUNT; i++) {
            BlockPos pos = candidates.get(random.nextInt(candidates.size()));
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getDestroySpeed(level, pos) < 0.0f) {
                continue;
            }

            Vec3 from = Vec3.atCenterOf(pos);
            Vec3 outward = from.subtract(center);
            if (outward.lengthSqr() < 1.0E-4D) {
                outward = new Vec3(random.nextDouble() - 0.5D, 0.4D, random.nextDouble() - 0.5D);
            }
            Vec3 direction = outward.normalize();
            Vec3 velocity = direction
                    .scale(SPEED * (0.6D + random.nextDouble() * 0.8D))
                    .add(0.0D, UPWARD_BIAS + random.nextDouble() * 0.3D, 0.0D);

            level.addFreshEntity(BlastDebrisEntity.create(level, state, from, velocity));
            thrown++;
        }
    }
}
