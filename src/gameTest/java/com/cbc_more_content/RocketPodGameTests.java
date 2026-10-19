package com.cbc_more_content;

import com.cbc_more_content.block.RocketPodBlock;
import com.cbc_more_content.block.RocketPodBlockEntity;
import com.cbc_more_content.munitions.RocketProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class RocketPodGameTests {
    /** Middle block of a pod facing east, near the west edge of the plot. */
    private static final BlockPos BODY = new BlockPos(3, 2, 12);

    /** Sets one loose section, the way the item does, and lets it join up if it can. */
    private static void section(ServerLevel level, BlockPos at, Direction facing) {
        var block = (RocketPodBlock) ModBlocks.ROCKET_POD.get();
        var state = block.defaultBlockState().setValue(RocketPodBlock.FACING, facing);
        level.setBlock(at, state, 3);
        block.setPlacedBy(level, at, state, null, ItemStack.EMPTY);
    }

    /** Three sections laid rear to front around {@code body}; returns the pod's magazine. */
    private static RocketPodBlockEntity pod(ServerLevel level, BlockPos body, Direction facing) {
        section(level, body.relative(facing, -1), facing);
        section(level, body, facing);
        section(level, body.relative(facing, 1), facing);
        return (RocketPodBlockEntity) level.getBlockEntity(body);
    }

    private static RocketPodBlockEntity pod(GameTestHelper helper, BlockPos body, Direction facing) {
        return pod(helper.getLevel(), helper.absolutePos(body), facing);
    }

    private static java.util.List<RocketProjectile> rockets(GameTestHelper helper) {
        return helper.getLevel()
                .getEntitiesOfClass(
                        RocketProjectile.class, new AABB(helper.absolutePos(BODY)).inflate(40), e -> e.isAlive());
    }

    /** A pulse fires everything loaded down the tubes' line, and the wall in front takes it. */
    @GameTest(template = "empty", batch = "rocket_pod_salvo", timeoutTicks = 120)
    public static void redstoneFiresASalvoDownrange(GameTestHelper helper) {
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(0, 1, 6), new BlockPos(22, 1, 18))) {
            helper.setBlock(p, Blocks.STONE);
        }
        int wall = 0;
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(19, 2, 8), new BlockPos(19, 6, 16))) {
            helper.setBlock(p, Blocks.STONE);
            wall++;
        }
        int wallBlocks = wall;
        var pod = pod(helper, BODY, Direction.EAST);
        helper.assertBlockPresent(ModBlocks.ROCKET_POD.get(), BODY.east());
        helper.assertBlockPresent(ModBlocks.ROCKET_POD.get(), BODY.west());
        helper.assertTrue(pod.load(20) == 8, "A pod holds eight rockets by default");
        helper.runAfterDelay(2, () -> helper.setBlock(BODY.west().north(), Blocks.REDSTONE_BLOCK));
        helper.runAfterDelay(6, () -> {
            var flying = rockets(helper);
            helper.assertFalse(flying.isEmpty(), "Rockets are in the air");
            for (var rocket : flying) {
                Vec3 v = rocket.getDeltaMovement();
                helper.assertTrue(
                        v.x > 1.0 && Math.abs(v.z) < 0.25 * v.x, "Rockets fly east along the tubes, got " + v);
            }
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(pod.rockets() == 0, "The whole load went out, left " + pod.rockets());
            int standing = 0;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(19, 2, 8), new BlockPos(19, 6, 16))) {
                if (helper.getBlockState(p).is(Blocks.STONE)) {
                    standing++;
                }
            }
            helper.assertTrue(standing < wallBlocks, "The wall in front was hit: " + standing + "/" + wallBlocks);
            helper.assertTrue(rockets(helper).isEmpty(), "No rocket is left hanging in the air");
            helper.succeed();
        });
    }

    /** No rockets: the circuit clicks and nothing leaves the tubes. */
    @GameTest(template = "empty", batch = "rocket_pod_empty", timeoutTicks = 40)
    public static void emptyPodDoesNotFire(GameTestHelper helper) {
        var pod = pod(helper, BODY, Direction.EAST);
        helper.setBlock(BODY.north(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(pod.rockets() == 0 && rockets(helper).isEmpty(), "Nothing fired");
            helper.succeed();
        });
    }

    /** A hopper on top feeds rockets in through any block of the pod, and nothing else. */
    @GameTest(template = "empty", batch = "rocket_pod_hopper", timeoutTicks = 200)
    public static void hopperLoadsRockets(GameTestHelper helper) {
        var pod = pod(helper, BODY, Direction.EAST);
        BlockPos hopper = BODY.east().above();
        helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        var hopperEntity = (HopperBlockEntity) helper.getBlockEntity(hopper);
        hopperEntity.setItem(0, new ItemStack(ModItems.S8_ROCKET.get(), 10));
        hopperEntity.setItem(1, new ItemStack(Blocks.DIRT, 4));
        helper.succeedWhen(() -> {
            helper.assertTrue(pod.rockets() == 8, "Hopper filled the pod, has " + pod.rockets());
            helper.assertTrue(hopperEntity.getItem(0).getCount() == 2, "Two rockets left over in the hopper");
            helper.assertTrue(hopperEntity.getItem(1).getCount() == 4, "The pod took no dirt");
        });
    }

    /**
     * On a ship turned a quarter round, the rockets leave along the ship's new heading for the
     * tubes, not the pod's facing in the ship's own grid.
     */
    @GameTest(template = "empty", batch = "rocket_pod_ship", timeoutTicks = 160)
    public static void onATurnedShipItFiresWhereTheShipPointsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(12, 8, 12));
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-8, -5, -8), center.offset(8, -1, 8))) {
            boolean wall = Math.abs(p.getX() - center.getX()) == 8
                    || Math.abs(p.getZ() - center.getZ()) == 8
                    || p.getY() == center.getY() - 5;
            level.setBlock(p, wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
        }
        var cells = new java.util.ArrayList<BlockPos>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-2, 0, -2), center.offset(2, 0, 2))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            cells.add(p.immutable());
        }
        var ship = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                level,
                center,
                cells,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(center.offset(-2, 0, -2), center.offset(2, 0, 2)));
        BlockPos deck = ship.getPlot().getCenterBlock();
        helper.runAfterDelay(10, () -> {
            var at = ship.logicalPose().transformPosition(deck.getCenter());
            dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(ship)
                    .teleport(new org.joml.Vector3d(at.x, at.y, at.z), new org.joml.Quaterniond().rotateY(Math.PI / 2));
        });
        helper.runAfterDelay(16, () -> {
            BlockPos body = deck.above();
            pod(level, body, Direction.EAST);
            var pod = (RocketPodBlockEntity) level.getBlockEntity(body);
            helper.assertTrue(pod != null, "Pod stands on the deck");
            pod.load(2);
            pod.fireSalvo();
        });
        helper.runAfterDelay(19, () -> {
            Vec3 expected =
                    ship.logicalPose().transformNormal(new Vec3(1, 0, 0)).normalize();
            var flying = level.getEntitiesOfClass(
                    RocketProjectile.class,
                    new AABB(BlockPos.containing(ship.logicalPose().transformPosition(deck.getCenter()))).inflate(40),
                    e -> e.isAlive());
            helper.assertFalse(flying.isEmpty(), "A rocket left the pod on the ship");
            for (var rocket : flying) {
                Vec3 v = rocket.getDeltaMovement().normalize();
                helper.assertTrue(
                        v.dot(expected) > 0.95,
                        "Rocket follows the turned ship: velocity " + v + ", tubes face " + expected);
                rocket.discard();
            }
            helper.succeed();
        });
    }

    /**
     * Built one section at a time: two in a row stay loose, the third joins all three into a
     * pod with the far one as the front, and taking the middle away leaves loose sections
     * again with the rockets dropped beside them.
     */
    @GameTest(template = "empty", batch = "rocket_pod_assembly", timeoutTicks = 40)
    public static void sectionsJoinIntoAPodAndComeApart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        section(level, helper.absolutePos(BODY.west()), Direction.EAST);
        section(level, helper.absolutePos(BODY), Direction.EAST);
        helper.assertTrue(
                helper.getBlockState(BODY).getValue(RocketPodBlock.PART) == RocketPodBlock.Part.SINGLE,
                "Two sections are still loose");
        // One facing the wrong way does not join.
        section(level, helper.absolutePos(BODY.east()), Direction.NORTH);
        helper.assertTrue(
                helper.getBlockState(BODY).getValue(RocketPodBlock.PART) == RocketPodBlock.Part.SINGLE,
                "A section turned the other way does not complete the pod");
        helper.setBlock(BODY.east(), Blocks.AIR);
        section(level, helper.absolutePos(BODY.east()), Direction.EAST);
        helper.assertTrue(
                helper.getBlockState(BODY.east()).getValue(RocketPodBlock.PART) == RocketPodBlock.Part.FRONT
                        && helper.getBlockState(BODY).getValue(RocketPodBlock.PART) == RocketPodBlock.Part.BODY
                        && helper.getBlockState(BODY.west()).getValue(RocketPodBlock.PART) == RocketPodBlock.Part.REAR,
                "The third section joins them: front at the far end");
        var pod = (RocketPodBlockEntity) helper.getBlockEntity(BODY);
        helper.assertTrue(pod.load(5) == 5, "The assembled pod takes rockets");
        helper.getLevel().destroyBlock(helper.absolutePos(BODY), true);
        helper.assertTrue(
                helper.getBlockState(BODY.east()).getValue(RocketPodBlock.PART) == RocketPodBlock.Part.SINGLE
                        && helper.getBlockState(BODY.west()).getValue(RocketPodBlock.PART)
                                == RocketPodBlock.Part.SINGLE,
                "With the middle gone the rest are loose sections again");
        int rockets = helper
                .getLevel()
                .getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new AABB(helper.absolutePos(BODY)).inflate(3),
                        e -> e.getItem().is(ModItems.S8_ROCKET.get()))
                .stream()
                .mapToInt(e -> e.getItem().getCount())
                .sum();
        helper.assertTrue(rockets == 5, "The rockets came out of the broken pod, found " + rockets);
        helper.succeed();
    }

    /** Set with the key to ten ticks apart and three a pulse, it fires them one at a time. */
    @GameTest(template = "empty", batch = "rocket_pod_ripple", timeoutTicks = 80)
    public static void rippleSpacesRocketsAsSet(GameTestHelper helper) {
        var pod = pod(helper, BODY, Direction.EAST);
        pod.load(8);
        pod.configure(10, 3);
        pod.fireSalvo();
        helper.runAfterDelay(2, () -> helper.assertTrue(pod.rockets() == 7, "One right away, has " + pod.rockets()));
        helper.runAfterDelay(8, () -> helper.assertTrue(pod.rockets() == 7, "The next waits its turn"));
        helper.runAfterDelay(14, () -> helper.assertTrue(pod.rockets() == 6, "Second after ten ticks"));
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(pod.rockets() == 5, "Three per pulse and no more, has " + pod.rockets());
            helper.succeed();
        });
    }

    /**
     * A rocket fired at something far past the area the test keeps loaded still gets there
     * and goes off, rather than stopping dead at the edge of the loaded ground.
     */
    @GameTest(template = "empty", batch = "rocket_pod_far", timeoutTicks = 420)
    public static void rocketsFlyOnPastTheLoadedArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Up on a tower, so the rocket's drop over 180 blocks does not put it in the ground.
        BlockPos high = BODY.above(18);
        BlockPos far = helper.absolutePos(high.east(180));
        int wall = 0;
        for (BlockPos p : BlockPos.betweenClosed(far.offset(0, -24, -6), far.offset(0, 4, 6))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            wall++;
        }
        int wallBlocks = wall;
        var pod = pod(helper, high, Direction.EAST);
        pod.load(2);
        pod.configure(4, 0);
        pod.fireSalvo();
        // However long a busy server takes to get the ground ahead ready: the rockets wait for
        // it rather than vanish, and the wall is hit in the end.
        helper.succeedWhen(() -> {
            int standing = 0;
            for (BlockPos p : BlockPos.betweenClosed(far.offset(0, -24, -6), far.offset(0, 4, 6))) {
                if (level.getBlockState(p).is(Blocks.STONE)) {
                    standing++;
                }
            }
            helper.assertTrue(
                    standing < wallBlocks, "The far wall was reached and hit: " + standing + "/" + wallBlocks);
        });
    }
}
