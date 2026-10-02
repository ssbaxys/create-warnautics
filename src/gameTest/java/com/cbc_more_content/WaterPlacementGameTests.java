package com.cbc_more_content;

import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class WaterPlacementGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static FakePlayer player(GameTestHelper h, Item item) {
        var p = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "water-test"));
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(h.absolutePos(new BlockPos(150, 150, 150)).getCenter());
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 64));
        return p;
    }

    private static UseOnContext context(FakePlayer p, BlockPos support, Direction face) {
        return new UseOnContext(
                p,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        support.getCenter().add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
                        face,
                        support,
                        false));
    }

    @GameTest(template = "empty", batch = "water_airframe", timeoutTicks = 100)
    public static void airframesKeepEachOriginalSourceWithoutCopyingOrPromotingFlow(GameTestHelper h) {
        int scenario = 0;
        for (Item item : List.of(ModItems.CRUISE_MISSILE.get(), ModItems.AIM9.get(), ModItems.MOAB.get())) {
            for (Direction face : Direction.values()) {
                for (int wetCell = 1; wetCell <= 3; wetCell++) {
                    var support = h.absolutePos(new BlockPos(20 + scenario++ * 5, 100, 20));
                    var level = h.getLevel();
                    level.setBlock(support, Blocks.STONE.defaultBlockState(), FLAGS);
                    for (int i = 1; i <= 3; i++) {
                        level.setBlock(
                                support.relative(face, i),
                                i == wetCell
                                        ? Blocks.WATER.defaultBlockState()
                                        : i == wetCell % 3 + 1
                                                ? Fluids.FLOWING_WATER
                                                        .getFlowing(4, false)
                                                        .createLegacyBlock()
                                                : Blocks.AIR.defaultBlockState(),
                                FLAGS);
                    }
                    var p = player(h, item);
                    h.assertTrue(
                            item.useOn(context(p, support, face)).consumesAction(), "Place " + item + " on " + face);
                    for (int i = 1; i <= 3; i++) {
                        var cell = support.relative(face, i);
                        var state = level.getBlockState(cell);
                        h.assertTrue(state.hasProperty(BlockStateProperties.WATERLOGGED), "Complete airframe " + item);
                        h.assertTrue(
                                state.getValue(BlockStateProperties.WATERLOGGED) == (i == wetCell),
                                "Only original source water survives: item=" + item + " face=" + face + " cell=" + i);
                    }
                    // A bucket must not extract a new source from the previously dry/flowing parts.
                    for (int i = 1; i <= 3; i++) {
                        var cell = support.relative(face, i);
                        var state = level.getBlockState(cell);
                        if (!(state.getBlock() instanceof SimpleWaterloggedBlock waterlogged)) {
                            continue;
                        }
                        h.assertTrue(
                                waterlogged
                                                .pickupBlock(null, level, cell, state)
                                                .isEmpty()
                                        == (i != wetCell),
                                "Exactly one existing bucket, no duplicated sources");
                    }
                    for (int i = 0; i <= 3; i++) {
                        level.setBlock(support.relative(face, i), Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "water_single", timeoutTicks = 100)
    public static void singleChargesNeverTurnFlowingWaterIntoBucketSources(GameTestHelper h) {
        int scenario = 0;
        for (Item item : List.of(
                ModItems.SMALL_BOMB.get(),
                ModItems.MEDIUM_BOMB.get(),
                ModItems.LARGE_BOMB.get(),
                ModItems.SEA_BOMB.get(),
                ModItems.CHAIN_CONNECTOR.get(),
                ModItems.SEA_MINE.get())) {
            var p = player(h, item);
            var block = ((net.minecraft.world.item.BlockItem) item).getBlock();
            for (boolean source : List.of(false, true)) {
                var support = h.absolutePos(new BlockPos(20 + scenario++ * 5, 100, 50));
                var cell = support.above();
                h.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), FLAGS);
                h.getLevel()
                        .setBlock(
                                cell,
                                source
                                        ? Blocks.WATER.defaultBlockState()
                                        : Fluids.FLOWING_WATER
                                                .getFlowing(4, false)
                                                .createLegacyBlock(),
                                FLAGS);
                var placed = block.getStateForPlacement(new BlockPlaceContext(context(p, support, Direction.UP)));
                h.assertTrue(
                        placed == null ? !source : placed.getValue(BlockStateProperties.WATERLOGGED) == source,
                        "Correct source accounting for " + item);
            }
        }
        h.succeed();
    }
}
