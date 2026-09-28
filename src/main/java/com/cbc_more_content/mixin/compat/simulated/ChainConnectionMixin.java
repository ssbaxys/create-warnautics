package com.cbc_more_content.mixin.compat.simulated;

import com.cbc_more_content.block.ChainConnectorBlock;
import com.cbc_more_content.compat.simulated.ChainConnection;
import com.cbc_more_content.item.ChainCoilItem;
import com.cbc_more_content.registry.ModItems;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RopeStrandHolderBehavior.class, remap = false)
public abstract class ChainConnectionMixin implements ChainConnection {
    @Unique
    private boolean warnautics$chain;

    @Unique
    private static final String WARNAUTICS_CHAIN = "cbc_more_content:chain";

    @Override
    public boolean warnautics$isChain() {
        return warnautics$chain && ((RopeStrandHolderBehavior) (Object) this).isAttached();
    }

    @Override
    public void warnautics$setChain(boolean chain) {
        warnautics$chain = chain;
    }

    // Keep the socket rule at the shared connection entry point as well as item interaction.
    @Inject(method = "createRope", at = @At("HEAD"), cancellable = true)
    private void warnautics$socketMaterial(
            RopeStrandHolderBehavior target, boolean dropItem, CallbackInfoReturnable<Boolean> ci) {
        var source = (RopeStrandHolderBehavior) (Object) this;
        if (!ChainCoilItem.isPlacingChain()
                && (source.blockEntity.getBlockState().getBlock() instanceof ChainConnectorBlock
                        || target.blockEntity.getBlockState().getBlock() instanceof ChainConnectorBlock)) {
            ci.setReturnValue(false);
        }
    }

    // Set both endpoints before Simulated sends their native update packets.
    @Inject(
            method = "createRope",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/simulated_team/simulated/content/blocks/rope/RopeStrandHolderBehavior;addServerStrand(Ldev/simulated_team/simulated/content/blocks/rope/strand/server/ServerRopeStrand;)V",
                            shift = At.Shift.AFTER))
    private void warnautics$material(
            RopeStrandHolderBehavior target, boolean dropItem, CallbackInfoReturnable<Boolean> ci) {
        boolean chain = ChainCoilItem.isPlacingChain();
        warnautics$setChain(chain);
        ((ChainConnection) target).warnautics$setChain(chain);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void warnautics$write(
            CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (warnautics$isChain()) {
            tag.putBoolean(WARNAUTICS_CHAIN, true);
        } else {
            tag.remove(WARNAUTICS_CHAIN);
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void warnautics$read(
            CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        warnautics$chain = tag.getBoolean(WARNAUTICS_CHAIN);
    }

    @Inject(method = "detachRope", at = @At("TAIL"))
    private void warnautics$detach(CallbackInfo ci) {
        warnautics$chain = false;
    }

    @ModifyArg(
            method = "destroyRope",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/ItemStack;<init>(Lnet/minecraft/world/level/ItemLike;)V"),
            index = 0)
    private ItemLike warnautics$returnMaterial(ItemLike original) {
        return warnautics$isChain() ? ModItems.CHAIN_COIL.get() : original;
    }

    @ModifyArg(
            method = "destroyRope",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
            index = 4)
    private SoundEvent warnautics$breakSound(SoundEvent original) {
        return warnautics$isChain() ? SoundEvents.CHAIN_BREAK : original;
    }

    @ModifyArg(
            method = "destroyRope",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/core/particles/BlockParticleOption;<init>(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;)V"),
            index = 1)
    private BlockState warnautics$breakParticles(BlockState original) {
        return warnautics$isChain() ? Blocks.CHAIN.defaultBlockState() : original;
    }
}
