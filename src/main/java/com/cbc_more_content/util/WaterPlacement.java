package com.cbc_more_content.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;

/** Waterlogging stores one existing source, never upgrades flowing water into a bucket. */
public final class WaterPlacement {
    private WaterPlacement() {}

    public static boolean sourceAt(BlockGetter level, BlockPos pos) {
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && fluid.isSource();
    }
}
