package com.cbc_more_content.mixin.compat.sable;

import org.spongepowered.asm.mixin.Mixin;

import com.cbc_more_content.block.LandMineBlock;
import com.cbc_more_content.compat.sable.LandMineSubLevelImpactCallback;

import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;

@Mixin(LandMineBlock.class)
public class LandMineBlockMixin implements BlockWithSubLevelCollisionCallback {
    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return LandMineSubLevelImpactCallback.INSTANCE;
    }
}
