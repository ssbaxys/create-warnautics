package com.cbc_more_content.item;

import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.ClaymoreBlock;
import com.cbc_more_content.ui.Notices;
import com.cbc_more_content.ui.UiTone;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
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
import net.minecraft.world.phys.Vec3;

public class DetonatorItem extends Item {
    public static final double RANGE = 250.0D;
    private static final String LINKED_VEST = "LinkedVest";
    public static final int MAX_CHARGES = 12;

    public static boolean holdsVestLink(ItemStack stack) {
        return boundVest(stack) != null;
    }

    private static final String BOUND = "BoundCharges";
    private static final int COOLDOWN_TICKS = 10;
    private static final int VALIDATE_INTERVAL = 20;

    public DetonatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        boolean claymore = state.getBlock() instanceof ClaymoreBlock;
        if (!(state.getBlock() instanceof C4Block) && !claymore) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();

        if (player != null && player.isShiftKeyDown()) {
            if (!unbind(stack, pos)) {
                say(player, "message.cbc_more_content.detonator.not_paired", UiTone.DANGER);
                return InteractionResult.CONSUME;
            }
            if (level.getBlockEntity(pos) instanceof C4BlockEntity dropped) {
                dropped.setPaired(false);
            }
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 0.9f);
            say(player, "message.cbc_more_content.detonator.unpaired", UiTone.MUTED);
            return InteractionResult.CONSUME;
        }

        // A claymore needs nothing set on it: its blasting cap is wired to the firing device.
        C4BlockEntity charge = level.getBlockEntity(pos) instanceof C4BlockEntity c4 ? c4 : null;
        if (!claymore && (charge == null || !charge.isWaitingOnRemote())) {
            say(player, "message.cbc_more_content.detonator.not_remote", UiTone.DANGER);
            return InteractionResult.CONSUME;
        }

        List<BlockPos> ring = boundCharges(stack);
        if (ring.contains(pos)) {
            say(player, "message.cbc_more_content.detonator.already_paired", UiTone.MUTED);
            return InteractionResult.CONSUME;
        }
        if (ring.size() >= MAX_CHARGES) {
            say(player, "message.cbc_more_content.detonator.full", UiTone.DANGER);
            return InteractionResult.CONSUME;
        }

        ring.add(pos.immutable());
        store(stack, ring);
        if (charge != null) {
            charge.setPaired(true);
        }
        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 1.5f);
        Notices.send(player, UiTone.SUCCESS, "message.cbc_more_content.detonator.paired", ring.size());
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player.isShiftKeyDown() && BombVestItem.isWearing(player)) {
            toggleVestLink(level, player, stack);
            return InteractionResultHolder.success(stack);
        }

        List<BlockPos> ring = boundCharges(stack);
        if (player.isShiftKeyDown()) {
            if (!ring.isEmpty()) {
                for (BlockPos charge : ring) {
                    if (level.isLoaded(charge) && level.getBlockEntity(charge) instanceof C4BlockEntity dropped) {
                        dropped.setPaired(false);
                    }
                }
                clear(stack);
                level.playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.UI_BUTTON_CLICK.value(),
                        SoundSource.PLAYERS,
                        0.6f,
                        0.8f);
                say(player, "message.cbc_more_content.detonator.cleared", UiTone.MUTED);
            }
            return InteractionResultHolder.success(stack);
        }

        if (ring.isEmpty() && boundVest(stack) == null) {
            say(player, "message.cbc_more_content.detonator.unbound", UiTone.MUTED);
            return InteractionResultHolder.success(stack);
        }

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.success(stack);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.9f, 1.6f);

        List<BlockPos> reached = new ArrayList<>();
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos charge : ring) {
            // A charge on a ship lives in the ship's plot, far out in the storage grid: the
            // radio reaches where the ship is, not where its blocks are filed.
            Vec3 where = com.cbc_more_content.compat.SableDropCompat.resolveWorldBlastChecked(
                            server, charge.getCenter())
                    .pos();
            double distanceSqr = player.distanceToSqr(where);
            if (distanceSqr > RANGE * RANGE || !level.isLoaded(charge)) {
                left.add(charge);
            } else {
                reached.add(charge);
            }
        }
        // Claymores first: a charge going off beside one would otherwise set it off itself.
        int fired = 0;
        for (BlockPos at : reached) {
            if (ClaymoreBlock.fireRemote(server, at)) {
                fired++;
            }
        }
        fired += C4BlockEntity.fireRing(server, reached);
        int unreachable = left.size();
        store(stack, left);

        int firedVests = 0;
        java.util.UUID vestId = boundVest(stack);
        if (vestId != null) {
            Entity maybeVest = server.getEntity(vestId);
            if (maybeVest instanceof Player vestWearer && BombVestItem.isWearing(vestWearer)) {
                BombVestItem.detonate(server, vestWearer);
                firedVests = 1;
                storeVestLink(stack, null);
            } else if (maybeVest instanceof Player) {
                storeVestLink(stack, null);
            }
        }

        if (firedVests > 0) {
            say(player, "message.cbc_more_content.detonator.vest_fired", UiTone.SUCCESS);
        }
        if (fired > 0) {
            Notices.send(player, UiTone.SUCCESS, "message.cbc_more_content.detonator.fired", fired);
        } else if (unreachable > 0) {
            say(player, "message.cbc_more_content.detonator.out_of_range", UiTone.DANGER);
        } else if (firedVests == 0) {
            say(player, "message.cbc_more_content.detonator.no_signal", UiTone.DANGER);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || level.getGameTime() % VALIDATE_INTERVAL != 0) {
            return;
        }
        List<BlockPos> ring = boundCharges(stack);
        List<BlockPos> left = new ArrayList<>(ring.size());
        for (BlockPos charge : ring) {
            if (!level.isLoaded(charge)
                    || level.getBlockState(charge).getBlock() instanceof ClaymoreBlock
                    || (level.getBlockEntity(charge) instanceof C4BlockEntity target && target.isWaitingOnRemote())) {
                left.add(charge);
            }
        }
        if (left.size() != ring.size()) {
            store(stack, left);
        }
        if (level instanceof ServerLevel serverLevel) {
            java.util.UUID vestId = boundVest(stack);
            if (vestId != null
                    && serverLevel.getEntity(vestId) instanceof Player vestWearer
                    && !BombVestItem.isWearing(vestWearer)) {
                storeVestLink(stack, null);
            }
        }
    }

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

    @Nullable
    public static java.util.UUID boundVest(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String id = data.copyTag().getString(LINKED_VEST);
        try {
            return id.isEmpty() ? null : java.util.UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void storeVestLink(ItemStack stack, @Nullable java.util.UUID vestId) {
        CompoundTag root = stack.get(DataComponents.CUSTOM_DATA) != null
                ? stack.get(DataComponents.CUSTOM_DATA).copyTag()
                : new CompoundTag();
        if (vestId == null) {
            if (!root.contains(LINKED_VEST)) {
                return;
            }
            root.remove(LINKED_VEST);
            if (root.isEmpty()) {
                clear(stack);
                return;
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            return;
        }
        root.putString(LINKED_VEST, vestId.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static void toggleVestLink(Level level, Player player, ItemStack stack) {
        ItemStack vest = BombVestItem.worn(player);
        if (vest == null) {
            return;
        }
        java.util.UUID self = player.getUUID();
        if (self.equals(boundVest(stack))) {
            storeVestLink(stack, null);
            BombVestItem.syncLinkMirror(player);
            level.playSound(
                    null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 0.9f);
            say(player, "message.cbc_more_content.detonator.vest_unlinked", UiTone.MUTED);
            return;
        }
        storeVestLink(stack, self);
        BombVestItem.syncLinkMirror(player);
        level.playSound(
                null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 1.5f);
        say(player, "message.cbc_more_content.detonator.vest_linked", UiTone.SUCCESS);
    }

    private static void store(ItemStack stack, List<BlockPos> ring) {
        if (ring.isEmpty()) {
            CompoundTag vestRoot = root(stack);
            vestRoot.remove(BOUND);
            if (vestRoot.isEmpty()) {
                clear(stack);
            } else {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(vestRoot));
            }
            return;
        }
        ListTag list = new ListTag();
        for (BlockPos charge : ring) {
            list.add(new IntArrayTag(new int[] {charge.getX(), charge.getY(), charge.getZ()}));
        }
        CompoundTag root = root(stack);
        root.put(BOUND, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static CompoundTag root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }

    private static void say(@Nullable Player player, String key, UiTone tone) {
        Notices.send(player, tone, key);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<BlockPos> ring = boundCharges(stack);
        if (!ring.isEmpty()) {
            tooltip.add(UiTone.ACCENT.text("tooltip.cbc_more_content.detonator.bound", ring.size(), MAX_CHARGES));
            for (BlockPos charge : ring) {
                tooltip.add(Component.literal(" %d, %d, %d".formatted(charge.getX(), charge.getY(), charge.getZ()))
                        .withStyle(UiTone.MUTED.style()));
            }
        }
        if (boundVest(stack) != null) {
            tooltip.add(UiTone.SUCCESS.text("tooltip.cbc_more_content.detonator.vest"));
        }
    }
}
