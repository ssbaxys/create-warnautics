package com.cbc_more_content;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.item.DropBombItem;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class MoabPlacementGameTests {

    private static MoabBlock bomb() {
        return (MoabBlock) ModBlocks.MOAB.get();
    }

    private static FakePlayer player(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "moab-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(helper.absolutePos(new BlockPos(60, 12, 60)).getCenter());
        var stack = DropBombItem.withSettings(ModItems.MOAB.get(), 1, 40);
        stack.setCount(2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player;
    }

    private static UseOnContext context(FakePlayer player, BlockPos pos, Direction face) {
        return new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        pos.getCenter().add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5),
                        face,
                        pos,
                        false));
    }

    private static InteractionResult place(FakePlayer player, BlockPos support, Direction face) {
        return player.getMainHandItem().getItem().useOn(context(player, support, face));
    }

    private static void assertAirframe(GameTestHelper helper, BlockPos body, Direction facing) {
        for (int offset = -1; offset <= 1; offset++) {
            var state = helper.getLevel().getBlockState(body.relative(facing, offset));
            helper.assertTrue(state.is(bomb()), "All three cells must exist: " + facing + " offset=" + offset);
            var part = offset == -1 ? MoabBlock.Part.TAIL : offset == 1 ? MoabBlock.Part.NOSE : MoabBlock.Part.BODY;
            helper.assertTrue(
                    state.getValue(MoabBlock.PART) == part && state.getValue(DropBombBlock.FACING) == facing,
                    "Model, collision and body lookup must agree");
            helper.assertTrue(state.getValue(DropBombBlock.RELEASE_DELAY) == 40, "All cells retain the release delay");
        }
    }

    @GameTest(template = "empty", batch = "moab_faces", timeoutTicks = 80)
    public static void placementOnEveryFaceIgnoresCameraPitch(GameTestHelper helper) {
        int index = 0;
        for (Direction face : Direction.values()) {
            var support = helper.absolutePos(new BlockPos(6 + index++ * 6, 12, 12));
            helper.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            var player = player(helper);
            player.setXRot(face.getAxis().isVertical() ? 0 : 89);
            helper.assertTrue(place(player, support, face).consumesAction(), "Place on " + face);
            helper.assertTrue(player.getMainHandItem().getCount() == 1, "A whole airframe costs one item");
            assertAirframe(helper, support.relative(face, 2), face);
        }
        helper.runAfterDelay(5, () -> {
            int i = 0;
            for (Direction face : Direction.values()) {
                var support = helper.absolutePos(new BlockPos(6 + i++ * 6, 12, 12));
                assertAirframe(helper, support.relative(face, 2), face);
                for (int distance = 0; distance <= 3; distance++) {
                    helper.getLevel()
                            .setBlock(
                                    support.relative(face, distance),
                                    Blocks.AIR.defaultBlockState(),
                                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "moab_horizontal", timeoutTicks = 80)
    public static void sneakingPlacesHorizontallyAndCanShiftAtAnEdge(GameTestHelper helper) {
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        helper.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var player = player(helper);
        player.setShiftKeyDown(true);
        player.setYRot(0);
        player.setXRot(85);
        var facing = player.getDirection();
        var anchor = support.above();
        helper.getLevel().setBlockAndUpdate(anchor.relative(facing.getOpposite()), Blocks.STONE.defaultBlockState());
        helper.assertTrue(place(player, support, Direction.UP).consumesAction(), "Horizontal placement at an edge");
        assertAirframe(helper, anchor.relative(facing), facing);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "moab_obstructed", timeoutTicks = 80)
    public static void blockedPlacementAndBuildLimitDoNotConsumeOrOverwrite(GameTestHelper helper) {
        var level = helper.getLevel();
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(support.above(3), Blocks.CHEST.defaultBlockState());
        var player = player(helper);
        helper.assertFalse(
                place(player, support, Direction.UP).consumesAction(), "No partial placement through a chest");
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "Blocked placement retains item");
        helper.assertTrue(
                level.getBlockState(support.above()).isAir()
                        && level.getBlockState(support.above(2)).isAir(),
                "Blocked placement leaves no orphan cells");
        helper.assertTrue(level.getBlockState(support.above(3)).is(Blocks.CHEST), "Obstacle must survive");
        var top = new BlockPos(support.getX(), level.getMaxBuildHeight() - 3, support.getZ());
        level.setBlockAndUpdate(top, Blocks.STONE.defaultBlockState());
        helper.assertFalse(
                place(player, top, Direction.UP).consumesAction(), "All segments must fit below build limit");
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "Build limit retains item");
        level.removeBlock(top, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "moab_collision", timeoutTicks = 80)
    public static void entityAtFarEndPreventsPlacement(GameTestHelper helper) {
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        helper.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var pig = EntityType.PIG.create(helper.getLevel());
        pig.setPos(support.above(3).getBottomCenter());
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        var player = player(helper);
        helper.assertFalse(place(player, support, Direction.UP).consumesAction(), "Far end must not intersect a mob");
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "Entity obstruction retains item");
        pig.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "moab_rotation", timeoutTicks = 80)
    public static void wrenchPreflightsThenRotatesWithoutOrphans(GameTestHelper helper) {
        var level = helper.getLevel();
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var player = player(helper);
        place(player, support, Direction.UP);
        var body = support.above(2);
        var facing = Direction.UP.getClockWise(Direction.Axis.X);
        var obstacle = body.relative(facing);
        level.setBlockAndUpdate(obstacle, Blocks.CHEST.defaultBlockState());
        var clicked = body.above();
        bomb().onWrenched(level.getBlockState(clicked), context(player, clicked, Direction.EAST));
        assertAirframe(helper, body, Direction.UP);
        helper.assertTrue(level.getBlockState(obstacle).is(Blocks.CHEST), "Blocked rotation preserves obstacle");
        level.removeBlock(obstacle, false);
        bomb().onWrenched(level.getBlockState(clicked), context(player, clicked, Direction.EAST));
        assertAirframe(helper, body, facing);
        helper.assertTrue(
                level.getBlockState(body.above()).isAir()
                        && level.getBlockState(body.below()).isAir(),
                "Successful rotation removes old end cells");
        helper.runAfterDelay(5, () -> {
            assertAirframe(helper, body, facing);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "moab_water", timeoutTicks = 80)
    public static void waterIsPreservedPerCellAndAfterRotation(GameTestHelper helper) {
        var level = helper.getLevel();
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        level.setBlock(
                support.above(), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var player = player(helper);
        place(player, support, Direction.UP);
        var body = support.above(2);
        assertAirframe(helper, body, Direction.UP);
        helper.assertTrue(
                level.getBlockState(body.below()).getValue(DropBombBlock.WATERLOGGED), "Submerged tail retains water");
        helper.assertFalse(level.getBlockState(body).getValue(DropBombBlock.WATERLOGGED), "Dry body stays dry");
        helper.assertFalse(level.getBlockState(body.above()).getValue(DropBombBlock.WATERLOGGED), "Dry nose stays dry");
        var facing = Direction.UP.getClockWise(Direction.Axis.X);
        level.setBlock(
                body.relative(facing),
                Blocks.WATER.defaultBlockState(),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        bomb().onWrenched(level.getBlockState(body), context(player, body, Direction.EAST));
        assertAirframe(helper, body, facing);
        helper.assertTrue(level.getBlockState(body.below()).is(Blocks.WATER), "Vacated wet tail restores water");
        helper.assertTrue(
                level.getBlockState(body.relative(facing)).getValue(DropBombBlock.WATERLOGGED),
                "New submerged nose retains water");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "moab_sublevel", timeoutTicks = 80)
    public static void placesOnSableSublevelAndDismantlesAsOneItem(GameTestHelper helper) {
        var level = helper.getLevel();
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        var hull = SubLevelAssemblyHelper.assembleBlocks(
                level, support, List.of(support), new BoundingBox3i(support, support));
        helper.runAfterDelay(5, () -> {
            var local = hull.getPlot().getCenterBlock();
            var player = player(helper);
            helper.assertTrue(place(player, local, Direction.UP).consumesAction(), "Placement on sublevel");
            var body = local.above(2);
            assertAirframe(helper, body, Direction.UP);
            var tail = local.above();
            bomb().onSneakWrenched(level.getBlockState(tail), context(player, tail, Direction.UP));
            for (int y = 1; y <= 3; y++) {
                helper.assertTrue(level.getBlockState(local.above(y)).isAir(), "Dismantling removes all segments");
            }
            int count = 0;
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(ModItems.MOAB.get())) {
                    count += stack.getCount();
                    helper.assertTrue(DropBombItem.getReleaseDelay(stack) == 40, "Recovered item retains delay");
                }
            }
            helper.assertTrue(count == 2, "Exactly one item is recovered, with no duplication");
            level.removeBlock(local, false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "moab_segment_power", timeoutTicks = 80)
    public static void powerOnAnySegmentReleasesExactlyOneWholeBomb(GameTestHelper helper) {
        // Keep the powered block inside the GameTest's ticking chunk. A region
        // ticket on a distant fixture loads it but does not run scheduled block ticks.
        var support = helper.absolutePos(new BlockPos(2, 8, 2));
        // Exercise each segment in turn so an earlier projectile cannot disturb
        // another fixture's redstone before its assertion runs.
        helper.runAfterDelay(5, () -> powerOneSegment(helper, support, -1));
    }

    private static void powerOneSegment(GameTestHelper helper, BlockPos support, int offset) {
        var level = helper.getLevel();
        for (int y = 1; y <= 3; y++) {
            level.removeBlock(support.above(y).east(), false);
            level.removeBlock(support.above(y), false);
        }
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        helper.assertTrue(place(player(helper), support, Direction.UP).consumesAction(), "Place before powering");
        var body = support.above(2);
        assertAirframe(helper, body, Direction.UP);
        var signal = body.offset(1, offset, 0);
        level.setBlockAndUpdate(signal, Blocks.REDSTONE_BLOCK.defaultBlockState());
        var poweredPart = body.offset(0, offset, 0);
        helper.assertTrue(
                DropBombBlock.isReceivingPower(level, poweredPart),
                "Powered segment must detect adjacent redstone: " + offset);
        helper.assertTrue(
                level.getBlockState(poweredPart).getValue(DropBombBlock.POWERED),
                "Powered segment must schedule launch: " + offset);
        helper.assertTrue(
                level.getBlockTicks().hasScheduledTick(poweredPart, bomb()),
                "Powered segment must have a scheduled block tick: " + offset);
        long start = level.getGameTime();
        helper.runAfterDelay(10, () -> {
            for (int y = -1; y <= 1; y++) {
                var part = body.offset(0, y, 0);
                helper.assertFalse(
                        level.getBlockState(part).is(bomb()),
                        "Released bomb leaves no segments: powered=" + offset + ", remaining=" + y + ", state="
                                + level.getBlockState(part) + ", signal=" + level.getBlockState(signal)
                                + ", receiving=" + DropBombBlock.isReceivingPower(level, part)
                                + ", ticking=" + level.isPositionEntityTicking(part)
                                + ", scheduled=" + level.getBlockTicks().hasScheduledTick(poweredPart, bomb())
                                + ", elapsed=" + (level.getGameTime() - start));
            }
            var projectiles = level.getEntitiesOfClass(
                    DropBombProjectile.class,
                    new AABB(body).inflate(3),
                    entity -> entity.getType() == ModEntityTypes.MOAB.get());
            helper.assertTrue(projectiles.size() == 1, "Each powered segment releases one projectile: " + offset);
            projectiles.forEach(DropBombProjectile::discard);
            level.removeBlock(signal, false);
            level.removeBlock(support, false);
            if (offset < 1) {
                helper.runAfterDelay(2, () -> powerOneSegment(helper, support, offset + 1));
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", batch = "moab_sublevel_power", timeoutTicks = 80)
    public static void placementOnPoweredSublevelReleasesFromTail(GameTestHelper helper) {
        var level = helper.getLevel();
        var support = helper.absolutePos(new BlockPos(12, 8, 12));
        level.setBlockAndUpdate(support, Blocks.REDSTONE_BLOCK.defaultBlockState());
        var hull = SubLevelAssemblyHelper.assembleBlocks(
                level, support, List.of(support), new BoundingBox3i(support, support));
        helper.runAfterDelay(5, () -> {
            var local = hull.getPlot().getCenterBlock();
            helper.assertTrue(place(player(helper), local, Direction.UP).consumesAction(), "Place on powered sublevel");
            helper.runAfterDelay(4, () -> {
                for (int y = 1; y <= 3; y++) {
                    helper.assertFalse(
                            level.getBlockState(local.above(y)).is(bomb()), "Powered sublevel releases entire bomb");
                }
                var world = hull.logicalPose().transformPosition(local.above(2).getCenter());
                var projectiles = level.getEntitiesOfClass(
                        DropBombProjectile.class,
                        new AABB(world, world).inflate(6),
                        entity -> entity.getType() == ModEntityTypes.MOAB.get());
                helper.assertTrue(projectiles.size() == 1, "Sublevel releases one world-space projectile");
                projectiles.forEach(DropBombProjectile::discard);
                level.removeBlock(local, false);
                helper.succeed();
            });
        });
    }
}
