package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.LandMineBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.block.SirenBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.mine.MineType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CBCMoreContent.MOD_ID);

    public static final DeferredBlock<DropBombBlock> SMALL_BOMB = BLOCKS.register(
            "small_bomb", () -> new DropBombBlock(bombProps(MapColor.COLOR_GREEN, 0.5f), BombSize.SMALL));

    public static final DeferredBlock<DropBombBlock> SEA_BOMB = BLOCKS.register(
            "sea_bomb", () -> new DropBombBlock(bombProps(MapColor.COLOR_LIGHT_BLUE, 0.65f), BombSize.SEA));

    public static final DeferredBlock<DropBombBlock> MEDIUM_BOMB = BLOCKS.register(
            "medium_bomb", () -> new DropBombBlock(bombProps(MapColor.TERRACOTTA_GREEN, 0.8f), BombSize.MEDIUM));

    public static final DeferredBlock<DropBombBlock> LARGE_BOMB = BLOCKS.register(
            "large_bomb", () -> new DropBombBlock(bombProps(MapColor.COLOR_BLACK, 1.2f), BombSize.LARGE));

    public static final DeferredBlock<MoabBlock> MOAB =
            BLOCKS.register("moab", () -> new MoabBlock(bombProps(MapColor.COLOR_BLACK, 2.0f), BombSize.MOAB));

    public static final DeferredBlock<LandMineBlock> SMALL_MINE = BLOCKS.register(
            "small_mine", () -> new LandMineBlock(mineProps(MapColor.TERRACOTTA_GREEN, 0.4f), MineType.SMALL));

    /** Antipersonnel charge that throws itself to waist height before it bursts. */
    public static final DeferredBlock<LandMineBlock> BOUNDING_MINE = BLOCKS.register(
            "bounding_mine", () -> new LandMineBlock(mineProps(MapColor.TERRACOTTA_BROWN, 0.4f), MineType.BOUNDING));

    public static final DeferredBlock<LandMineBlock> LARGE_MINE = BLOCKS.register(
            "large_mine", () -> new LandMineBlock(mineProps(MapColor.TERRACOTTA_GRAY, 0.7f), MineType.LARGE));

    /**
     * Airframe only for now: it places, breaks and drops itself, and does nothing else.
     * No warhead, no guidance, no launch. Three blocks long, matching the model.
     */
    public static final DeferredBlock<CruiseMissileBlock> CRUISE_MISSILE =
            BLOCKS.register("cruise_missile", () -> new CruiseMissileBlock(bombProps(MapColor.METAL, 1.0f)));

    /**
     * Breaching charge stuck to a surface. Tough enough that breaking it takes a moment,
     * which is what gives a live charge time to go off in the hands of whoever tries.
     */
    public static final DeferredBlock<C4Block> C4 = BLOCKS.register(
            "c4",
            () -> new C4Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SAND)
                    .strength(1.5f)
                    .sound(SoundType.WOOL)
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY)
                    .isRedstoneConductor((state, level, pos) -> false)));

    /** Air-raid post. Solid enough to survive what it is warning about, mostly. */
    public static final DeferredBlock<SirenBlock> SIREN = BLOCKS.register(
            "siren",
            () -> new SirenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    private static BlockBehaviour.Properties bombProps(MapColor color, float strength) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(strength)
                .sound(SoundType.METAL)
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY)
                .isRedstoneConductor((state, level, pos) -> false);
    }

    private static BlockBehaviour.Properties mineProps(MapColor color, float strength) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(strength)
                .sound(SoundType.METAL)
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY)
                .isRedstoneConductor((state, level, pos) -> false);
    }

    private ModBlocks() {}
}
