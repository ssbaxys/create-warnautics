package com.cbc_more_content.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.event.WarnauticsBlockChipEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst;
import rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst.SubProjectile;

@Mixin(ShrapnelBurst.class)
public class ShrapnelBurstMixin {
    @Inject(
            method = "onSubProjectileHitBlock",
            at = @At("HEAD"),
            cancellable = true)
    private void cbc_more_content$vetoChip(
            BlockHitResult hit,
            SubProjectile subProjectile,
            CallbackInfo ci) {
        ShrapnelBurst self = (ShrapnelBurst) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!self.getPersistentData().getBoolean(MineExplosionHandler.MINE_BURST_TAG)) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (!MineExplosionHandler.mayChipBlock((ServerLevel) self.level(), pos, self.position())) {
            ci.cancel();
        }
    }
}
