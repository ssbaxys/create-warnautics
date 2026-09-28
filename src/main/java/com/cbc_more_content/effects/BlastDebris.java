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
 * A bounded set of chunks thrown clear of whatever a blast actually broke.
 * <p>
 * Sampled from the real crater rather than picked from a fixed list of rubble blocks, so
 * a charge set against sandstone throws sandstone and one set against a plank wall throws
 * planks — the wreckage always matches what was standing there.
 */
public final class BlastDebris {
    public static final int COUNT = 12;
    public static final int MAX_ACTIVE = 192;
    public static final int MAX_PER_TICK = 32;
    private static final java.util.Map<ServerLevel, Budget> BUDGETS = new java.util.WeakHashMap<>();
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
        // Cosmetic pieces do not need server physics and tracking where nobody can see them.
        if (level.players().stream().noneMatch(player -> player.distanceToSqr(center) < 112 * 112)) {
            return;
        }
        Budget budget = BUDGETS.computeIfAbsent(level, ignored -> new Budget());
        budget.pieces.removeIf(reference -> {
            var piece = reference.get();
            return piece == null || piece.isRemoved();
        });
        if (budget.tick != level.getGameTime()) {
            budget.tick = level.getGameTime();
            budget.spawned = 0;
        }
        int requested = Math.clamp(4 + (int) Math.sqrt(candidates.size()) / 2, 4, COUNT);
        int limit = Math.min(requested, Math.min(MAX_PER_TICK - budget.spawned, MAX_ACTIVE - budget.pieces.size()));
        if (limit <= 0) {
            return;
        }
        RandomSource random = level.random;
        int thrown = 0;
        // A handful of draws rather than a full shuffle: the list can be the whole
        // crater, and every draw only has to clear the "is this worth showing" bar.
        int attempts = Math.min(candidates.size(), limit * 6);
        java.util.Set<BlockPos> sampled = new java.util.HashSet<>();
        for (int i = 0; i < attempts && thrown < limit; i++) {
            BlockPos pos = candidates.get(random.nextInt(candidates.size()));
            if (!sampled.add(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getDestroySpeed(level, pos) < 0.0f) {
                continue;
            }

            Vec3 from = BlastScene.worldPosition(level, pos);
            Vec3 outward = from.subtract(center);
            if (outward.lengthSqr() < 1.0E-4D) {
                outward = new Vec3(random.nextDouble() - 0.5D, 0.4D, random.nextDouble() - 0.5D);
            }
            Vec3 direction = outward.normalize();
            Vec3 velocity = direction
                    .scale(SPEED * (0.6D + random.nextDouble() * 0.8D))
                    .add(0.0D, UPWARD_BIAS + random.nextDouble() * 0.3D, 0.0D);

            var piece = BlastDebrisEntity.create(level, state, from, velocity);
            if (level.addFreshEntity(piece)) {
                budget.pieces.add(new java.lang.ref.WeakReference<>(piece));
                budget.spawned++;
                thrown++;
            }
        }
    }

    private static final class Budget {
        long tick = Long.MIN_VALUE;
        int spawned;
        final java.util.List<java.lang.ref.WeakReference<BlastDebrisEntity>> pieces = new java.util.ArrayList<>();
    }
}
