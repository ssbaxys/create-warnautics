package com.cbc_more_content.mixin.compat.simulated;

import com.cbc_more_content.item.ChainCoilItem;
import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = RopeItem.class, remap = false)
public abstract class ChainPlacementSoundMixin {
    @ModifyArg(
            method = "attachRope",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
            index = 2)
    private SoundEvent warnautics$placeSound(SoundEvent original) {
        return (Object) this instanceof ChainCoilItem ? SoundEvents.CHAIN_PLACE : original;
    }
}
