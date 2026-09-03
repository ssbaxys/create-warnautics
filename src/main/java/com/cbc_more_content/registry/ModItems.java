package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.item.BombSettingsKeyItem;
import com.cbc_more_content.item.C4Item;
import com.cbc_more_content.item.CruiseMissileItem;
import com.cbc_more_content.item.DetonatorItem;
import com.cbc_more_content.item.DropBombItem;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.item.TripwireCoilItem;
import com.cbc_more_content.item.WireCuttersItem;
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
    public static final DeferredItem<DropBombItem> SMALL_BOMB_2 = ITEMS.register(
            "small_bomb_2", () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(1), 2));

    public static final DeferredItem<DropBombItem> SMALL_BOMB_3 = ITEMS.register(
            "small_bomb_3", () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(1), 3));

    public static final DeferredItem<DropBombItem> SMALL_BOMB_4 = ITEMS.register(
            "small_bomb_4", () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(1), 4));

    public static final DeferredItem<DropBombItem> SMALL_BOMB = ITEMS.register(
            "small_bomb", () -> new DropBombItem(ModBlocks.SMALL_BOMB.get(), new Item.Properties().stacksTo(64)));

    public static final DeferredItem<DropBombItem> SEA_BOMB = ITEMS.register(
            "sea_bomb", () -> new DropBombItem(ModBlocks.SEA_BOMB.get(), new Item.Properties().stacksTo(48)));

    public static final DeferredItem<DropBombItem> MEDIUM_BOMB = ITEMS.register(
            "medium_bomb", () -> new DropBombItem(ModBlocks.MEDIUM_BOMB.get(), new Item.Properties().stacksTo(32)));

    public static final DeferredItem<DropBombItem> LARGE_BOMB = ITEMS.register(
            "large_bomb", () -> new DropBombItem(ModBlocks.LARGE_BOMB.get(), new Item.Properties().stacksTo(16)));

    public static final DeferredItem<DropBombItem> MOAB =
            ITEMS.register("moab", () -> new DropBombItem(ModBlocks.MOAB.get(), new Item.Properties().stacksTo(4)));

    public static final DeferredItem<BlockItem> SMALL_MINE = ITEMS.register(
            "small_mine", () -> new BlockItem(ModBlocks.SMALL_MINE.get(), new Item.Properties().stacksTo(64)));

    public static final DeferredItem<BlockItem> BOUNDING_MINE = ITEMS.register(
            "bounding_mine", () -> new BlockItem(ModBlocks.BOUNDING_MINE.get(), new Item.Properties().stacksTo(64)));

    public static final DeferredItem<BlockItem> LARGE_MINE = ITEMS.register(
            "large_mine", () -> new BlockItem(ModBlocks.LARGE_MINE.get(), new Item.Properties().stacksTo(16)));

    /** Three-cell guided airframe; the guidance package lives on its middle cell. */
    public static final DeferredItem<BlockItem> SIREN =
            ITEMS.register("siren", () -> new BlockItem(ModBlocks.SIREN.get(), new Item.Properties().stacksTo(16)));

    public static final DeferredItem<CruiseMissileItem> CRUISE_MISSILE = ITEMS.register(
            "cruise_missile",
            () -> new CruiseMissileItem(ModBlocks.CRUISE_MISSILE.get(), new Item.Properties().stacksTo(8)));

    /**
     * The mod's main theme as a playable record. The song itself is defined by the
     * jukebox_song datapack entry; the item only points at it.
     */
    public static final DeferredItem<Item> MUSIC_DISC_BREAKER_OF_SKIES = ITEMS.register(
            "music_disc_breaker_of_skies",
            () -> new Item(new Item.Properties()
                    .stacksTo(1)
                    .rarity(net.minecraft.world.item.Rarity.RARE)
                    .jukeboxPlayable(net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.JUKEBOX_SONG,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    CBCMoreContent.MOD_ID, "breaker_of_skies")))));

    /** One per slot: a charge is a placement, not ammunition to be carried in bulk. */
    public static final DeferredItem<C4Item> C4 =
            ITEMS.register("c4", () -> new C4Item(new Item.Properties().stacksTo(1)));

    /** Pairs with a missile, then paints a hull for it to chase. */
    public static final DeferredItem<TargetDesignatorItem> TARGET_DESIGNATOR =
            ITEMS.register("target_designator", () -> new TargetDesignatorItem(new Item.Properties().stacksTo(1)));

    /** A coil of tripwire: tie one post, then another within eight blocks. */
    public static final DeferredItem<TripwireCoilItem> TRIPWIRE_COIL =
            ITEMS.register("tripwire_coil", () -> new TripwireCoilItem(new Item.Properties().stacksTo(16)));

    /** Fires a charge that was set to remote, from up to 250 blocks away. */
    public static final DeferredItem<DetonatorItem> DETONATOR =
            ITEMS.register("detonator", () -> new DetonatorItem(new Item.Properties().stacksTo(1)));

    /** Opens the wire panel on a live charge. */
    public static final DeferredItem<WireCuttersItem> WIRE_CUTTERS = ITEMS.register(
            "wire_cutters",
            () -> new WireCuttersItem(new Item.Properties().stacksTo(1).durability(64)));

    public static final DeferredItem<BombSettingsKeyItem> SETTINGS_KEY =
            ITEMS.register("settings_key", () -> new BombSettingsKeyItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}
}
