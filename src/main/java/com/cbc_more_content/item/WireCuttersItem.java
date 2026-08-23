package com.cbc_more_content.item;

import java.util.List;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4Block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Opens the wire panel on a live charge. Useless against one that is not running. */
public class WireCuttersItem extends Item {
    public WireCuttersItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof C4Block)
                || state.getValue(C4Block.STATE) == C4Block.Fuse.IDLE) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            openScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Resolved reflectively so the screen class is never loaded on a dedicated server. */
    private static void openScreen(BlockPos pos) {
        try {
            Class.forName("com.cbc_more_content.client.gui.C4WireClient")
                    .getMethod("open", BlockPos.class)
                    .invoke(null, pos);
        } catch (ReflectiveOperationException e) {
            CBCMoreContent.LOGGER.debug("C4 wire panel unavailable: {}", e.toString());
        }
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.wire_cutters"));
    }
}
