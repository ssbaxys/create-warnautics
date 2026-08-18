package com.cbc_more_content.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides what a blast leaves behind in a cleared block.
 * <p>
 * Warnautics removes blocks with {@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE} — no
 * neighbour updates — because a large crater issuing neighbour updates for thousands of
 * positions in one tick is what stalls the server. The cost is that no fluid tick is
 * ever scheduled, so an underwater blast used to punch permanent air pockets into the
 * sea: kelp forests were the obvious case, since every kelp block carries water and
 * became a dry hole the ocean never refilled.
 * <p>
 * Filling submerged positions with water directly sidesteps that: the result is what the
 * fluid tick would have produced anyway, without paying for the updates.
 */
public final class BlastRubble {
    private BlastRubble() {
    }

    /** Air on land, a water source anywhere the sea would immediately close back in. */
    public static BlockState replacementFor(BlockGetter level, BlockPos pos, BlockState removed) {
        if (isWater(removed)) {
            return Blocks.WATER.defaultBlockState();
        }
        for (Direction direction : Direction.values()) {
            // Water above a hole always falls in; water beside it flows in. Only a hole
            // with dry sides and a dry ceiling genuinely stays dry.
            if (direction == Direction.DOWN) {
                continue;
            }
            try {
                if (isWater(level.getBlockState(pos.relative(direction)))) {
                    return Blocks.WATER.defaultBlockState();
                }
            } catch (Throwable ignored) {
                // Unloaded neighbour — treat as dry.
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static boolean isWater(BlockState state) {
        return state != null && state.getFluidState().is(FluidTags.WATER);
    }
}
