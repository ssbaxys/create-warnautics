package com.cbc_more_content.item;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.C4BlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Radio detonator for charges set to remote.
 * <p>
 * Pairing is deliberately physical — each charge has to be touched with the set after it
 * is armed — while firing is not, up to {@link #RANGE}. Past that the press simply does
 * nothing: there is no signal to send, so none is. One set holds a whole ring, and the
 * plunger fires all of it at once.
 */
public class DetonatorItem extends Item {
    /** How far the set will reach. Beyond it, pressing the plunger sends nothing. */
    public static final double RANGE = 250.0D;
    /** Enough for a demolition ring, few enough that the tooltip stays readable. */
    public static final int MAX_CHARGES = 12;
    private static final String BOUND = "BoundCharges";
    /** Long enough that a fumbled press cannot be repeated into a second ring. */
    private static final int COOLDOWN_TICKS = 10;
    /** How often a carried detonator checks that its charges are still there. */
    private static final int VALIDATE_INTERVAL = 20;

    public DetonatorItem(Properties properties) {
        super(properties);
    }

    /** Touching an armed remote charge adds it to the ring; sneaking takes it back off. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof C4Block)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();

        if (player != null && player.isShiftKeyDown()) {
            if (!unbind(stack, pos)) {
                say(player, "message.cbc_more_content.detonator.not_paired", ChatFormatting.RED);
                return InteractionResult.CONSUME;
            }
            if (level.getBlockEntity(pos) instanceof C4BlockEntity dropped) {
                dropped.setPaired(false);
            }
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundSource.PLAYERS, 0.8f, 0.9f);
            say(player, "message.cbc_more_content.detonator.unpaired", ChatFormatting.GRAY);
            return InteractionResult.CONSUME;
        }

        if (!(level.getBlockEntity(pos) instanceof C4BlockEntity charge)
                || !charge.isWaitingOnRemote()) {
            // Either not armed yet, or armed on its own timer; neither answers a set.
            say(player, "message.cbc_more_content.detonator.not_remote", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }

        List<BlockPos> ring = boundCharges(stack);
        if (ring.contains(pos)) {
            say(player, "message.cbc_more_content.detonator.already_paired", ChatFormatting.GRAY);
            return InteractionResult.CONSUME;
        }
        if (ring.size() >= MAX_CHARGES) {
            say(player, "message.cbc_more_content.detonator.full", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }

        ring.add(pos.immutable());
        store(stack, ring);
        charge.setPaired(true);
        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                SoundSource.PLAYERS, 0.8f, 1.5f);
        say(Component.translatable("message.cbc_more_content.detonator.paired", ring.size())
                .withStyle(ChatFormatting.GREEN), player);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        List<BlockPos> ring = boundCharges(stack);
        if (player.isShiftKeyDown()) {
            // Sneaking in the open drops the whole ring. Sneaking on a charge drops that
            // one, which is handled in useOn before this ever runs.
            if (!ring.isEmpty()) {
                for (BlockPos charge : ring) {
                    if (level.isLoaded(charge)
                            && level.getBlockEntity(charge) instanceof C4BlockEntity dropped) {
                        dropped.setPaired(false);
                    }
                }
                clear(stack);
                level.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(),
                        SoundSource.PLAYERS, 0.6f, 0.8f);
                say(player, "message.cbc_more_content.detonator.cleared", ChatFormatting.GRAY);
            }
            return InteractionResultHolder.success(stack);
        }

        if (ring.isEmpty()) {
            say(player, "message.cbc_more_content.detonator.unbound", ChatFormatting.GRAY);
            return InteractionResultHolder.success(stack);
        }

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.success(stack);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK,
                SoundSource.PLAYERS, 0.9f, 1.6f);

        List<BlockPos> reached = new ArrayList<>();
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos charge : ring) {
            double distanceSqr = player.distanceToSqr(
                    charge.getX() + 0.5D, charge.getY() + 0.5D, charge.getZ() + 0.5D);
            if (distanceSqr > RANGE * RANGE || !level.isLoaded(charge)) {
                // Nothing reached it, so nothing has changed about it: it stays on the
                // set, and can be fired from closer.
                left.add(charge);
            } else {
                reached.add(charge);
            }
        }
        // Anything that was reached and did not answer is gone or no longer remote;
        // either way the set has no business still holding it, so only the ones out of
        // reach are kept.
        int fired = C4BlockEntity.fireRing(server, reached);
        int unreachable = left.size();
        store(stack, left);

        if (fired > 0) {
            say(Component.translatable("message.cbc_more_content.detonator.fired", fired)
                    .withStyle(ChatFormatting.GREEN), player);
        } else if (unreachable > 0) {
            say(player, "message.cbc_more_content.detonator.out_of_range", ChatFormatting.RED);
        } else {
            say(player, "message.cbc_more_content.detonator.no_signal", ChatFormatting.RED);
        }
        return InteractionResultHolder.success(stack);
    }

    /**
     * Charges that have gone off take their place on the set with them. Checked from the
     * pocket rather than pushed by the blast: a charge is a block entity being deleted,
     * and it has no way of reaching an item stack in somebody's inventory.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || level.getGameTime() % VALIDATE_INTERVAL != 0) {
            return;
        }
        List<BlockPos> ring = boundCharges(stack);
        if (ring.isEmpty()) {
            return;
        }
        List<BlockPos> left = new ArrayList<>(ring.size());
        for (BlockPos charge : ring) {
            // An unloaded charge is kept: it may well still be sitting there, and the set
            // being carried out of the chunk is not the charge going away.
            if (!level.isLoaded(charge)
                    || (level.getBlockEntity(charge) instanceof C4BlockEntity target
                            && target.isWaitingOnRemote())) {
                left.add(charge);
            }
        }
        if (left.size() != ring.size()) {
            store(stack, left);
        }
    }

    /** The charges this set holds, in the order they were paired. Never null. */
    public static List<BlockPos> boundCharges(ItemStack stack) {
        List<BlockPos> ring = new ArrayList<>();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return ring;
        }
        ListTag list = data.copyTag().getList(BOUND, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            int[] at = list.getIntArray(i);
            if (at.length == 3) {
                ring.add(new BlockPos(at[0], at[1], at[2]));
            }
        }
        return ring;
    }

    /** Takes one charge off the set. False when it was not on it to begin with. */
    public static boolean unbind(ItemStack stack, BlockPos charge) {
        List<BlockPos> ring = boundCharges(stack);
        if (!ring.remove(charge)) {
            return false;
        }
        store(stack, ring);
        return true;
    }

    public static void clear(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
    }

    private static void store(ItemStack stack, List<BlockPos> ring) {
        if (ring.isEmpty()) {
            clear(stack);
            return;
        }
        ListTag list = new ListTag();
        for (BlockPos charge : ring) {
            list.add(new IntArrayTag(new int[] {charge.getX(), charge.getY(), charge.getZ()}));
        }
        CompoundTag root = new CompoundTag();
        root.put(BOUND, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static void say(@Nullable Player player, String key, ChatFormatting colour) {
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable(key).withStyle(colour), true);
        }
    }

    private static void say(Component message, @Nullable Player player) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        List<BlockPos> ring = boundCharges(stack);
        if (ring.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.cbc_more_content.detonator")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.cbc_more_content.detonator.bound",
                            ring.size(), MAX_CHARGES)
                    .withStyle(ChatFormatting.GRAY));
            for (BlockPos charge : ring) {
                tooltip.add(Component.literal(" %d, %d, %d"
                                .formatted(charge.getX(), charge.getY(), charge.getZ()))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            tooltip.add(Component.translatable("tooltip.cbc_more_content.detonator.unpair")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.cbc_more_content.detonator.range",
                        (int) RANGE)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
