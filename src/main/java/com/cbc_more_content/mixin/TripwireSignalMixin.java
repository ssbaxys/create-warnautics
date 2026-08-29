package com.cbc_more_content.mixin;

import com.cbc_more_content.event.TripwireSignal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a post a tripwire was tied to report power while its pulse lasts.
 * <p>
 * Reported from the block state rather than from a block of our own, because the posts
 * are ordinary fences that were there before the wire and stay after it: the wire only
 * marks a position, and this is what turns that mark into a signal every consumer of
 * redstone already understands.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class TripwireSignalMixin {
    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void cbc_more_content$tripwireSignal(
            BlockGetter level, BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> callback) {
        int power = TripwireSignal.strength(level, pos);
        if (power > 0) {
            callback.setReturnValue(power);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void cbc_more_content$tripwireDirectSignal(
            BlockGetter level, BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> callback) {
        int power = TripwireSignal.strength(level, pos);
        if (power > 0) {
            callback.setReturnValue(power);
        }
    }
}
