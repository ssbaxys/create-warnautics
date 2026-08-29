package com.cbc_more_content.item;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** The missile airframe, carrying the same unfinished-feature warning as its designator. */
public class CruiseMissileItem extends BlockItem {
    public CruiseMissileItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        WorkInProgress.append(tooltip);
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
