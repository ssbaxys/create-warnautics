package com.cbc_more_content.item;

import java.util.List;

import javax.annotation.Nullable;

import com.cbc_more_content.entity.TripwireEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A coil of tripwire, strung the way a lead is tied: one post, then the other.
 * <p>
 * The first post is held on the stack rather than in the world, so an unfinished run
 * leaves nothing behind and can be dropped by sneaking.
 */
public class TripwireCoilItem extends Item {
    private static final String PENDING = "FirstPost";

    public TripwireCoilItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        if (!TripwireEntity.canAnchor(level.getBlockState(pos))) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        BlockPos first = pendingPost(stack);

        if (first == null) {
            if (level instanceof ServerLevel server && TripwireEntity.occupied(server, pos)) {
                say(player, "message.cbc_more_content.tripwire.occupied", ChatFormatting.RED);
                return InteractionResult.CONSUME;
            }
            setPending(stack, pos);
            level.playSound(null, pos, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.7f, 1.2f);
            say(player, "message.cbc_more_content.tripwire.first", ChatFormatting.GRAY);
            return InteractionResult.CONSUME;
        }

        if (first.equals(pos)) {
            clearPending(stack);
            say(player, "message.cbc_more_content.tripwire.dropped", ChatFormatting.GRAY);
            return InteractionResult.CONSUME;
        }
        if (!TripwireEntity.canAnchor(level.getBlockState(first))) {
            clearPending(stack);
            say(player, "message.cbc_more_content.tripwire.dropped", ChatFormatting.GRAY);
            return InteractionResult.CONSUME;
        }
        // Straight-line distance, so a diagonal run is held to the same length as one
        // along an axis rather than quietly reaching half again as far.
        if (Math.sqrt(first.distSqr(pos)) > TripwireEntity.MAX_SPAN) {
            say(player, "message.cbc_more_content.tripwire.too_far", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        if (level instanceof ServerLevel server && TripwireEntity.occupied(server, pos)) {
            say(player, "message.cbc_more_content.tripwire.occupied", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }

        TripwireEntity wire = new TripwireEntity(level, first, pos);
        level.addFreshEntity(wire);
        clearPending(stack);
        level.playSound(null, pos, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.9f, 0.85f);
        say(player, "message.cbc_more_content.tripwire.set", ChatFormatting.GREEN);
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    /** Sneaking with a half-strung coil abandons the first end. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player.isShiftKeyDown() && pendingPost(stack) != null) {
            clearPending(stack);
            say(player, "message.cbc_more_content.tripwire.dropped", ChatFormatting.GRAY);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Nullable
    public static BlockPos pendingPost(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag().getCompound(PENDING);
        return tag.contains("X")
                ? new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"))
                : null;
    }

    private static void setPending(ItemStack stack, BlockPos post) {
        CompoundTag inner = new CompoundTag();
        inner.putInt("X", post.getX());
        inner.putInt("Y", post.getY());
        inner.putInt("Z", post.getZ());
        CompoundTag root = new CompoundTag();
        root.put(PENDING, inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static void clearPending(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
    }

    private static void say(@Nullable Player player, String key, ChatFormatting colour) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(colour), true);
        }
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        BlockPos pending = pendingPost(stack);
        tooltip.add(Component.translatable(pending == null
                        ? "tooltip.cbc_more_content.tripwire_coil"
                        : "tooltip.cbc_more_content.tripwire_coil.pending")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_more_content.tripwire_coil.span",
                        TripwireEntity.MAX_SPAN)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
