package com.cbc_more_content.registry;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.LandMineBlock;
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

    public static final DeferredBlock<DropBombBlock> SMALL_BOMB = BLOCKS.register("small_bomb",
            () -> new DropBombBlock(bombProps(MapColor.COLOR_GREEN, 0.5f), BombSize.SMALL));

    public static final DeferredBlock<DropBombBlock> SEA_BOMB = BLOCKS.register("sea_bomb",
            () -> new DropBombBlock(bombProps(MapColor.COLOR_LIGHT_BLUE, 0.65f), BombSize.SEA));

    public static final DeferredBlock<DropBombBlock> MEDIUM_BOMB = BLOCKS.register("medium_bomb",
            () -> new DropBombBlock(bombProps(MapColor.TERRACOTTA_GREEN, 0.8f), BombSize.MEDIUM));

    public static final DeferredBlock<DropBombBlock> LARGE_BOMB = BLOCKS.register("large_bomb",
            () -> new DropBombBlock(bombProps(MapColor.COLOR_BLACK, 1.2f), BombSize.LARGE));

    public static final DeferredBlock<LandMineBlock> SMALL_MINE = BLOCKS.register("small_mine",
            () -> new LandMineBlock(mineProps(MapColor.TERRACOTTA_GREEN, 0.4f), MineType.SMALL));

    public static final DeferredBlock<LandMineBlock> LARGE_MINE = BLOCKS.register("large_mine",
            () -> new LandMineBlock(mineProps(MapColor.TERRACOTTA_GRAY, 0.7f), MineType.LARGE));

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

    private ModBlocks() {
    }
}
