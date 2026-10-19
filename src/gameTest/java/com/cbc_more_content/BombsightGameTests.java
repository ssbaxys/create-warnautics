package com.cbc_more_content;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.bombsight.BombsightBlock;
import com.cbc_more_content.bombsight.BombsightBlockEntity;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.util.DropBombUtil;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class BombsightGameTests {
    /** Height of the landing ground built for the runs, relative to the plot. */
    private static final int GROUND_Y = 40;
    /** Ship speed for the runs, blocks per tick (20 m/s). */
    private static final double SPEED = 1.0D;

    /** Records a real bomb's flight, tick by tick, against which the sight's integrator is matched. */
    @GameTest(template = "empty", batch = "bombsight_probe", timeoutTicks = 60)
    public static void recordBombFlight(GameTestHelper helper) {
        var level = helper.getLevel();
        Vec3 start = helper.absoluteVec(new BlockPos(12, 80, 12).getCenter());
        DropBombProjectile bomb = DropBombUtil.spawn(BombSize.SMALL, level, start, new Vec3(1.5, 0.1, 0.3), null);
        StringBuilder log = new StringBuilder(String.format(
                "bomb probe g=%.5f drag=%.5f quad=%s v0=%s |",
                bomb.ballisticGravity(), bomb.ballisticDrag(), bomb.ballisticQuadraticDrag(), bomb.getDeltaMovement()));
        for (int t = 1; t <= 30; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                Vec3 p = bomb.position().subtract(start);
                Vec3 v = bomb.getDeltaMovement();
                log.append(String.format(
                        " t%d p=(%.4f,%.4f,%.4f) v=(%.4f,%.4f,%.4f)", tick, p.x, p.y, p.z, v.x, v.y, v.z));
            });
        }
        helper.runAfterDelay(31, () -> {
            CBCMoreContent.LOGGER.info(log.toString());
            bomb.discard();
            helper.succeed();
        });
    }

    /**
     * From the eyepiece: a hand release drops a stick from the rack whatever the run says,
     * automatic release switches, and the target can be cleared.
     */
    @GameTest(template = "empty", batch = "bombsight_hand", timeoutTicks = 80)
    public static void handReleaseDropsFromTheRack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rack = helper.absolutePos(new BlockPos(12, 50, 12));
        ground(helper, 30);
        level.setBlock(
                rack, ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.FACING, Direction.DOWN), 3);
        level.setBlock(rack.north(), ModBlocks.BOMBSIGHT.get().defaultBlockState(), 3);
        var sight = (BombsightBlockEntity) level.getBlockEntity(rack.north());
        sight.markTarget(500, 500);
        boolean auto = sight.autoRelease();
        sight.setAutoRelease(!auto);
        helper.assertTrue(sight.autoRelease() != auto, "Automatic release switches");
        sight.clearTarget();
        helper.assertFalse(sight.hasTarget(), "The target clears");
        helper.assertTrue(sight.releaseNow(level), "A hand release goes out");
        helper.assertTrue(
                level.getBlockState(rack.north()).getValue(BombsightBlock.RELEASING), "The release signal is on");
        helper.succeedWhen(() -> {
            helper.assertFalse(
                    level.getBlockState(rack).getBlock() instanceof DropBombBlock, "The rack let its bomb go");
            unforce(level);
        });
    }

    /**
     * The sight fixes to whatever face it is put against: a floor, a ceiling, or a wall,
     * facing out of it, with its shape against that face. Hung under a ledge beside a rack,
     * or on a wall beside one, it still finds the rack and works the drop out.
     */
    @GameTest(template = "empty", batch = "bombsight_ceiling", timeoutTicks = 40)
    public static void sightHangsFromACeilingAndStillWorks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(12, 50, 12));
        ground(helper, 30);
        level.setBlock(cell.above(), Blocks.STONE.defaultBlockState(), 3);
        var block = (BombsightBlock) ModBlocks.BOMBSIGHT.get();
        var stack = new net.minecraft.world.item.ItemStack(ModBlocks.BOMBSIGHT.get());
        var under = new net.minecraft.world.phys.BlockHitResult(
                cell.above().getBottomCenter(), Direction.DOWN, cell.above(), false);
        var hung = block.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(
                level, null, net.minecraft.world.InteractionHand.MAIN_HAND, stack, under));
        helper.assertTrue(
                hung.getValue(BombsightBlock.FACE)
                        == net.minecraft.world.level.block.state.properties.AttachFace.CEILING,
                "Placed on a ceiling, it hangs");
        var high = new net.minecraft.world.phys.BlockHitResult(
                cell.east().getCenter().add(-0.5D, 0.3D, 0), Direction.WEST, cell.east(), false);
        var onWall = block.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(
                level, null, net.minecraft.world.InteractionHand.MAIN_HAND, stack, high));
        helper.assertTrue(
                onWall.getValue(BombsightBlock.FACE) == net.minecraft.world.level.block.state.properties.AttachFace.WALL
                        && onWall.getValue(BombsightBlock.FACING) == Direction.WEST,
                "Placed on a wall, it fixes to the wall facing out of it");
        helper.assertTrue(
                onWall.getShape(level, cell).bounds().minX >= 0.8D,
                "On the west face of the block east of it, the sight sits against that block");
        var floor = new net.minecraft.world.phys.BlockHitResult(
                cell.below().getCenter().add(0, 0.5D, 0), Direction.UP, cell.below(), false);
        helper.assertTrue(
                block.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(
                                        level, null, net.minecraft.world.InteractionHand.MAIN_HAND, stack, floor))
                                .getValue(BombsightBlock.FACE)
                        == net.minecraft.world.level.block.state.properties.AttachFace.FLOOR,
                "Placed on a floor, it stands");
        level.setBlock(cell, hung, 3);
        helper.assertTrue(
                hung.getShape(level, cell).bounds().minY >= 0.8D, "A hanging sight's shape is at the top of the cell");
        BlockPos rack = cell.south();
        level.setBlock(
                rack, ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.FACING, Direction.DOWN), 3);
        var prediction = ((BombsightBlockEntity) level.getBlockEntity(cell)).predict(level);
        helper.assertTrue(prediction.rack(), "Hung beside the rack, it finds it");
        helper.assertTrue(prediction.fall().landed(), "And works out where its bomb lands");
        helper.assertTrue(
                Math.abs(prediction.fall().impact().x - rack.getCenter().x) < 1.0D
                        && Math.abs(prediction.fall().impact().z - rack.getCenter().z) < 1.0D,
                "A rack dropping straight down from rest lands under itself: "
                        + prediction.fall().impact());
        // The same rack, the sight now on the wall beside it instead.
        level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        BlockPos wallCell = rack.east();
        level.setBlock(wallCell.east(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(wallCell, onWall, 3);
        var fromWall = ((BombsightBlockEntity) level.getBlockEntity(wallCell)).predict(level);
        helper.assertTrue(fromWall.rack() && fromWall.fall().landed(), "On a wall beside the rack, it works too");
        helper.assertTrue(
                fromWall.fall().impact().distanceTo(prediction.fall().impact()) < 0.01D,
                "And gets the same point, wherever on the carrier it is fixed");
        unforce(level);
        helper.succeed();
    }

    /**
     * A sight beside a powered rack on the ground: the bomb that leaves the rack comes down
     * where the sight said it would.
     */
    @GameTest(template = "empty", batch = "bombsight_ground", timeoutTicks = 120)
    public static void groundRackLandsWhereTheSightSays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rack = helper.absolutePos(new BlockPos(12, 50, 12));
        ground(helper, 30);
        level.setBlock(
                rack, ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.FACING, Direction.EAST), 3);
        level.setBlock(rack.north(), ModBlocks.BOMBSIGHT.get().defaultBlockState(), 3);
        var sight = (BombsightBlockEntity) level.getBlockEntity(rack.north());
        var predicted = sight.predict(level);
        helper.assertTrue(predicted.rack(), "The sight finds the rack beside it");
        helper.assertTrue(predicted.fall().landed(), "The fall comes down on loaded ground");
        level.setBlock(rack.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        follower(helper, 110).start(rack.getCenter(), predicted.fall().impact(), 1.5D, "ground rack");
    }

    /**
     * On a Sable ship flying at 20 m/s and sinking as it goes, the sight's point is where
     * the bomb from its rack comes down.
     */
    @GameTest(template = "empty", batch = "bombsight_ship", timeoutTicks = 220)
    public static void movingShipBombLandsWhereTheSightSays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        boolean[] dropped = {false};
        Follow follow = follower(helper, 195);
        for (int t = 5; t < 80; t++) {
            helper.runAfterDelay(t, () -> {
                if (dropped[0]) {
                    return;
                }
                BlockPos sightPos = findSight(level, ship.getPlot().getCenterBlock());
                helper.assertTrue(sightPos != null, "The sight rides on the ship");
                // Wait for the push to take: the point is to drop from a ship under way.
                if (carrierVelocity(level, sightPos).x < SPEED * 0.6D) {
                    return;
                }
                dropped[0] = true;
                var sight = (BombsightBlockEntity) level.getBlockEntity(sightPos);
                var prediction = sight.predict(level);
                helper.assertTrue(prediction.rack(), "The sight finds the rack on the ship");
                helper.assertTrue(prediction.fall().landed(), "The fall comes down on loaded ground");
                // Released this very tick, from where the rack would let it go.
                BlockPos rackPos = sightPos.south();
                var state = level.getBlockState(rackPos);
                var frame = ((DropBombBlock) state.getBlock()).predictLaunch(level, rackPos, state);
                helper.assertTrue(
                        frame.vel().x > SPEED * 0.6D, "The bomb leaves with the ship's speed: " + frame.vel());
                DropBombUtil.spawn(BombSize.SMALL, frame, null);
                follow.start(frame.pos(), prediction.fall().impact(), 2.0D, "moving ship");
            });
        }
    }

    /**
     * The whole run: a target marked ahead of a moving, sinking ship, automatic release on,
     * and the bomb that the sight's own signal sends off the rack lands on the target.
     */
    @GameTest(template = "empty", batch = "bombsight_auto", timeoutTicks = 260)
    public static void autoReleaseHitsTheTargetFromAMovingShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        Vec3[] target = {null};
        DropBombProjectile[] bomb = {null};
        Vec3[] last = {null};
        int[] released = {-1};
        boolean[] done = {false};
        for (int t = 5; t < 250; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                if (done[0]) {
                    return;
                }
                if (target[0] == null) {
                    BlockPos sightPos = findSight(level, ship.getPlot().getCenterBlock());
                    helper.assertTrue(sightPos != null, "The sight rides on the ship");
                    if (carrierVelocity(level, sightPos).x < SPEED * 0.6D) {
                        return;
                    }
                    // A target a little way down the track, as a crew would line up a run.
                    var be = (BombsightBlockEntity) level.getBlockEntity(sightPos);
                    Vec3 now = be.predict(level).fall().impact();
                    int x = (int) Math.floor(now.x + 9);
                    int z = (int) Math.floor(now.z + 1.5D);
                    be.configure((byte) 1, true, x, z, true, 2, false);
                    target[0] = new Vec3(x + 0.5D, now.y, z + 0.5D);
                    CBCMoreContent.LOGGER.info("bombsight auto run: t={} impact {} target {}", tick, now, target[0]);
                    return;
                }
                if (bomb[0] == null) {
                    var flying = level.getEntitiesOfClass(
                            DropBombProjectile.class, new AABB(BlockPos.containing(target[0])).inflate(120), e -> true);
                    if (!flying.isEmpty()) {
                        bomb[0] = flying.get(0);
                        released[0] = tick;
                    }
                    return;
                }
                if (bomb[0].isAlive()) {
                    last[0] = bomb[0].position();
                    return;
                }
                if (last[0] == null) {
                    return;
                }
                done[0] = true;
                double miss = Math.hypot(last[0].x - target[0].x, last[0].z - target[0].z);
                CBCMoreContent.LOGGER.info(
                        "bombsight auto run: released t={} landed {} target {} miss {} (along {}, cross {})",
                        released[0],
                        last[0],
                        target[0],
                        String.format("%.2f", miss),
                        String.format("%.2f", last[0].x - target[0].x),
                        String.format("%.2f", last[0].z - target[0].z));
                unforce(level);
                helper.assertTrue(
                        miss <= 3.0D,
                        "Auto release puts the bomb on the target: missed by " + String.format("%.2f", miss));
                helper.succeed();
            });
        }
    }

    /** The carrier's velocity at a block on it, blocks per tick. */
    private static Vec3 carrierVelocity(ServerLevel level, BlockPos pos) {
        return com.cbc_more_content.compat.SableDropCompat.resolveLaunch(level, pos, pos.getCenter(), Vec3.ZERO, null)
                .vel();
    }

    // ---------------------------------------------------------------- fixtures

    private static final Set<ChunkPos> FORCED = new HashSet<>();

    /** Stone ground at {@link #GROUND_Y}, long enough for the runs, its chunks kept loaded. */
    private static void ground(GameTestHelper helper, int length) {
        ServerLevel level = helper.getLevel();
        BlockPos from = helper.absolutePos(new BlockPos(-12, GROUND_Y, -10));
        BlockPos to = helper.absolutePos(new BlockPos(12 + length, GROUND_Y, 34));
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (int cx = Math.min(from.getX(), to.getX()) >> 4; cx <= Math.max(from.getX(), to.getX()) >> 4; cx++) {
            for (int cz = Math.min(from.getZ(), to.getZ()) >> 4; cz <= Math.max(from.getZ(), to.getZ()) >> 4; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                if (FORCED.add(chunk)) {
                    level.setChunkForced(cx, cz, true);
                }
            }
        }
    }

    private static void unforce(ServerLevel level) {
        for (ChunkPos chunk : FORCED) {
            level.setChunkForced(chunk.x, chunk.z, false);
        }
        FORCED.clear();
    }

    /**
     * A small ship high over the ground: a deck, a rack hanging under it nose down, the
     * sight beside the rack; pushed off at {@link #SPEED} east once assembled.
     */
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel ship(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper, 110);
        BlockPos deck = helper.absolutePos(new BlockPos(0, 95, 12));
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(deck.offset(-2, 0, -2), deck.offset(2, 0, 2))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells.add(p.immutable());
        }
        BlockPos rack = deck.below();
        level.setBlock(
                rack,
                ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.FACING, Direction.DOWN),
                Block.UPDATE_CLIENTS);
        level.setBlock(
                rack.north(),
                ModBlocks.BOMBSIGHT
                        .get()
                        .defaultBlockState()
                        .setValue(BombsightBlock.FACING, Direction.EAST)
                        .setValue(
                                BombsightBlock.FACE,
                                net.minecraft.world.level.block.state.properties.AttachFace.CEILING),
                Block.UPDATE_CLIENTS);
        cells.add(rack);
        cells.add(rack.north());
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, deck, cells, new BoundingBox3i(deck.offset(-2, -1, -2), deck.offset(2, 0, 2)));
        helper.runAfterDelay(4, () -> RigidBodyHandle.of(ship)
                .addLinearAndAngularVelocity(new Vector3d(SPEED * 20, 0, 0), new Vector3d()));
        return ship;
    }

    private static BlockPos findSight(ServerLevel level, BlockPos plotCentre) {
        for (BlockPos p : BlockPos.betweenClosed(plotCentre.offset(-4, -4, -4), plotCentre.offset(4, 4, 4))) {
            if (level.getBlockEntity(p) instanceof BombsightBlockEntity) {
                return p.immutable();
            }
        }
        return null;
    }

    /**
     * Follows the one bomb near where it was let go down, and checks where it lands against
     * the prediction. Its polling is scheduled up front, from the test's own body: a test
     * scheduling more work from inside a scheduled callback upsets the test runner, which is
     * iterating that schedule at the time.
     */
    private static final class Follow {
        Vec3 from;
        Vec3 predicted;
        double tolerance;
        String what;
        DropBombProjectile bomb;
        Vec3 last;

        void start(Vec3 from, Vec3 predicted, double tolerance, String what) {
            this.from = from;
            this.predicted = predicted;
            this.tolerance = tolerance;
            this.what = what;
        }

        void poll(GameTestHelper helper) {
            if (from == null) {
                return;
            }
            ServerLevel level = helper.getLevel();
            if (bomb == null) {
                var flying = level.getEntitiesOfClass(
                        DropBombProjectile.class, new AABB(BlockPos.containing(from)).inflate(12), e -> true);
                if (!flying.isEmpty()) {
                    bomb = flying.get(0);
                }
                return;
            }
            if (bomb.isAlive()) {
                last = bomb.position();
                return;
            }
            if (last == null) {
                return;
            }
            Vec3 landed = last;
            last = null;
            double miss = Math.hypot(landed.x - predicted.x, landed.z - predicted.z);
            CBCMoreContent.LOGGER.info(
                    "bombsight {}: predicted {} landed {} miss {}",
                    what,
                    predicted,
                    landed,
                    String.format("%.2f", miss));
            unforce(level);
            helper.assertTrue(
                    miss <= tolerance,
                    "The bomb lands where the sight said (" + what + "): missed by " + String.format("%.2f", miss));
            helper.succeed();
        }
    }

    /** A follower polled every tick up to {@code ticks}; armed later with {@link Follow#start}. */
    private static Follow follower(GameTestHelper helper, int ticks) {
        Follow follow = new Follow();
        for (int t = 1; t < ticks; t++) {
            helper.runAfterDelay(t, () -> follow.poll(helper));
        }
        return follow;
    }
}
