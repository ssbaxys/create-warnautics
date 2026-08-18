package com.cbc_more_content.item;

import java.util.List;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.DropBombBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Settings key. Right-click a placed bomb to open its release-interval dial.
 * <p>
 * Replaces the old sneak-and-click cycling, which forced players to guess the current
 * interval and step through all six presets to reach the one they wanted.
 */
public class BombSettingsKeyItem extends Item {
    public BombSettingsKeyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DropBombBlock bomb) || !bomb.allowsCassette()) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            openScreen(pos,
                    state.getValue(DropBombBlock.RELEASE_DELAY),
                    state.getValue(DropBombBlock.CASSETTE));
        } else {
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM,
                    SoundSource.BLOCKS, 0.5f, 1.4f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Reached reflectively so the screen class is never resolved on a dedicated
     * server, matching how the mod already gates its other client-only entry points.
     */
    private static void openScreen(BlockPos pos, int storedDelay, int cassette) {
        try {
            Class.forName("com.cbc_more_content.client.gui.BombSettingsClient")
                    .getMethod("open", BlockPos.class, int.class, int.class)
                    .invoke(null, pos, storedDelay, cassette);
        } catch (ReflectiveOperationException e) {
            CBCMoreContent.LOGGER.debug("Bomb settings screen unavailable: {}", e.toString());
        }
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.settings_key"));
    }
}
