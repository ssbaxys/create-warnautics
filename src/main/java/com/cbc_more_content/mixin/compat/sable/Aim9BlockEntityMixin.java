package com.cbc_more_content.mixin.compat.sable;

import com.cbc_more_content.block.Aim9BlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Sable actors tick independently of the normal world's storage-chunk ticker. */
@Mixin(Aim9BlockEntity.class)
public abstract class Aim9BlockEntityMixin implements BlockEntitySubLevelActor {
    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        var be = (Aim9BlockEntity) (Object) this;
        var level = subLevel.getLevel();
        if (level != null && be.getLevel() == level) {
            Aim9BlockEntity.serverTick(level, be.getBlockPos(), level.getBlockState(be.getBlockPos()), be);
        }
    }
}
