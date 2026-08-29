package com.cbc_more_content.item;

import com.cbc_more_content.block.CruiseMissileBlock;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Binds a missile, then paints something for it to chase.
 * <p>
 * Right-click a placed missile to pair with it; then hold the attack key on a Sable hull
 * to lock. The lock itself is resolved on the server from the block the player was
 * looking at, so nothing here has to know how sub-levels are numbered.
 */
public class TargetDesignatorItem extends Item {
    private static final String BOUND = "BoundMissile";

    public TargetDesignatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CruiseMissileBlock)) {
            return InteractionResult.PASS;
        }

        BlockPos body = CruiseMissileBlock.bodyOf(state, pos);
        if (!level.isClientSide) {
            bind(context.getItemInHand(), body);
            level.playSound(null, body, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.7f, 1.6f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static void bind(ItemStack stack, BlockPos missile) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", missile.getX());
        tag.putInt("Y", missile.getY());
        tag.putInt("Z", missile.getZ());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(wrap(tag)));
    }

    /** The missile this designator is paired with, or null while it is unbound. */
    @Nullable
    public static BlockPos boundMissile(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag().getCompound(BOUND);
        return tag.contains("X") ? new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")) : null;
    }

    private static CompoundTag wrap(CompoundTag inner) {
        CompoundTag root = new CompoundTag();
        root.put(BOUND, inner);
        return root;
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        WorkInProgress.append(tooltip);
        BlockPos bound = boundMissile(stack);
        tooltip.add(Component.translatable(
                        bound == null
                                ? "tooltip.cbc_more_content.target_designator"
                                : "tooltip.cbc_more_content.target_designator.bound")
                .withStyle(ChatFormatting.GRAY));
        if (bound != null) {
            tooltip.add(Component.literal("%d, %d, %d".formatted(bound.getX(), bound.getY(), bound.getZ()))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
