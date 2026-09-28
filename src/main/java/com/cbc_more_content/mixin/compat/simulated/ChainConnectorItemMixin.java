package com.cbc_more_content.mixin.compat.simulated;

import com.cbc_more_content.block.ChainConnectorBlock;
import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import dev.simulated_team.simulated.index.SimDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RopeItem.class, remap = false)
public abstract class ChainConnectorItemMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void warnautics$chainOnlySocket(UseOnContext context, CallbackInfoReturnable<InteractionResult> ci) {
        var player = context.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            return;
        }
        var level = context.getLevel();
        var stack = context.getItemInHand();
        var first = stack.get(SimDataComponents.ROPE_FIRST_CONNECTION);
        boolean invalidFirst = first != null && !ChainConnectorBlock.accepts(level, first, stack);
        if (invalidFirst || !ChainConnectorBlock.accepts(level, context.getClickedPos(), stack)) {
            if (invalidFirst) {
                stack.remove(SimDataComponents.ROPE_FIRST_CONNECTION);
            }
            if (player != null && !level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("message.cbc_more_content.chain_connector_only"), true);
            }
            ci.setReturnValue(InteractionResult.FAIL);
        }
    }
}
