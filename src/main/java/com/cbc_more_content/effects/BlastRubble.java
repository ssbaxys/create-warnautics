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
 * Blocks are removed without neighbour updates, so no fluid tick is ever scheduled and an
 * underwater blast would punch permanent air pockets into the sea. Filling submerged
 * positions with water directly gives the same result the fluid tick would have, without
 * paying for thousands of updates in one tick.
 */
public final class BlastRubble {
    private BlastRubble() {}

    /** Air on land, a water source anywhere the sea would immediately close back in. */
    public static BlockState replacementFor(BlockGetter level, BlockPos pos, BlockState removed) {
        if (isWater(removed)) {
            return Blocks.WATER.defaultBlockState();
        }
        for (Direction direction : Direction.values()) {
            // Water above falls in and water beside flows in; only a hole with dry sides
            // and a dry ceiling genuinely stays dry.
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
