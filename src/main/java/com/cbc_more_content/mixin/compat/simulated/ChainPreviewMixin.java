package com.cbc_more_content.mixin.compat.simulated;

import com.cbc_more_content.block.ChainConnectorBlock;
import com.cbc_more_content.item.ChainCoilItem;
import com.llamalad7.mixinextras.sugar.Local;
import com.tterrag.registrate.util.entry.ItemEntry;
import dev.simulated_team.simulated.content.items.rope.RopeItem.ClientRopeItemHandler;
import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import dev.simulated_team.simulated.index.SimDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reuse Simulated's endpoint/range preview for the separate coil as well. */
@Mixin(value = ClientRopeItemHandler.class, remap = false)
public abstract class ChainPreviewMixin {
    @Redirect(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/simulated_team/simulated/content/items/rope/RopeItem/RopeItem;isValidRopeAttachment(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean warnautics$socketPreview(Level level, BlockPos pos, @Local ItemStack heldItem) {
        var first = heldItem.get(SimDataComponents.ROPE_FIRST_CONNECTION);
        return RopeItem.isValidRopeAttachment(level, pos)
                && ChainConnectorBlock.accepts(level, pos, heldItem)
                && (first == null || ChainConnectorBlock.accepts(level, first, heldItem));
    }

    @Redirect(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/tterrag/registrate/util/entry/ItemEntry;isIn(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean warnautics$previewChain(ItemEntry<?> entry, ItemStack stack) {
        return entry.isIn(stack) || stack.getItem() instanceof ChainCoilItem;
    }
}
