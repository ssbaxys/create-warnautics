package com.cbc_more_content.mixin;

import com.cbc_more_content.effects.MineExplosionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst;
import rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst.SubProjectile;

@Mixin(ShrapnelBurst.class)
public class ShrapnelBurstMixin {
    @Inject(method = "onSubProjectileHitBlock", at = @At("HEAD"), cancellable = true)
    private void cbc_more_content$vetoChip(BlockHitResult hit, SubProjectile subProjectile, CallbackInfo ci) {
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

    /**
     * A claymore's balls still stop at whoever they strike, so bodies shade those behind,
     * but the damage is dealt by {@code MineExplosionHandler} as one worked-out blow.
     */
    @Inject(method = "onSubProjectileHitEntity", at = @At("HEAD"), cancellable = true)
    private void cbc_more_content$claymoreHitsAreCounted(
            net.minecraft.world.phys.EntityHitResult hit, SubProjectile subProjectile, CallbackInfo ci) {
        if (((ShrapnelBurst) (Object) this).getPersistentData().getBoolean(MineExplosionHandler.CLAYMORE_BURST_TAG)) {
            ci.cancel();
        }
    }

    /**
     * A claymore's steel balls are pellets, not shell splinters. At CBC's shell-fragment
     * size the first body in front caught half the fan and shaded everyone behind it.
     */
    @Inject(method = "getSubProjectileWidth", at = @At("HEAD"), cancellable = true)
    private void cbc_more_content$claymoreBallWidth(CallbackInfoReturnable<Double> cir) {
        if (((ShrapnelBurst) (Object) this).getPersistentData().getBoolean(MineExplosionHandler.CLAYMORE_BURST_TAG)) {
            cir.setReturnValue(MineExplosionHandler.CLAYMORE_BALL_SIZE);
        }
    }

    @Inject(method = "getSubProjectileHeight", at = @At("HEAD"), cancellable = true)
    private void cbc_more_content$claymoreBallHeight(CallbackInfoReturnable<Double> cir) {
        if (((ShrapnelBurst) (Object) this).getPersistentData().getBoolean(MineExplosionHandler.CLAYMORE_BURST_TAG)) {
            cir.setReturnValue(MineExplosionHandler.CLAYMORE_BALL_SIZE);
        }
    }
}
