package com.cbc_more_content.item;

import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.util.ReflectiveDispatcher;
import java.util.List;
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
        // A live charge comes first. A tripwire can be tied to anything solid, a charge
        // included, and once anchors stopped being limited to fences this branch was
        // swallowing every click on a C4 before the panel ever got a look at it.
        if (state.getBlock() instanceof C4Block && state.getValue(C4Block.STATE) != C4Block.Fuse.IDLE) {
            if (level.isClientSide) {
                openScreen(pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (com.cbc_more_content.entity.TripwireEntity.canAnchor(state)
                && level instanceof net.minecraft.server.level.ServerLevel server
                && com.cbc_more_content.entity.TripwireEntity.cutAt(server, pos)) {
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** Resolved reflectively so the screen class is never loaded on a dedicated server. */
    private static void openScreen(BlockPos pos) {
        ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.gui.C4WireClient", "open", new Class<?>[] {BlockPos.class}, pos);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.wire_cutters"));
    }
}
