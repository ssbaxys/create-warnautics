package com.cbc_more_content.mixin.compat.sable;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(com.cbc_more_content.block.SeaMineBlock.class)
public class SeaMineBlockMixin implements BlockSubLevelLiftProvider {
    @Override
    public Direction sable$getNormal(BlockState state) {
        return Direction.DOWN;
    }

    @Override
    public float sable$getLiftScalar() {
        return 2.0F;
    }
}
