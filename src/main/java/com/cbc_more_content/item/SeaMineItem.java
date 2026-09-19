package com.cbc_more_content.item;

import com.cbc_more_content.entity.SeaMineEntity;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Places a moored sea mine into the water cell that was clicked.
 * <p>
 * Water only, and it refuses anywhere without a seabed within reach of the chain — the
 * mooring is what holds the mine at its hover, and a chain with nothing under it is
 * decoration. The mine spawns as the fresh copper version and drifts up to its mooring
 * height on its own; nothing here places it at height.
 */
public class SeaMineItem extends Item {
    public SeaMineItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Player player = context.getPlayer();

        // The face clicked decides the cell. Against the seabed that is the water above
        // it; against the water itself it is that cell. Against a hull side it is the
        // water beside it, which is where a diver would plant one anyway.
        BlockPos pos = SeaMineEntity.canMooring(level, clicked) ? clicked : clicked.relative(context.getClickedFace());
        if (!SeaMineEntity.canMooring(level, pos)) {
            if (!level.isClientSide && player != null) {
                player.displayClientMessage(
                        Component.translatable("message.cbc_more_content.sea_mine.not_water"), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        place(level, pos);

        if (player == null || !player.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    private static void place(Level level, BlockPos pos) {
        SeaMineEntity mine = new SeaMineEntity(level, Vec3.atCenterOf(pos));
        level.addFreshEntity(mine);
        level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 0.8f, 0.85f);
    }

    /** Right-clicking open water with no block face: still try the cell aimed at. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        net.minecraft.world.phys.BlockHitResult hit = pickWater(level, player);
        if (hit == null) {
            return InteractionResultHolder.pass(stack);
        }
        BlockPos pos = hit.getBlockPos();
        if (!SeaMineEntity.canMooring(level, pos)) {
            player.displayClientMessage(Component.translatable("message.cbc_more_content.sea_mine.not_water"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        place(level, pos);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.consume(stack);
    }

    /**
     * The water cell a player is aiming at, within reach. A clip in water hits nothing
     * solid, so the aim ray is walked by hand and stopped at the first water cell.
     */
    @Nullable
    private static net.minecraft.world.phys.BlockHitResult pickWater(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0f).scale(player.blockInteractionRange());
        Vec3 end = eye.add(view);
        net.minecraft.world.level.ClipContext clip = new net.minecraft.world.level.ClipContext(
                eye,
                end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.WATER,
                player);
        net.minecraft.world.phys.BlockHitResult hit = level.clip(clip);
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return null;
        }
        return hit;
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.sea_mine").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_more_content.sea_mine.chain")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
