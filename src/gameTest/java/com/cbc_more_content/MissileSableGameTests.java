package com.cbc_more_content;

import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.CruiseMissileBlockEntity;
import com.cbc_more_content.compat.MissileDesignatorTargeting;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.item.TargetDesignatorItem;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class MissileSableGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final TicketType<Long> FIXTURE =
            TicketType.create("warnautics_missile_fixture", Long::compareTo, 100);

    private static List<BlockPos> place(ServerLevel level, BlockPos body, Direction facing) {
        var state = ModBlocks.CRUISE_MISSILE.get().defaultBlockState().setValue(CruiseMissileBlock.FACING, facing);
        var cells = List.of(body, body.relative(facing), body.relative(facing.getOpposite()));
        level.setBlock(cells.get(0), state, FLAGS);
        level.setBlock(cells.get(1), state.setValue(CruiseMissileBlock.PART, CruiseMissileBlock.Part.NOSE), FLAGS);
        level.setBlock(cells.get(2), state.setValue(CruiseMissileBlock.PART, CruiseMissileBlock.Part.TAIL), FLAGS);
        return cells;
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos center, List<BlockPos> cells) {
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(center), 3, center.asLong());
        var low = new BlockPos(
                cells.stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                cells.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        var high = new BlockPos(
                cells.stream().mapToInt(BlockPos::getX).max().orElseThrow(),
                cells.stream().mapToInt(BlockPos::getY).max().orElseThrow(),
                cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow());
        return SubLevelAssemblyHelper.assembleBlocks(level, center, cells, new BoundingBox3i(low, high));
    }

    @GameTest(template = "empty", batch = "missile_launch_rotated", timeoutTicks = 100)
    public static void lastAirframeLaunchesFromRotatedMovingSublevelOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(50, 90, 50));
        var ship = assemble(level, at, place(level, at, Direction.UP));
        helper.runAfterDelay(4, () -> {
            helper.assertFalse(ship.isRemoved(), "Fixture must remain loaded before changing its velocity");
            var handle = RigidBodyHandle.of(ship);
            handle.teleport(
                    new Vector3d(at.getX() + .5, at.getY() + .5, at.getZ() + .5), new Quaterniond().rotateZ(.55));
            handle.addLinearAndAngularVelocity(new Vector3d(8, 0, 0), new Vector3d());
        });
        helper.runAfterDelay(7, () -> {
            var local = ship.getPlot().getCenterBlock();
            var state = level.getBlockState(local);
            var old = (CruiseMissileBlockEntity) level.getBlockEntity(local);
            old.setTarget(at.east(100));
            var frame = SableDropCompat.resolveLaunch(level, local.getCenter(), Vec3.ZERO, new Vec3(0, 1, 0));
            helper.assertTrue(
                    Math.abs(frame.orientation().x) > .1 && frame.vel().x > .1, "Fixture is rotated and moving");
            CruiseMissileBlock.launch(level, local, state);
            CruiseMissileBlock.launch(level, local, state);
            var spawned = level.getEntitiesOfClass(
                    CruiseMissileProjectile.class, new AABB(frame.pos(), frame.pos()).inflate(4));
            helper.assertTrue(
                    spawned.size() == 1, "Only one missile spawns in world space after last plot block is removed");
            var missile = spawned.getFirst();
            helper.assertTrue(
                    missile.position().distanceToSqr(frame.pos()) < 1e-8, "Launch point captured before plot removal");
            var expected = frame.orientation()
                    .scale(CruiseMissileProjectile.EJECT_SPEED)
                    .add(frame.vel());
            helper.assertTrue(
                    missile.getDeltaMovement().distanceToSqr(expected) < 1e-8,
                    "Cold launch follows transformed rail and inherits carrier velocity");
            helper.assertTrue(missile.isEjecting() && !missile.isPowered(), "Cold start still precedes motor ignition");
            CompoundTag saved = new CompoundTag();
            missile.saveWithoutId(saved);
            helper.assertTrue(saved.getInt("TargetX") == at.getX() + 100, "Guidance survives block removal");
            helper.assertFalse(old.isLiveAirframe(), "Original block entity cannot render a phantom after launch");
            for (var p : List.of(local, local.above(), local.below())) {
                helper.assertFalse(
                        level.getBlockState(p).is(ModBlocks.CRUISE_MISSILE.get()), "All airframe cells consumed");
            }
            missile.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "missile_motion_save", timeoutTicks = 100)
    public static void inheritedMotionAndColdStartSurviveFlightAndSave(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(60, 100, 60)).getCenter();
        var hot = ModEntityTypes.CRUISE_MISSILE.get().create(level);
        hot.setPos(at);
        hot.launch(new Vec3(1, 0, 0), new Vec3(0, 0, .4));
        var initial = hot.getDeltaMovement();
        hot.tick();
        helper.assertTrue(
                hot.position().distanceToSqr(at.add(initial)) < 1e-8,
                "First powered tick retains inherited sideways motion");
        hot.discard();
        var cold = ModEntityTypes.CRUISE_MISSILE.get().create(level);
        cold.setPos(at);
        cold.eject(new Vec3(.3, .9, 0).normalize(), new Vec3(.2, 0, 0));
        for (int i = 0; i < 5; i++) {
            cold.tick();
        }
        var tag = new CompoundTag();
        cold.saveWithoutId(tag);
        var loaded = ModEntityTypes.CRUISE_MISSILE.get().create(level);
        loaded.load(tag);
        helper.assertTrue(loaded.isEjecting() && !loaded.isPowered(), "Reload retains cold start phase");
        for (int i = 0; i < CruiseMissileProjectile.EJECT_TICKS - 5; i++) {
            loaded.tick();
        }
        helper.assertTrue(
                !loaded.isEjecting() && loaded.isPowered(), "Reloaded missile lights motor when ejection ends");
        cold.discard();
        loaded.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "missile_stale_render", timeoutTicks = 100)
    public static void everyFacingRendersOnlyItsCurrentLiveBody(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(12, 30, 12));
        for (var facing : Direction.values()) {
            var cells = place(level, at, facing);
            var body = (CruiseMissileBlockEntity) level.getBlockEntity(at);
            helper.assertTrue(body.isLiveAirframe(), "Current complete body can render");
            helper.assertTrue(
                    level.getBlockState(at).getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED,
                    "No cached three-cell missile mesh remains in world chunks");
            for (var pos : cells) {
                level.removeBlock(pos, false);
            }
            helper.assertFalse(body.isLiveAirframe(), "Removed block entity must not draw");
            place(level, at, facing);
            helper.assertFalse(
                    body.isLiveAirframe(), "Replacement at same position must not revive stale renderer entry");
            for (var pos : cells) {
                level.removeBlock(pos, false);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "missile_binding_move", timeoutTicks = 100)
    public static void designatorFollowsAssemblyAndWorksBeyondEightBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(30, 60, 30));
        var cells = place(level, at, Direction.UP);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "missile-bind-test"));
        var remote = new ItemStack(ModItems.TARGET_DESIGNATOR.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        remote.getItem()
                .useOn(new UseOnContext(
                        player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(at.getCenter(), Direction.NORTH, at, false)));
        var ship = assemble(level, at, cells);
        var local = ship.getPlot().getCenterBlock();
        helper.assertTrue(
                local.equals(TargetDesignatorItem.resolveBoundMissile(level, remote)),
                "Binding follows persistent missile identity into Sable plot");
        var world = ship.logicalPose().transformPosition(local.getCenter());
        player.setPos(world.add(30, 0, 0));
        helper.assertTrue(
                MissileDesignatorTargeting.canControl(player, local),
                "Designator can launch from a cabin thirty blocks away");
        player.setPos(world.add(221, 0, 0));
        helper.assertFalse(
                MissileDesignatorTargeting.canControl(player, local), "Remote control still has a finite range");
        for (var pos : List.of(local, local.above(), local.below())) {
            level.removeBlock(pos, false);
        }
        helper.assertTrue(
                TargetDesignatorItem.resolveBoundMissile(level, remote) == null,
                "Destroyed missile cannot stay bound to a replacement");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "missile_target_range", timeoutTicks = 100)
    public static void targetRangeStartsAtFiftyBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(40, 60, 40));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "missile-range-test"));
        player.setPos(at.getCenter().subtract(0, player.getEyeHeight(), 0));
        var target = UUID.randomUUID();
        helper.assertFalse(
                MissileDesignatorTargeting.canTarget(
                        player, at, target, at.getCenter().add(49.9, 0, 0)),
                "Targets under fifty blocks cannot be locked");
        helper.assertTrue(
                MissileDesignatorTargeting.canTarget(
                        player, at, target, at.getCenter().add(50, 0, 0)),
                "Exactly fifty blocks is allowed");
        helper.assertFalse(
                MissileDesignatorTargeting.canTarget(
                        player, at, target, at.getCenter().add(221, 0, 0)),
                "Maximum designator range remains enforced");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "missile_target_hulls", timeoutTicks = 100)
    public static void ownHullIsTransparentButWorldAndOtherHullStillOcclude(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(50, 100, 50));
        var own = wall(level, at.east(8));
        var target = wall(level, at.east(80));
        helper.runAfterDelay(5, () -> {
            var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "missile-sight-test"));
            var centre = target.logicalPose()
                    .transformPosition(target.getPlot().getCenterBlock().getCenter());
            var eye = new Vec3(at.getX() + .5, centre.y, at.getZ() + .5);
            player.setPos(eye.subtract(0, player.getEyeHeight(), 0));
            ((EntityMovementExtension) player).sable$setTrackingSubLevel(own);
            helper.assertTrue(
                    MissileDesignatorTargeting.canTarget(player, at, target.getUniqueId(), centre),
                    "Target marker and lock pass through the operator's hull");
            helper.assertFalse(
                    MissileDesignatorTargeting.canTarget(player, at, own.getUniqueId(), centre),
                    "Own ship cannot be designated");
            ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
            helper.assertFalse(
                    MissileDesignatorTargeting.canTarget(player, at, target.getUniqueId(), centre),
                    "The same wall is opaque when it belongs to another ship");
            ((EntityMovementExtension) player).sable$setTrackingSubLevel(own);
            var obstruction = BlockPos.containing(eye.add(35, 0, 0));
            level.setBlockAndUpdate(obstruction, Blocks.STONE.defaultBlockState());
            helper.assertFalse(
                    MissileDesignatorTargeting.canTarget(player, at, target.getUniqueId(), centre),
                    "World walls still block targeting from inside a ship");
            level.removeBlock(obstruction, false);
            clearWall(level, own);
            clearWall(level, target);
            helper.succeed();
        });
    }

    private static ServerSubLevel wall(ServerLevel level, BlockPos at) {
        var cells = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(at.offset(0, -2, -2), at.offset(0, 2, 2))) {
            level.setBlock(p, Blocks.OBSIDIAN.defaultBlockState(), FLAGS);
            cells.add(p.immutable());
        }
        return assemble(level, at, cells);
    }

    @GameTest(template = "empty", batch = "missile_sable_redstone", timeoutTicks = 100)
    public static void poweringAnySegmentOnSublevelLaunchesOneMissile(GameTestHelper helper) {
        var level = helper.getLevel();
        var ships = new ArrayList<ServerSubLevel>();
        for (int i = 0; i < 3; i++) {
            var at = helper.absolutePos(new BlockPos(40 + i * 20, 70, 40));
            var cells = new ArrayList<>(place(level, at, Direction.UP));
            // A real rack keeps the signal source connected while Sable updates mass/topology.
            // Replacing an existing stone signal cell also avoids adding a disconnected floating block.
            for (var support : BlockPos.betweenClosed(at.offset(0, -2, -1), at.offset(2, -2, 1))) {
                level.setBlock(support, Blocks.STONE.defaultBlockState(), FLAGS);
                cells.add(support.immutable());
            }
            for (int y = -1; y <= 1; y++) {
                var support = at.offset(2, y, 0);
                level.setBlock(support, Blocks.STONE.defaultBlockState(), FLAGS);
                cells.add(support);
            }
            var signal = at.offset(1, i - 1, 0);
            level.setBlock(signal, Blocks.STONE.defaultBlockState(), FLAGS);
            cells.add(signal);
            ships.add(assemble(level, at, cells));
        }
        var centers = new ArrayList<Vec3>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (var ship : ships) {
                        var world = BlockPos.containing(ship.logicalPose()
                                .transformPosition(
                                        ship.getPlot().getCenterBlock().getCenter()));
                        helper.assertTrue(
                                level.areEntitiesLoaded(new ChunkPos(world).toLong())
                                        && level.isPositionEntityTicking(world),
                                "Parent-world entity chunks must be ticking before launching");
                    }
                })
                .thenExecute(() -> {
                    for (int i = 0; i < 3; i++) {
                        var ship = ships.get(i);
                        helper.assertFalse(ship.isRemoved(), "Powered missile fixture must remain loaded");
                        var local = ship.getPlot().getCenterBlock();
                        helper.assertTrue(
                                level.getBlockState(local).is(ModBlocks.CRUISE_MISSILE.get()),
                                "Rack retains missile before power");
                        centers.add(ship.logicalPose().transformPosition(local.getCenter()));
                        level.setBlockAndUpdate(local.offset(1, i - 1, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    }
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    for (int i = 0; i < 3; i++) {
                        var local = ships.get(i).getPlot().getCenterBlock();
                        for (var p : List.of(local, local.above(), local.below())) {
                            helper.assertFalse(
                                    level.getBlockState(p).is(ModBlocks.CRUISE_MISSILE.get()),
                                    "Power on segment " + (i - 1) + " consumes complete airframe");
                        }
                        var center = centers.get(i);
                        var missiles = level.getEntitiesOfClass(
                                CruiseMissileProjectile.class, new AABB(center, center).inflate(8));
                        var observed = new ArrayList<String>();
                        for (var entity : level.getAllEntities()) {
                            if (entity instanceof CruiseMissileProjectile) {
                                observed.add(entity.position() + " age=" + entity.tickCount);
                            }
                        }
                        helper.assertTrue(
                                missiles.size() == 1,
                                "Powered segment " + (i - 1) + " expects one missile near " + center + "; actual="
                                        + observed);
                        missiles.forEach(CruiseMissileProjectile::discard);
                        for (var cell : BlockPos.betweenClosed(local.offset(0, -2, -1), local.offset(2, 1, 1))) {
                            level.setBlock(cell, Blocks.AIR.defaultBlockState(), FLAGS);
                        }
                    }
                })
                .thenSucceed();
    }

    private static void clearWall(ServerLevel level, ServerSubLevel sub) {
        var center = sub.getPlot().getCenterBlock();
        for (var p : BlockPos.betweenClosed(center.offset(0, -2, -2), center.offset(0, 2, 2))) {
            level.removeBlock(p, false);
        }
    }
}
