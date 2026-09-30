package com.cbc_more_content.item;

import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
    private static final String GROUP = "BoundMissiles";
    public static final int MAX_GROUP = 8;

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
            if (level.getBlockEntity(body) instanceof CruiseMissileBlockEntity missile) {
                int count = addBinding(context.getItemInHand(), body, missile.missileId(), level);
                if (count < 0) {
                    if (context.getPlayer() != null) {
                        context.getPlayer()
                                .displayClientMessage(
                                        Component.translatable("message.cbc_more_content.designator.full", MAX_GROUP)
                                                .withStyle(ChatFormatting.RED),
                                        true);
                    }
                    return InteractionResult.CONSUME;
                }
                missile.armRemote();
                if (context.getPlayer() != null) {
                    context.getPlayer()
                            .displayClientMessage(
                                    Component.translatable(
                                                    "message.cbc_more_content.designator.bound", count, MAX_GROUP)
                                            .withStyle(ChatFormatting.GREEN),
                                    true);
                }
            }
            level.playSound(null, body, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.7f, 1.6f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static void bind(ItemStack stack, BlockPos missile) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", missile.getX());
        tag.putInt("Y", missile.getY());
        tag.putInt("Z", missile.getZ());
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        list.add(tag);
        root.put(GROUP, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    /** Adds or refreshes one missile. The cap bounds both packet work and sub-level searches. */
    private static int addBinding(ItemStack stack, BlockPos pos, UUID id, Level level) {
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = existing == null ? new CompoundTag() : existing.copyTag();
        ListTag list = entries(root);
        CompoundTag binding = location(pos);
        binding.putUUID("Id", id);
        binding.putString("Dimension", level.dimension().location().toString());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag old = list.getCompound(i);
            if ((old.hasUUID("Id") && id.equals(old.getUUID("Id")))
                    || (position(old).equals(pos)
                            && old.getString("Dimension").equals(binding.getString("Dimension")))) {
                list.set(i, binding);
                root.put(GROUP, list);
                root.remove(BOUND);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
                return list.size();
            }
        }
        if (list.size() >= MAX_GROUP) {
            return -1;
        }
        list.add(binding);
        root.put(GROUP, list);
        root.remove(BOUND);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return list.size();
    }

    private static CompoundTag location(BlockPos pos) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("X", pos.getX());
        entry.putInt("Y", pos.getY());
        entry.putInt("Z", pos.getZ());
        return entry;
    }

    private static BlockPos position(CompoundTag entry) {
        return new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
    }

    private static ListTag entries(CompoundTag root) {
        if (root.contains(GROUP, Tag.TAG_LIST)) {
            return root.getList(GROUP, Tag.TAG_COMPOUND);
        }
        ListTag legacy = new ListTag();
        if (root.contains(BOUND, Tag.TAG_COMPOUND)) {
            legacy.add(root.getCompound(BOUND).copy());
        }
        return legacy;
    }

    /** The missile this designator is paired with, or null while it is unbound. */
    @Nullable
    public static BlockPos boundMissile(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        ListTag list = entries(data.copyTag());
        return list.isEmpty() ? null : position(list.getCompound(0));
    }

    public static int boundCount(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : entries(data.copyTag()).size();
    }

    /** Assembly preserves the missile's identity while moving it to a different storage address. */
    @Nullable
    public static BlockPos resolveBoundMissile(Level level, ItemStack stack) {
        List<BlockPos> resolved = resolveBoundMissiles(level, stack);
        return resolved.isEmpty() ? null : resolved.getFirst();
    }

    /** Resolves every live group member, including missiles moved into Sable plots. */
    public static List<BlockPos> resolveBoundMissiles(Level level, ItemStack stack) {
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        if (existing == null) {
            return List.of();
        }
        CompoundTag root = existing.copyTag();
        ListTag list = entries(root);
        List<BlockPos> found = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < Math.min(MAX_GROUP, list.size()); i++) {
            CompoundTag binding = list.getCompound(i);
            if (binding.contains("Dimension")
                    && !binding.getString("Dimension")
                            .equals(level.dimension().location().toString())) {
                continue;
            }
            BlockPos resolved = resolveOne(level, binding);
            if (resolved != null) {
                found.add(resolved);
                if (!resolved.equals(position(binding))) {
                    binding.putInt("X", resolved.getX());
                    binding.putInt("Y", resolved.getY());
                    binding.putInt("Z", resolved.getZ());
                    changed = true;
                }
            }
        }
        if (changed) {
            root.put(GROUP, list);
            root.remove(BOUND);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
        return found;
    }

    @Nullable
    private static BlockPos resolveOne(Level level, CompoundTag binding) {
        BlockPos stored = position(binding);
        UUID id = binding.hasUUID("Id") ? binding.getUUID("Id") : null;
        if (level.isLoaded(stored)
                && level.getBlockEntity(stored) instanceof CruiseMissileBlockEntity missile
                && (id == null || id.equals(missile.missileId()))) {
            return stored;
        }
        if (id == null) {
            return null;
        }
        var container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (var sub : container.getAllSubLevels()) {
                if (sub.isRemoved()) {
                    continue;
                }
                for (var chunk : sub.getPlot().getLoadedChunks()) {
                    for (var be : chunk.getChunk().getBlockEntities().values()) {
                        if (be instanceof CruiseMissileBlockEntity missile
                                && !missile.isRemoved()
                                && id.equals(missile.missileId())) {
                            return missile.getBlockPos();
                        }
                    }
                }
            }
        }
        return null;
    }

    public static void removeLaunched(ItemStack stack, List<BlockPos> launched) {
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        if (existing == null || launched.isEmpty()) {
            return;
        }
        CompoundTag root = existing.copyTag();
        ListTag old = entries(root);
        ListTag kept = new ListTag();
        for (int i = 0; i < old.size(); i++) {
            if (!launched.contains(position(old.getCompound(i)))) {
                kept.add(old.getCompound(i).copy());
            }
        }
        root.put(GROUP, kept);
        root.remove(BOUND);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int count = boundCount(stack);
        if (count > 0) {
            tooltip.add(Component.translatable("tooltip.cbc_more_content.target_designator.group", count, MAX_GROUP)
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
