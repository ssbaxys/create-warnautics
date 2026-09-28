package com.cbc_more_content.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keep native physics tests away from vanilla's random coordinates millions of blocks out. */
@Mixin(GameTestServer.class)
public abstract class GameTestServerMixin {
    @ModifyVariable(method = "startTests", at = @At("STORE"), ordinal = 0)
    private BlockPos seaMineTests$nearOrigin(BlockPos randomOrigin) {
        return new BlockPos(0, -59, 0);
    }
}
