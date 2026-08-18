package com.cbc_more_content.mixin.compat.sable;

import org.spongepowered.asm.mixin.Mixin;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.compat.sable.DropBombSubLevelImpactCallback;

import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;

@Mixin(DropBombBlock.class)
public class DropBombBlockMixin implements BlockWithSubLevelCollisionCallback {
    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return DropBombSubLevelImpactCallback.INSTANCE;
    }
}
