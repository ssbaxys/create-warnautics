package com.cbc_more_content;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.munitions.SeaBombProjectile;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class TorpedoSableGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final TicketType<Long> FIXTURE = TicketType.create("torpedo_sable_fixture", Long::compareTo, 1200);

    @GameTest(template = "empty", batch = "torpedo_sable_last", timeoutTicks = 100)
    public static void lastBlockLaunchesInWorldAndDoesNotLeavePlotEntity(GameTestHelper helper) {
        launch(helper, false, false);
    }

    @GameTest(template = "empty", batch = "torpedo_sable_rack", timeoutTicks = 100)
    public static void underwaterRotatedRackSurvivesRelease(GameTestHelper helper) {
        launch(helper, true, true);
    }

    @GameTest(template = "empty", batch = "torpedo_sable_redstone", timeoutTicks = 600)
    public static void redstoneReleaseSwimsAwayAndCarrierRemainsEditable(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(60, 100, 60));
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(at), 4, at.asLong(), true);
        var originChunk = new ChunkPos(at);
        for (int x = -2; x <= 2; x++) {
            for (int z = -5; z <= 2; z++) {
                level.getChunk(originChunk.x + x, originChunk.z + z);
            }
        }
        var state = ModBlocks.SEA_BOMB.get().defaultBlockState().setValue(DropBombBlock.FACING, Direction.NORTH);
        level.setBlock(at, state, FLAGS);
        var cells = new ArrayList<BlockPos>();
        cells.add(at);
        for (var p : BlockPos.betweenClosed(at.offset(-1, -1, 0), at.offset(1, -1, 3))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), FLAGS);
            cells.add(p.immutable());
        }
        level.setBlock(at.south(), Blocks.STONE.defaultBlockState(), FLAGS);
        cells.add(at.south());
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, at, cells, new BoundingBox3i(at.offset(-1, -1, 0), at.offset(1, 0, 3)));
        // This fixture tests rack release, not a shoreline transition. Leave room for
        // the unanchored stone carrier to sink while its chunks finish loading.
        var waterMin = at.offset(-8, -25, -65);
        var waterMax = at.offset(8, 8, 8);
        for (var p : BlockPos.betweenClosed(waterMin, waterMax)) {
            level.setBlock(p, Blocks.WATER.defaultBlockState(), FLAGS);
        }
        var spawned = new ArrayList<SeaBombProjectile>();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getLevel() == level && event.getEntity() instanceof SeaBombProjectile sea) {
                spawned.add(sea);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(level.isPositionEntityTicking(at), "Parent chunks loaded before release");
                })
                .thenExecute(() -> {
                    var local = ship.getPlot().getCenterBlock();
                    level.setBlockAndUpdate(local.south(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(spawned.size() == 1, "Native redstone path releases one torpedo");
                        var sea = spawned.getFirst();
                        helper.assertFalse(sea.isRemoved(), "Released torpedo continues ticking in water");
                        helper.assertTrue(
                                sea.phase() == SeaBombProjectile.PHASE_SWIM,
                                "Released torpedo swims: phase=" + sea.phase() + " position=" + sea.position()
                                        + " rack=" + at + " ticks=" + sea.tickCount);
                        helper.assertTrue(
                                sea.getZ() < at.getZ() - 5, "Torpedo leaves the rack, not only a short particle trail");
                        helper.assertFalse(ship.isRemoved(), "Carrier remains loaded after torpedo release");
                        var local = ship.getPlot().getCenterBlock();
                        helper.assertFalse(
                                level.getBlockState(local).is(ModBlocks.SEA_BOMB.get()),
                                "Torpedo block remains consumed");
                        for (var p : cells) {
                            if (!p.equals(at) && !p.equals(at.south())) {
                                helper.assertTrue(
                                        level.getBlockState(local.offset(p.subtract(at)))
                                                .is(Blocks.STONE),
                                        "Carrier does not lose blocks during launch");
                            }
                        }
                        level.setBlockAndUpdate(local.below(), Blocks.AIR.defaultBlockState());
                        helper.assertTrue(
                                level.getBlockState(local.below()).isAir(),
                                "Server accepts block removal after launch");
                    } finally {
                        NeoForge.EVENT_BUS.unregister(listener);
                        spawned.forEach(SeaBombProjectile::discard);
                        var local = ship.getPlot().getCenterBlock();
                        for (var p : cells) {
                            level.setBlock(local.offset(p.subtract(at)), Blocks.AIR.defaultBlockState(), FLAGS);
                        }
                        for (var p : BlockPos.betweenClosed(waterMin, waterMax)) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                        }
                    }
                })
                .thenSucceed();
    }

    private static void launch(GameTestHelper helper, boolean rack, boolean underwater) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(60, 110, 60));
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(at), 3, at.asLong());
        var state = ModBlocks.SEA_BOMB.get().defaultBlockState().setValue(DropBombBlock.FACING, Direction.NORTH);
        level.setBlock(at, state, FLAGS);
        var cells = new ArrayList<BlockPos>();
        cells.add(at);
        if (rack) {
            for (var p : BlockPos.betweenClosed(at.offset(-2, -1, 0), at.offset(2, -1, 4))) {
                level.setBlock(p, Blocks.STONE.defaultBlockState(), FLAGS);
                cells.add(p.immutable());
            }
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level,
                at,
                cells,
                new BoundingBox3i(
                        at.offset(rack ? -2 : 0, rack ? -1 : 0, 0), at.offset(rack ? 2 : 0, 0, rack ? 4 : 0)));
        helper.runAfterDelay(4, () -> {
            helper.assertFalse(ship.isRemoved(), "Launch fixture loaded");
            RigidBodyHandle.of(ship)
                    .teleport(
                            new Vector3d(at.getX() + .5, at.getY() + .5, at.getZ() + .5),
                            new Quaterniond().rotateY(.7));
            RigidBodyHandle.of(ship).addLinearAndAngularVelocity(new Vector3d(5, 0, 0), new Vector3d());
        });
        helper.runAfterDelay(7, () -> {
            var local = ship.getPlot().getCenterBlock();
            var body = ship.logicalPose().transformPosition(local.getCenter());
            var frame = SableDropCompat.resolveLaunch(level, local.getCenter(), Vec3.ZERO, new Vec3(0, 0, -1));
            var waterMin = BlockPos.containing(body).offset(-12, -3, -12);
            var waterMax = BlockPos.containing(body).offset(12, 3, 12);
            if (underwater) {
                for (var p : BlockPos.betweenClosed(waterMin, waterMax)) {
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), FLAGS);
                }
            }
            var spawned = new ArrayList<SeaBombProjectile>();
            Consumer<EntityJoinLevelEvent> listener = event -> {
                if (event.getLevel() == level && event.getEntity() instanceof SeaBombProjectile sea) {
                    spawned.add(sea);
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                release(level, local, state);
                helper.assertTrue(spawned.size() == 1, "Exactly one torpedo must spawn");
                var sea = spawned.getFirst();
                helper.assertTrue(
                        sea.position().distanceToSqr(body) < 9,
                        "Spawn stays near real rack, not plot storage: " + sea.position() + " expected near " + body);
                helper.assertTrue(
                        sea.getOrientation().dot(frame.orientation()) > .999,
                        "Launch follows rotated carrier orientation");
                helper.assertFalse(
                        SableDropCompat.isInsideSubLevel(level, sea.blockPosition()),
                        "Projectile lives outside plot storage");
                for (int i = 0; i < 6; i++) {
                    sea.tick();
                }
                helper.assertFalse(sea.isRemoved(), "Launched torpedo stays alive while clearing its rack");
                helper.assertFalse(
                        level.getBlockState(local).is(ModBlocks.SEA_BOMB.get()), "Consumed block stays removed");
                if (rack) {
                    for (var p : cells) {
                        if (!p.equals(at)) {
                            helper.assertTrue(
                                    level.getBlockState(local.offset(p.subtract(at)))
                                            .is(Blocks.STONE),
                                    "Release must leave the carrier intact");
                        }
                    }
                }
                if (underwater) {
                    helper.assertTrue(sea.phase() == SeaBombProjectile.PHASE_SWIM, "Submerged release starts swimming");
                }
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
                spawned.forEach(SeaBombProjectile::discard);
                for (var p : cells) {
                    level.setBlock(local.offset(p.subtract(at)), Blocks.AIR.defaultBlockState(), FLAGS);
                }
                if (underwater) {
                    for (var p : BlockPos.betweenClosed(waterMin, waterMax)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
            helper.succeed();
        });
    }

    private static void release(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            var method = DropBombBlock.class.getDeclaredMethod(
                    "ejectOne", ServerLevel.class, BlockPos.class, BlockState.class);
            method.setAccessible(true);
            method.invoke(ModBlocks.SEA_BOMB.get(), level, pos, state);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
