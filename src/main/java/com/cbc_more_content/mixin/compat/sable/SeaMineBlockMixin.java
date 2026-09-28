package com.cbc_more_content.mixin.compat.sable;

import com.cbc_more_content.compat.sable.SeaMineSubLevelImpactCallback;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(com.cbc_more_content.block.SeaMineBlock.class)
public class SeaMineBlockMixin implements BlockWithSubLevelCollisionCallback {
    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return SeaMineSubLevelImpactCallback.INSTANCE;
    }
}
