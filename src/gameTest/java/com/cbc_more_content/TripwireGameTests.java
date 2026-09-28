package com.cbc_more_content;

import com.cbc_more_content.compat.sable.TripwireGeometry;
import com.cbc_more_content.entity.TripwireEntity;
import com.cbc_more_content.event.TripwireSignal;
import com.cbc_more_content.item.TripwireCoilItem;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
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
public class TripwireGameTests {
    private static FakePlayer player(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "wire-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.TRIPWIRE_COIL.get(), 2));
        return player;
    }

    private static void click(FakePlayer player, BlockPos pos) {
        player.getMainHandItem()
                .getItem()
                .useOn(new UseOnContext(
                        player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(pos.getCenter(), Direction.UP, pos, false)));
    }

    private static TripwireEntity connect(GameTestHelper helper, BlockPos a, BlockPos b) {
        var player = player(helper);
        click(player, a);
        helper.assertTrue(TripwireCoilItem.pendingPost(player.getMainHandItem()).equals(a), "First post selected");
        click(player, b);
        helper.assertTrue(player.getMainHandItem().getCount() == 1, "One successful wire consumes one coil");
        var at = TripwireGeometry.position(helper.getLevel(), a);
        var wires = helper.getLevel().getEntitiesOfClass(TripwireEntity.class, new AABB(at, at).inflate(10));
        helper.assertTrue(wires.size() == 1, "Wire must be spawned and searchable in world coordinates");
        return wires.getFirst();
    }

    private static List<BlockPos> deck(ServerLevel level, BlockPos center) {
        var blocks = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(center.offset(-3, -1, -1), center.offset(3, -1, 1))) {
            level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
            blocks.add(pos.immutable());
        }
        for (var pos : List.of(center.west(3), center.east(3))) {
            level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
            blocks.add(pos);
        }
        return blocks;
    }

    private static ServerSubLevel ship(GameTestHelper helper) {
        var center = helper.absolutePos(new BlockPos(12, 8, 12));
        return SubLevelAssemblyHelper.assembleBlocks(
                helper.getLevel(),
                center,
                deck(helper.getLevel(), center),
                new BoundingBox3i(center.offset(-3, -1, -1), center.offset(3, 0, 1)));
    }

    private static void clean(ServerLevel level, ServerSubLevel ship) {
        var center = ship.getPlot().getCenterBlock();
        for (var pos : BlockPos.betweenClosed(center.offset(-3, -1, -1), center.offset(3, 0, 1))) {
            level.removeBlock(pos, false);
        }
    }

    private static void cross(GameTestHelper helper, TripwireEntity wire) {
        var level = helper.getLevel();
        var cow = EntityType.COW.create(level);
        cow.setNoAi(true);
        Vec3 mid = wire.endA().lerp(wire.endB(), 0.5);
        Vec3 span = wire.endB().subtract(wire.endA());
        Vec3 normal = new Vec3(-span.z, 0, span.x).normalize();
        var foot = mid.add(0, -cow.getBbHeight() * 0.4, 0);
        cow.setPos(foot.add(normal.scale(0.25)));
        level.getChunkAt(cow.blockPosition());
        level.addFreshEntity(cow);
        wire.tick();
        helper.assertFalse(wire.isRemoved(), "Approaching the wire does not trigger it early");
        cow.setPos(foot.subtract(normal.scale(0.25)));
        wire.tick();
        helper.assertTrue(wire.isRemoved(), "Walking through the wire triggers it");
        helper.assertTrue(
                level.getSignal(wire.anchorA(), Direction.UP) == 15
                        && level.getSignal(wire.anchorB(), Direction.UP) == 15,
                "Both local posts output redstone");
        cow.discard();
    }

    @GameTest(template = "empty", batch = "tripwire_world", timeoutTicks = 100)
    public static void worldWireStillTriggersAndPulseExpires(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = helper.absolutePos(new BlockPos(12, 8, 12));
        deck(level, center);
        var wire = connect(helper, center.west(3), center.east(3));
        level.setBlockAndUpdate(wire.anchorA().north(), Blocks.REDSTONE_LAMP.defaultBlockState());
        cross(helper, wire);
        helper.assertTrue(
                level.getBlockState(wire.anchorA().north()).getValue(RedstoneLampBlock.LIT),
                "Pulse reaches neighbours");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(TripwireSignal.strength(level, wire.anchorA()) == 0, "Pulse expires after 20 ticks");
            helper.assertFalse(
                    level.getBlockState(wire.anchorA().north()).getValue(RedstoneLampBlock.LIT), "Lamp switches off");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "tripwire_ship", timeoutTicks = 100)
    public static void shipWireFollowsMotionAndTriggersAtWorldPosition(GameTestHelper helper) {
        var level = helper.getLevel();
        var ship = ship(helper);
        helper.runAfterDelay(5, () -> {
            var center = ship.getPlot().getCenterBlock();
            var wire = connect(helper, center.west(3), center.east(3));
            var original = wire.position();
            for (int i = 0; i < 8; i++) {
                wire.tickCount++;
                wire.tick();
            }
            helper.assertFalse(wire.isRemoved(), "Own ship must not trip its wire");
            RigidBodyHandle.of(ship)
                    .teleport(
                            new Vector3d(original.x + 10, original.y + 3, original.z), new Quaterniond().rotateY(0.65));
            helper.runAfterDelay(3, () -> {
                wire.tick();
                var expectedA = ship.logicalPose().transformPosition(TripwireEntity.tie(center.west(3)));
                helper.assertTrue(
                        wire.endA().distanceToSqr(expectedA) < 1.0E-8, "Endpoint follows translated and rotated ship");
                helper.assertTrue(
                        wire.position().distanceToSqr(original) > 50, "Entity tracking follows wire midpoint");
                helper.assertTrue(
                        wire.getBoundingBox().contains(wire.endA())
                                && wire.getBoundingBox().contains(wire.endB()),
                        "World collision bounds follow both ends");
                cross(helper, wire);
                clean(level, ship);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "tripwire_assembly_save", timeoutTicks = 100)
    public static void existingWireSurvivesAssemblySaveAndClientSync(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = helper.absolutePos(new BlockPos(12, 8, 12));
        var blocks = deck(level, center);
        var wire = connect(helper, center.west(3), center.east(3));
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, center, blocks, new BoundingBox3i(center.offset(-3, -1, -1), center.offset(3, 0, 1)));
        helper.runAfterDelay(5, () -> {
            wire.tick();
            helper.assertFalse(
                    wire.isRemoved(), "Native tracking points preserve an already strung wire through assembly");
            helper.assertTrue(wire.ownerA().orElseThrow().equals(ship.getUniqueId()), "Owner updated after assembly");
            helper.assertTrue(
                    wire.anchorA().equals(ship.getPlot().getCenterBlock().west(3)),
                    "Storage address updated after assembly");
            var saved = new CompoundTag();
            wire.addAdditionalSaveData(saved);
            wire.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            var restored = ModEntityTypes.TRIPWIRE.get().create(level);
            restored.readAdditionalSaveData(saved);
            restored.tick();
            level.addFreshEntity(restored);
            helper.assertTrue(
                    restored.ownerA().equals(wire.ownerA()) && restored.endA().distanceToSqr(wire.endA()) < 1.0E-8,
                    "Saved wire restores ship identity and endpoint");
            var clientCopy = ModEntityTypes.TRIPWIRE.get().create(level);
            clientCopy.getEntityData().assignValues(restored.getEntityData().getNonDefaultValues());
            helper.assertTrue(
                    clientCopy.ownerA().equals(restored.ownerA())
                            && clientCopy.endB().equals(restored.endB()),
                    "Native entity sync carries local posts and both owner IDs");
            helper.assertTrue(
                    TripwireEntity.occupied(level, restored.anchorA()), "Occupied lookup uses world coordinates");
            helper.assertTrue(TripwireEntity.cutAt(level, restored.anchorB()), "Cutters find a wire on a sublevel");
            helper.assertTrue(
                    SubLevelTrackingPointSavedData.getOrLoad(level).getTrackingPoint(saved.getUUID("TrackingA")) == null
                            && SubLevelTrackingPointSavedData.getOrLoad(level)
                                            .getTrackingPoint(saved.getUUID("TrackingB"))
                                    == null,
                    "Cut wire releases its native tracking records");
            helper.assertTrue(
                    TripwireSignal.strength(level, restored.anchorA()) == 0, "Cutting does not activate the trap");
            clean(level, ship);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "tripwire_between_ships", timeoutTicks = 100)
    public static void spanUsesWorldDistanceBetweenSeparateSublevels(GameTestHelper helper) {
        var level = helper.getLevel();
        var a = helper.absolutePos(new BlockPos(8, 8, 12));
        var b = a.east(6);
        level.setBlockAndUpdate(a, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(b, Blocks.IRON_BLOCK.defaultBlockState());
        var first = SubLevelAssemblyHelper.assembleBlocks(level, a, List.of(a), new BoundingBox3i(a, a));
        var second = SubLevelAssemblyHelper.assembleBlocks(level, b, List.of(b), new BoundingBox3i(b, b));
        helper.runAfterDelay(5, () -> {
            var localA = first.getPlot().getCenterBlock();
            var localB = second.getPlot().getCenterBlock();
            helper.assertTrue(localA.distSqr(localB) > 64, "Different ships use distant storage plots");
            var wire = connect(helper, localA, localB);
            for (int i = 0; i < 8; i++) {
                wire.tickCount++;
                wire.tick();
            }
            helper.assertFalse(wire.isRemoved(), "Neither supporting hull trips the wire");
            var at = wire.endB();
            RigidBodyHandle.of(second).teleport(new Vector3d(at.x + 12, at.y, at.z), new Quaterniond());
            helper.runAfterDelay(3, () -> {
                wire.tick();
                helper.assertTrue(
                        wire.isRemoved() && TripwireSignal.strength(level, localA) == 15,
                        "Moving ships apart breaks taut wire");
                var player = player(helper);
                click(player, localA);
                click(player, localB);
                helper.assertTrue(
                        player.getMainHandItem().getCount() == 2
                                && TripwireCoilItem.pendingPost(player.getMainHandItem()) != null,
                        "Distant world endpoints are rejected without consuming the coil");
                level.removeBlock(localA, false);
                level.removeBlock(localB, false);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "tripwire_world_ship", timeoutTicks = 100)
    public static void worldToShipWireAndDuplicatePostChecks(GameTestHelper helper) {
        var level = helper.getLevel();
        var a = helper.absolutePos(new BlockPos(8, 8, 12));
        var b = a.east(6);
        level.setBlockAndUpdate(a, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(b, Blocks.IRON_BLOCK.defaultBlockState());
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, b, List.of(b), new BoundingBox3i(b, b));
        helper.runAfterDelay(5, () -> {
            var local = ship.getPlot().getCenterBlock();
            var wire = connect(helper, a, local);
            helper.assertTrue(
                    wire.ownerA().isEmpty() && wire.ownerB().isPresent(), "Mixed frame endpoints stay distinct");
            var player = player(helper);
            click(player, local);
            helper.assertTrue(
                    TripwireCoilItem.pendingPost(player.getMainHandItem()) == null
                            && player.getMainHandItem().getCount() == 2,
                    "An occupied ship post cannot accept a duplicate wire");
            helper.assertTrue(TripwireEntity.cutAt(level, local), "Cut mixed wire from ship post");
            helper.assertFalse(
                    TripwireEntity.occupied(level, a) || TripwireEntity.occupied(level, local), "Cut frees both posts");
            level.removeBlock(local, false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "tripwire_hull_shapes", timeoutTicks = 100)
    public static void otherHullNeedsRealGeometryAcrossTheLine(GameTestHelper helper) {
        var level = helper.getLevel();
        var ship = ship(helper);
        helper.runAfterDelay(5, () -> {
            var center = ship.getPlot().getCenterBlock();
            var a = ship.logicalPose().transformPosition(TripwireEntity.tie(center.west(2)));
            var b = ship.logicalPose().transformPosition(TripwireEntity.tie(center.east(2)));
            helper.assertFalse(
                    TripwireGeometry.crossesHull(level, a, b, Optional.empty(), Optional.empty(), 0.6),
                    "Empty deck space inside a hull bounding box is not a collision");
            level.setBlockAndUpdate(center, Blocks.IRON_BLOCK.defaultBlockState());
            helper.assertTrue(
                    TripwireGeometry.crossesHull(level, a, b, Optional.empty(), Optional.empty(), 0.6),
                    "Actual hull block crossing the run triggers the trap");
            helper.assertFalse(
                    TripwireGeometry.crossesHull(level, a, b, Optional.of(ship.getUniqueId()), Optional.empty(), 0.6),
                    "Supporting hull remains excluded even if its bounds surround the wire");
            clean(level, ship);
            helper.succeed();
        });
    }
}
