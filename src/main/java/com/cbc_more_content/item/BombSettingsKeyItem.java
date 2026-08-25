package com.cbc_more_content.item;

import java.util.List;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.DropBombBlock;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import javax.annotation.Nullable;
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
        ItemStack key = context.getItemInHand();

        // Clicking anything belonging to Create Radar arms the key rather than opening
        // anything: from here on it is a binding tool until it is told otherwise. The
        // Network Controller counts, even though it is not a radar itself — the missile
        // resolves the dishes around it when it goes looking for a picture.
        var radarBlockEntity = level.getBlockEntity(pos);
        if (com.cbc_more_content.compat.RadarCompat.isRadarModBlock(radarBlockEntity)) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            // Sneaking opens the network's intercept conditions instead of arming the
            // key, so setting a battery up and re-tuning it are the same tool.
            Player player = context.getPlayer();
            if (player != null && player.isShiftKeyDown()) {
                openRadarSettings(level, pos, player);
                return InteractionResult.CONSUME;
            }
            setBindingSet(key, pos);
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundSource.BLOCKS, 0.8f, 1.7f);
            say(context.getPlayer(), "message.cbc_more_content.key.bind_mode", ChatFormatting.AQUA);
            return InteractionResult.CONSUME;
        }

        BlockPos bindingSet = bindingSet(key);
        if (bindingSet != null && state.getBlock() instanceof CruiseMissileBlock) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            BlockPos body = CruiseMissileBlock.bodyOf(state, pos);
            if (!(level.getBlockEntity(body) instanceof CruiseMissileBlockEntity guidance)) {
                return InteractionResult.PASS;
            }
            if (guidance.guidance() != CruiseMissileBlockEntity.Guidance.INTERCEPT) {
                say(context.getPlayer(), "message.cbc_more_content.key.not_intercept",
                        ChatFormatting.RED);
                return InteractionResult.CONSUME;
            }
            boolean rebound = guidance.bindController(bindingSet);
            level.playSound(null, body, SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.BLOCKS, 0.8f, rebound ? 1.1f : 1.5f);
            say(context.getPlayer(), rebound
                    ? "message.cbc_more_content.key.rebound"
                    : "message.cbc_more_content.key.bound", ChatFormatting.GREEN);
            return InteractionResult.CONSUME;
        }

        if (state.getBlock() instanceof CruiseMissileBlock) {
            BlockPos body = CruiseMissileBlock.bodyOf(state, pos);
            if (level.isClientSide) {
                BlockPos current = null;
                int mode = 0;
                if (level.getBlockEntity(body) instanceof CruiseMissileBlockEntity guidance) {
                    current = guidance.target();
                    // Reopening the screen has to show what the missile is actually set
                    // to; starting from scratch every time threw away the mode as soon as
                    // anyone glanced at the settings.
                    mode = switch (guidance.guidance()) {
                        case REMOTE -> 1;
                        case INTERCEPT -> 2;
                        default -> 0;
                    };
                }
                openMissileScreen(body, current, mode);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (state.getBlock() instanceof com.cbc_more_content.block.SirenBlock) {
            if (level.isClientSide) {
                openSirenScreen(pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (state.getBlock() instanceof C4Block) {
            // The keypad comes first either way: on an idle charge it sets the arming
            // code, on a live one it is the only way to stop the fuse.
            if (level.isClientSide) {
                openC4CodeScreen(pos, state.getValue(C4Block.STATE) != C4Block.Fuse.IDLE);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
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

    /** Sneaking with an armed key puts it back to being an ordinary settings key. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack key = player.getItemInHand(hand);
        if (!level.isClientSide && player.isShiftKeyDown() && bindingSet(key) != null) {
            key.remove(DataComponents.CUSTOM_DATA);
            say(player, "message.cbc_more_content.key.bind_cancel", ChatFormatting.GRAY);
        }
        return InteractionResultHolder.sidedSuccess(key, level.isClientSide());
    }

    /** Sends the network's current conditions to the operator so the panel opens filled in. */
    private static void openRadarSettings(Level level, BlockPos controller, Player player) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)
                || !(player instanceof net.minecraft.server.level.ServerPlayer viewer)) {
            return;
        }
        var settings = com.cbc_more_content.radar.InterceptSettingsStore.get(server)
                .forController(controller);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer,
                new com.cbc_more_content.network.OpenRadarSettingsPayload(controller, settings));
    }

    /** The radar set this key is currently handing out, or null when it is idle. */
    @Nullable
    public static BlockPos bindingSet(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag().getCompound("BindingSet");
        return tag.contains("X")
                ? new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"))
                : null;
    }

    private static void setBindingSet(ItemStack stack, BlockPos set) {
        CompoundTag inner = new CompoundTag();
        inner.putInt("X", set.getX());
        inner.putInt("Y", set.getY());
        inner.putInt("Z", set.getZ());
        CompoundTag root = new CompoundTag();
        root.put("BindingSet", inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static void say(@Nullable Player player, String key, ChatFormatting colour) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(colour), true);
        }
    }

    /**
     * Reached reflectively so the screen classes are never resolved on a dedicated
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

    private static void openMissileScreen(BlockPos pos, BlockPos current, int mode) {
        try {
            Class.forName("com.cbc_more_content.client.gui.MissileTargetClient")
                    .getMethod("open", BlockPos.class, BlockPos.class, int.class)
                    .invoke(null, pos, current, mode);
        } catch (ReflectiveOperationException e) {
            CBCMoreContent.LOGGER.debug("Missile target screen unavailable: {}", e.toString());
        }
    }

    private static void openSirenScreen(BlockPos pos) {
        try {
            Class.forName("com.cbc_more_content.client.gui.SirenSettingsClient")
                    .getMethod("open", BlockPos.class)
                    .invoke(null, pos);
        } catch (ReflectiveOperationException e) {
            CBCMoreContent.LOGGER.debug("Siren settings unavailable: {}", e.toString());
        }
    }

    private static void openC4CodeScreen(BlockPos pos, boolean disarming) {
        try {
            Class.forName("com.cbc_more_content.client.gui.C4CodeClient")
                    .getMethod("open", BlockPos.class, boolean.class)
                    .invoke(null, pos, disarming);
        } catch (ReflectiveOperationException e) {
            CBCMoreContent.LOGGER.debug("C4 keypad unavailable: {}", e.toString());
        }
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.settings_key"));
        if (com.cbc_more_content.compat.RadarCompat.loaded()) {
            tooltip.add(Component.translatable("tooltip.cbc_more_content.settings_key.radar")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (bindingSet(stack) != null) {
            tooltip.add(Component.translatable("message.cbc_more_content.key.bind_mode")
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
