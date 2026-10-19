package com.cbc_more_content.item;

import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.ui.Notices;
import com.cbc_more_content.ui.UiTone;
import com.cbc_more_content.util.ReflectiveDispatcher;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.item.component.CustomData;
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
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.8f, 1.7f);
            say(context.getPlayer(), "message.cbc_more_content.key.bind_mode", UiTone.ACCENT);
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
                say(context.getPlayer(), "message.cbc_more_content.key.not_intercept", UiTone.DANGER);
                return InteractionResult.CONSUME;
            }
            boolean rebound = guidance.bindController(bindingSet);
            level.playSound(
                    null, body, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, rebound ? 1.1f : 1.5f);
            say(
                    context.getPlayer(),
                    rebound ? "message.cbc_more_content.key.rebound" : "message.cbc_more_content.key.bound",
                    UiTone.SUCCESS);
            return InteractionResult.CONSUME;
        }

        if (state.getBlock() instanceof CruiseMissileBlock) {
            BlockPos body = CruiseMissileBlock.bodyOf(state, pos);
            if (level.isClientSide) {
                BlockPos current = null;
                int mode = 0;
                int flightProfile = 0;
                if (level.getBlockEntity(body) instanceof CruiseMissileBlockEntity guidance) {
                    current = guidance.target();
                    flightProfile = guidance.flightProfile().id();
                    // Reopening the screen has to show what the missile is actually set
                    // to; starting from scratch every time threw away the mode as soon as
                    // anyone glanced at the settings.
                    mode = switch (guidance.guidance()) {
                        case REMOTE -> 1;
                        case INTERCEPT -> 2;
                        default -> 0;
                    };
                }
                openMissileScreen(body, current, mode, flightProfile);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (state.getBlock() instanceof com.cbc_more_content.block.Aim9Block) {
            if (level.isClientSide) {
                ReflectiveDispatcher.invoke(
                        "com.cbc_more_content.client.gui.Aim9SettingsClient",
                        "open",
                        new Class<?>[] {BlockPos.class},
                        com.cbc_more_content.block.Aim9Block.bodyOf(state, pos));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (state.getBlock() instanceof com.cbc_more_content.bombsight.BombsightBlock) {
            if (level.isClientSide) {
                ReflectiveDispatcher.invoke(
                        "com.cbc_more_content.client.gui.BombsightScreen",
                        "open",
                        new Class<?>[] {BlockPos.class},
                        pos);
            } else {
                // The key remembers this sight, so a target can be marked with it from the air.
                setBombsight(key, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (state.getBlock() instanceof com.cbc_more_content.block.RocketPodBlock) {
            if (!com.cbc_more_content.block.RocketPodBlock.formed(state)) {
                if (!level.isClientSide) {
                    say(context.getPlayer(), "message.cbc_more_content.rocket_pod.unassembled", UiTone.MUTED);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (level.isClientSide) {
                ReflectiveDispatcher.invoke(
                        "com.cbc_more_content.client.gui.RocketPodSettingsClient",
                        "open",
                        new Class<?>[] {BlockPos.class},
                        com.cbc_more_content.block.RocketPodBlock.bodyOf(state, pos));
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
            Player who = context.getPlayer();
            // A charge that landed set up from a preset starts with one click; sneaking
            // goes through the keypad as usual, to set it up afresh.
            if (who != null
                    && !who.isShiftKeyDown()
                    && level.getBlockEntity(pos) instanceof com.cbc_more_content.block.C4BlockEntity c4
                    && c4.isPrimed()) {
                if (!level.isClientSide) {
                    c4.arm();
                    if (c4.isWaitingOnRemote()) {
                        com.cbc_more_content.item.DetonatorItem.pairFromInventory(who, pos, c4);
                    } else {
                        Notices.send(
                                who, UiTone.DANGER, "message.cbc_more_content.c4.preset.started", c4.fuseSeconds());
                    }
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
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
            openScreen(pos, state.getValue(DropBombBlock.RELEASE_DELAY), state.getValue(DropBombBlock.CASSETTE));
        } else {
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.5f, 1.4f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Sneaking with an armed key puts it back to being an ordinary settings key. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack key = player.getItemInHand(hand);
        if (!level.isClientSide && player.isShiftKeyDown() && bindingSet(key) != null) {
            CompoundTag root = key.get(DataComponents.CUSTOM_DATA).copyTag();
            root.remove("BindingSet");
            key.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            say(player, "message.cbc_more_content.key.bind_cancel", UiTone.MUTED);
        } else if (!level.isClientSide && player.isShiftKeyDown() && bombsight(key) != null) {
            markBombsightTarget(level, player, bombsight(key));
        }
        return InteractionResultHolder.sidedSuccess(key, level.isClientSide());
    }

    /** Sends the network's current conditions to the operator so the panel opens filled in. */
    private static void openRadarSettings(Level level, BlockPos controller, Player player) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)
                || !(player instanceof net.minecraft.server.level.ServerPlayer viewer)) {
            return;
        }
        var settings =
                com.cbc_more_content.radar.InterceptSettingsStore.get(server).forController(controller);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                viewer, new com.cbc_more_content.network.OpenRadarSettingsPayload(controller, settings));
    }

    /** The radar set this key is currently handing out, or null when it is idle. */
    @Nullable
    public static BlockPos bindingSet(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag().getCompound("BindingSet");
        return tag.contains("X") ? new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")) : null;
    }

    /** The bombsight this key last opened, for marking its target from the air. */
    @Nullable
    public static BlockPos bombsight(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag().getCompound("Bombsight");
        return tag.contains("X") ? new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")) : null;
    }

    private static void setBombsight(ItemStack stack, BlockPos sight) {
        CompoundTag inner = new CompoundTag();
        inner.putInt("X", sight.getX());
        inner.putInt("Y", sight.getY());
        inner.putInt("Z", sight.getZ());
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = data == null ? new CompoundTag() : data.copyTag();
        root.put("Bombsight", inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    /**
     * Sneak and use the key in the air: the spot on the ground the player is looking at, up
     * to half a kilometre off, becomes the target of the bombsight the key last opened.
     */
    private static void markBombsightTarget(Level level, Player player, BlockPos sightPos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
            return;
        }
        if (!server.isLoaded(sightPos)
                || !(server.getBlockEntity(sightPos)
                        instanceof com.cbc_more_content.bombsight.BombsightBlockEntity sight)) {
            say(player, "message.cbc_more_content.bombsight.lost", UiTone.DANGER);
            return;
        }
        var where = com.cbc_more_content.compat.SableDropCompat.resolveWorldBlastChecked(server, sightPos.getCenter())
                .pos();
        if (player.distanceToSqr(where) > 256 * 256) {
            say(player, "message.cbc_more_content.bombsight.too_far", UiTone.DANGER);
            return;
        }
        var hit = player.pick(512.0D, 1.0F, false);
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            say(player, "message.cbc_more_content.bombsight.no_point", UiTone.MUTED);
            return;
        }
        var at = net.minecraft.core.BlockPos.containing(hit.getLocation());
        sight.markTarget(at.getX(), at.getZ());
        level.playSound(
                null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.4f);
        Notices.send(
                player,
                UiTone.SUCCESS,
                "message.cbc_more_content.bombsight.marked",
                at.getX(),
                at.getZ(),
                Math.round(Math.sqrt(player.distanceToSqr(hit.getLocation()))));
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

    private static void say(@Nullable Player player, String key, UiTone tone) {
        Notices.send(player, tone, key);
    }

    /**
     * Reached reflectively so the screen classes are never resolved on a dedicated
     * server, matching how the mod already gates its other client-only entry points.
     */
    private static void openScreen(BlockPos pos, int storedDelay, int cassette) {
        ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.gui.BombSettingsClient",
                "open",
                new Class<?>[] {BlockPos.class, int.class, int.class},
                pos,
                storedDelay,
                cassette);
    }

    private static void openMissileScreen(BlockPos pos, BlockPos current, int mode, int flightProfile) {
        ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.gui.MissileTargetClient",
                "open",
                new Class<?>[] {BlockPos.class, BlockPos.class, int.class, int.class},
                pos,
                current,
                mode,
                flightProfile);
    }

    private static void openSirenScreen(BlockPos pos) {
        ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.gui.SirenSettingsClient", "open", new Class<?>[] {BlockPos.class}, pos);
    }

    private static void openC4CodeScreen(BlockPos pos, boolean disarming) {
        ReflectiveDispatcher.invoke(
                "com.cbc_more_content.client.gui.C4CodeClient",
                "open",
                new Class<?>[] {BlockPos.class, boolean.class},
                pos,
                disarming);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (bindingSet(stack) != null) {
            tooltip.add(UiTone.ACCENT.text("message.cbc_more_content.key.bind_mode"));
        }
    }
}
