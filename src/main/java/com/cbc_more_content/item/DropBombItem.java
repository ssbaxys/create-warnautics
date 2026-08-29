package com.cbc_more_content.item;

import com.cbc_more_content.block.DropBombBlock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;

/**
 * Bomb BlockItem that carries {@link DropBombBlock#CASSETTE} via {@link DataComponents#BLOCK_STATE}.
 * Cassette count is shown visually (item/block models), not as numbers in the name.
 * <p>
 * Release interval is deliberately absent from the tooltip: it is a property of a rack
 * in the world, set with the Settings Key, not of an item sitting in an inventory.
 */
public class DropBombItem extends BlockItem {
    private final int defaultCassette;

    public DropBombItem(Block block, Properties properties) {
        this(block, properties, 1);
    }

    public DropBombItem(Block block, Properties properties, int defaultCassette) {
        super(block, properties);
        this.defaultCassette = DropBombBlock.clampCassette(defaultCassette);
    }

    public int getDefaultCassette() {
        return this.defaultCassette;
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = super.getDefaultInstance();
        if (this.defaultCassette > 1) {
            setCassette(stack, this.defaultCassette);
        }
        return stack;
    }

    public static ItemStack withCassette(Item item, int cassette) {
        ItemStack stack = new ItemStack(item);
        setCassette(stack, cassette);
        return stack;
    }

    public static ItemStack withSettings(Item item, int cassette, int releaseDelay) {
        ItemStack stack = withCassette(item, cassette);
        setReleaseDelay(stack, releaseDelay);
        return stack;
    }

    public static int getCassette(ItemStack stack) {
        BlockItemStateProperties props = stack.get(DataComponents.BLOCK_STATE);
        if (props == null) {
            if (stack.getItem() instanceof DropBombItem bombItem) {
                return bombItem.defaultCassette;
            }
            return 1;
        }
        Integer value = props.get(DropBombBlock.CASSETTE);
        if (value != null) {
            return value;
        }
        if (stack.getItem() instanceof DropBombItem bombItem) {
            return bombItem.defaultCassette;
        }
        return 1;
    }

    public static void setCassette(ItemStack stack, int cassette) {
        int clamped = DropBombBlock.clampCassette(cassette);
        BlockItemStateProperties props = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
        stack.set(DataComponents.BLOCK_STATE, props.with(DropBombBlock.CASSETTE, clamped));
        if (clamped > 1) {
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
        }
    }

    public static int getReleaseDelay(ItemStack stack) {
        BlockItemStateProperties props = stack.get(DataComponents.BLOCK_STATE);
        if (props == null) {
            return DropBombBlock.DEFAULT_RELEASE_DELAY;
        }
        Integer value = props.get(DropBombBlock.RELEASE_DELAY);
        return value == null ? DropBombBlock.DEFAULT_RELEASE_DELAY : DropBombBlock.releaseDelayTicks(value);
    }

    public static void setReleaseDelay(ItemStack stack, int releaseDelay) {
        BlockItemStateProperties props = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
        stack.set(
                DataComponents.BLOCK_STATE,
                props.with(DropBombBlock.RELEASE_DELAY, DropBombBlock.normalizeReleaseDelayTicks(releaseDelay)));
    }
}
