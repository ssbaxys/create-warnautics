package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.item.BombSettingsKeyItem;
import com.cbc_more_content.item.DropBombItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CBCMoreContent.MOD_ID);

    /**
     * Creative / Simulated aliases first — {@link Block#asItem()} must resolve to
     * {@link #SMALL_BOMB}, so the base bomb is registered last among this group.
     */
    public static final DeferredItem<DropBombItem> SMALL_BOMB_2 = ITEMS.register("small_bomb_2",
            () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(1), 2));

    public static final DeferredItem<DropBombItem> SMALL_BOMB_3 = ITEMS.register("small_bomb_3",
            () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(1), 3));

    public static final DeferredItem<DropBombItem> SMALL_BOMB_4 = ITEMS.register("small_bomb_4",
            () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(1), 4));

    public static final DeferredItem<DropBombItem> SMALL_BOMB = ITEMS.register("small_bomb",
            () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(64)));

    public static final DeferredItem<DropBombItem> SEA_BOMB = ITEMS.register("sea_bomb",
            () -> new DropBombItem(ModBlocks.SEA_BOMB.get(), new Item.Properties().stacksTo(48)));

    public static final DeferredItem<DropBombItem> MEDIUM_BOMB = ITEMS.register("medium_bomb",
            () -> new DropBombItem(ModBlocks.MEDIUM_BOMB.get(), new Item.Properties().stacksTo(32)));

    public static final DeferredItem<DropBombItem> LARGE_BOMB = ITEMS.register("large_bomb",
            () -> new DropBombItem(ModBlocks.LARGE_BOMB.get(), new Item.Properties().stacksTo(16)));

    public static final DeferredItem<BlockItem> SMALL_MINE = ITEMS.register("small_mine",
            () -> new BlockItem(ModBlocks.SMALL_MINE.get(), new Item.Properties().stacksTo(64)));

    public static final DeferredItem<BlockItem> LARGE_MINE = ITEMS.register("large_mine",
            () -> new BlockItem(ModBlocks.LARGE_MINE.get(), new Item.Properties().stacksTo(16)));

    public static final DeferredItem<BombSettingsKeyItem> SETTINGS_KEY = ITEMS.register("settings_key",
            () -> new BombSettingsKeyItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
