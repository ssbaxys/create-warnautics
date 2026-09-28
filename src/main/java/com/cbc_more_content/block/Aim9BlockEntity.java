package com.cbc_more_content.block;

import com.cbc_more_content.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class Aim9BlockEntity extends BlockEntity {
    public Aim9BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIM9.get(), pos, state);
    }

    public boolean isLiveAirframe() {
        return !isRemoved()
                && level != null
                && level.getBlockEntity(worldPosition) == this
                && level.getBlockState(worldPosition).getBlock() instanceof Aim9Block
                && level.getBlockState(worldPosition).getValue(Aim9Block.PART) == Aim9Block.Part.BODY;
    }
}
