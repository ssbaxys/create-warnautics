package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class Aim9GameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final TicketType<Long> FIXTURE = TicketType.create("aim9_test", Long::compareTo, 100);

    private static FakePlayer player(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "aim9-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(h.absolutePos(new BlockPos(80, 120, 80)).getCenter());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.AIM9.get(), 2));
        return player;
    }

    private static UseOnContext context(FakePlayer p, BlockPos pos, Direction face) {
        return new UseOnContext(
                p,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        pos.getCenter().add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
                        face,
                        pos,
                        false));
    }

    private static void assertAirframe(GameTestHelper h, BlockPos body, Direction facing) {
        for (int i = -1; i <= 1; i++) {
            var pos = body.relative(facing, i);
            var state = h.getLevel().getBlockState(pos);
            h.assertTrue(state.is(ModBlocks.AIM9.get()), "Complete AIM-9 on " + facing + ", offset=" + i);
            h.assertTrue(
                    state.getValue(Aim9Block.FACING) == facing
                            && Aim9Block.bodyOf(state, pos).equals(body),
                    "Parts agree on body and heading");
            h.assertFalse(state.getCollisionShape(h.getLevel(), pos).isEmpty(), "All parts have their own collision");
        }
        h.assertTrue(
                h.getLevel().getBlockEntity(body) instanceof Aim9BlockEntity be && be.isLiveAirframe(),
                "Live middle renderer");
    }

    @GameTest(template = "empty", batch = "aim9_faces", timeoutTicks = 100)
    public static void aim9PlacesOnEveryFaceAndSneaksHorizontally(GameTestHelper h) {
        int i = 0;
        for (var face : Direction.values()) {
            var support = h.absolutePos(new BlockPos(20 + i++ * 6, 90, 20));
            h.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            var p = player(h);
            p.setXRot(89);
            h.assertTrue(p.getMainHandItem().useOn(context(p, support, face)).consumesAction(), "Place on " + face);
            h.assertTrue(p.getMainHandItem().getCount() == 1, "One item for the whole airframe");
            assertAirframe(h, support.relative(face, 2), face);
            for (int offset = 0; offset <= 3; offset++) {
                h.getLevel().setBlock(support.relative(face, offset), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
        var support = h.absolutePos(new BlockPos(20, 90, 35));
        h.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var p = player(h);
        p.setShiftKeyDown(true);
        p.setYRot(0);
        h.assertTrue(
                p.getMainHandItem().useOn(context(p, support, Direction.UP)).consumesAction(),
                "Sneak places horizontally");
        assertAirframe(h, support.above(), Direction.SOUTH);
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_obstruction", timeoutTicks = 100)
    public static void aim9BlockedPlacementConsumesNothingAndBreakDropsOne(GameTestHelper h) {
        var level = h.getLevel();
        var support = h.absolutePos(new BlockPos(20, 90, 20));
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(support.above(3), Blocks.CHEST.defaultBlockState());
        var p = player(h);
        h.assertFalse(
                p.getMainHandItem().useOn(context(p, support, Direction.UP)).consumesAction(),
                "Obstructed placement fails");
        h.assertTrue(
                p.getMainHandItem().getCount() == 2
                        && level.getBlockState(support.above()).isAir(),
                "No cost or partial frame");
        level.removeBlock(support.above(3), false);
        p.getMainHandItem().useOn(context(p, support, Direction.UP));
        var body = support.above(2);
        assertAirframe(h, body, Direction.UP);
        int drops = 0;
        for (var cell : List.of(body, body.above(), body.below())) {
            drops += Block.getDrops(level.getBlockState(cell), level, cell, level.getBlockEntity(cell)).stream()
                    .filter(stack -> stack.is(ModItems.AIM9.get()))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }
        h.assertTrue(drops == 1, "Only the middle cell drops one AIM-9");
        var old = (Aim9BlockEntity) level.getBlockEntity(body);
        ModBlocks.AIM9.get().playerWillDestroy(level, body.above(), level.getBlockState(body.above()), p);
        level.destroyBlock(body.above(), false);
        for (var cell : List.of(body, body.above(), body.below())) {
            h.assertFalse(level.getBlockState(cell).is(ModBlocks.AIM9.get()), "No orphan after nose break");
        }
        h.assertFalse(old.isLiveAirframe(), "Removed airframe cannot render a ghost");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_wrench", timeoutTicks = 100)
    public static void aim9WrenchPreservesWaterAndReturnsOneItem(GameTestHelper h) {
        var level = h.getLevel();
        var support = h.absolutePos(new BlockPos(20, 90, 20));
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        level.setBlock(support.above(), Blocks.WATER.defaultBlockState(), FLAGS);
        var p = player(h);
        p.getMainHandItem().useOn(context(p, support, Direction.UP));
        var body = support.above(2);
        h.assertTrue(
                level.getBlockState(body.below()).getValue(Aim9Block.WATERLOGGED),
                "Water preserved in clicked tail cell");
        var facing = Direction.UP.getClockWise(Direction.Axis.X);
        level.setBlockAndUpdate(body.relative(facing), Blocks.CHEST.defaultBlockState());
        ModBlocks.AIM9.get().onWrenched(level.getBlockState(body), context(p, body, Direction.EAST));
        assertAirframe(h, body, Direction.UP);
        level.removeBlock(body.relative(facing), false);
        ModBlocks.AIM9.get().onWrenched(level.getBlockState(body), context(p, body, Direction.EAST));
        assertAirframe(h, body, facing);
        h.assertTrue(
                level.getFluidState(body.below()).is(net.minecraft.tags.FluidTags.WATER),
                "Rotation restores displaced source water");
        int before = p.getInventory().countItem(ModItems.AIM9.get());
        var end = body.relative(facing);
        ModBlocks.AIM9.get().onSneakWrenched(level.getBlockState(end), context(p, end, Direction.UP));
        h.assertTrue(
                p.getInventory().countItem(ModItems.AIM9.get()) == before + 1, "Wrench returns exactly one missile");
        for (var cell : List.of(body, body.relative(facing), body.relative(facing.getOpposite()))) {
            h.assertFalse(level.getBlockState(cell).is(ModBlocks.AIM9.get()), "No wrench orphans");
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_sable", timeoutTicks = 100)
    public static void aim9SurvivesSableAssemblyAndRedstoneRemainsInert(GameTestHelper h) {
        var level = h.getLevel();
        var support = h.absolutePos(new BlockPos(40, 100, 40));
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(support), 2, support.asLong());
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var p = player(h);
        p.getMainHandItem().useOn(context(p, support, Direction.UP));
        var body = support.above(2);
        var old = (Aim9BlockEntity) level.getBlockEntity(body);
        var sub = SubLevelAssemblyHelper.assembleBlocks(
                level, body, List.of(body.below(), body, body.above()), new BoundingBox3i(body.below(), body.above()));
        var local = sub.getPlot().getCenterBlock();
        h.assertFalse(old.isLiveAirframe(), "Original renderer entry is inactive after assembly");
        assertAirframe(h, local, Direction.UP);
        level.setBlockAndUpdate(local.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.runAfterDelay(6, () -> {
            assertAirframe(h, local, Direction.UP);
            for (var cell : List.of(local.below(), local, local.above(), local.east())) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            h.succeed();
        });
    }
}
