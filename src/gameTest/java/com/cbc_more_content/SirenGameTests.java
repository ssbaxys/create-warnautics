package com.cbc_more_content;

import com.cbc_more_content.block.SirenBlock;
import com.cbc_more_content.block.SirenBlockEntity;
import com.cbc_more_content.network.ModNetworking;
import com.cbc_more_content.network.SirenWailPayload;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.siren.BlastLog;
import com.cbc_more_content.siren.SirenSettings;
import com.cbc_more_content.siren.SirenSource;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class SirenGameTests {
    private static final SirenSettings MANUAL = new SirenSettings(false, 16, 0, false, false);

    @GameTest(template = "empty", batch = "siren_drive", timeoutTicks = 100)
    public static void createMotorDrivesAssembledSiren(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        siren(level, pos).applySettings(MANUAL);
        level.setBlock(
                pos.below(),
                com.simibubi.create.AllBlocks.CREATIVE_MOTOR
                        .get()
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.DirectionalBlock.FACING,
                                net.minecraft.core.Direction.UP),
                Block.UPDATE_ALL);
        var motor =
                (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) level.getBlockEntity(pos.below());
        motor.generatedSpeed.setValue(64);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.succeedWhen(() -> {
            var siren = siren(level, pos);
            helper.assertTrue(
                    Math.abs(siren.getSpeed()) == 64 && siren.isWailing(),
                    "Create network must drive the siren inside a Sable plot");
            level.removeBlock(pos.below(), false);
            cleanup(level, ship);
        });
    }

    private static ServerSubLevel ship(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(12, 8, 12));
        level.setBlock(center, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(center.east(), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(center.east(2), ModBlocks.SIREN.get().defaultBlockState(), Block.UPDATE_ALL);
        return SubLevelAssemblyHelper.assembleBlocks(
                level,
                center,
                List.of(center, center.east(), center.east(2)),
                new BoundingBox3i(center, center.east(2)));
    }

    private static BlockPos post(ServerSubLevel ship) {
        return ship.getPlot().getCenterBlock().east(2);
    }

    private static SirenBlockEntity siren(ServerLevel level, BlockPos pos) {
        return (SirenBlockEntity) level.getBlockEntity(pos);
    }

    private static void cleanup(ServerLevel level, ServerSubLevel ship) {
        level.removeBlock(post(ship).above(), false);
        level.removeBlock(post(ship).below(), false);
        level.removeBlock(post(ship), false);
        level.removeBlock(ship.getPlot().getCenterBlock().east(), false);
        level.removeBlock(ship.getPlot().getCenterBlock(), false);
    }

    private static ServerPlayer listener(ServerLevel level, Vec3 at, List<SirenWailPayload> packets) {
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "siren-listener"));
        player.connection =
                new ServerGamePacketListenerImpl(
                        level.getServer(),
                        new Connection(PacketFlow.SERVERBOUND),
                        player,
                        CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                    @Override
                    public void send(Packet<?> packet) {
                        if (packet instanceof ClientboundCustomPayloadPacket custom
                                && custom.payload() instanceof SirenWailPayload siren) {
                            packets.add(siren);
                        }
                    }

                    @Override
                    public void tick() {}

                    @Override
                    public void resetPosition() {}
                };
        player.setPos(at);
        level.addNewPlayer(player);
        return player;
    }

    private static SirenWailPayload last(List<SirenWailPayload> packets) {
        if (packets.isEmpty()) {
            throw new AssertionError("No siren packet reached the world-space listener");
        }
        return packets.getLast();
    }

    @GameTest(template = "empty", batch = "siren_redstone", timeoutTicks = 100)
    public static void shipRedstoneSendsAndStopsSound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        SirenBlockEntity siren = siren(level, pos);
        siren.applySettings(MANUAL);
        Vec3 here = SirenSource.capture(level, pos).worldPosition();
        List<SirenWailPayload> packets = new ArrayList<>();
        var player = listener(level, here.add(1, 0, 0), packets);
        try {
            level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            siren.setSpeed(0);
            siren.tick();
            helper.assertTrue(siren.wants() && !siren.isWailing() && packets.isEmpty(), "Drive is still required");
            siren.setSpeed(64);
            siren.tick();
            helper.assertTrue(last(packets).voice() == 1 && last(packets).remainingTicks() > 0, "Full speed sound");
            helper.assertTrue(
                    last(packets).source().subLevelId().equals(ship.getUniqueId()), "Ship identity in packet");
            helper.assertTrue(last(packets).source().worldPosition().distanceToSqr(here) < 0.001, "World sound origin");
            helper.assertTrue(level.getBlockState(pos).getValue(SirenBlock.SOUNDING), "Sounding block state");
            siren.setSpeed(32);
            siren.tick();
            helper.assertTrue(last(packets).voice() == 0.5f, "Rotor balance remains proportional");
            level.removeBlock(pos.above(), false);
            siren.tick();
            helper.assertTrue(last(packets).remainingTicks() == 0 && !siren.isWailing(), "Redstone off sends stop");
            level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            siren.setSpeed(64);
            siren.tick();
            siren.setSpeed(0);
            siren.tick();
            helper.assertTrue(last(packets).remainingTicks() == 0, "Stopped shaft sends stop");
            siren.setSpeed(64);
            siren.tick();
            level.removeBlock(pos, false);
            helper.assertTrue(last(packets).remainingTicks() == 0, "Broken siren sends stop");
        } finally {
            player.discard();
            cleanup(level, ship);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "siren_range", timeoutTicks = 150)
    public static void listenersEnteringAndLeavingReceiveUpdates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        var siren = siren(level, pos);
        siren.applySettings(MANUAL);
        level.setBlock(
                pos.below(),
                com.simibubi.create.AllBlocks.CREATIVE_MOTOR
                        .get()
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.DirectionalBlock.FACING,
                                net.minecraft.core.Direction.UP),
                Block.UPDATE_ALL);
        var motor =
                (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) level.getBlockEntity(pos.below());
        motor.generatedSpeed.setValue(64);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        Vec3 here = SirenSource.capture(level, pos).worldPosition();
        List<SirenWailPayload> packets = new ArrayList<>();
        var player = listener(level, here.add(400, 0, 0), packets);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(siren.isWailing(), "Siren must have a running Create motor");
            helper.assertTrue(packets.isEmpty(), "Out-of-range listener must not start");
            player.setPos(here.add(300, 0, 0));
        });
        helper.runAfterDelay(50, () -> {
            helper.assertTrue(last(packets).remainingTicks() > 0, "Distant listener starts on keepalive");
            player.setPos(here.add(400, 0, 0));
        });
        helper.runAfterDelay(95, () -> {
            try {
                helper.assertTrue(last(packets).remainingTicks() == 0, "Leaving listener receives stop");
            } finally {
                player.discard();
                cleanup(level, ship);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "siren_motion", timeoutTicks = 100)
    public static void sourceFollowsTranslationAndRotation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        SirenSource source = SirenSource.capture(level, pos);
        helper.runAfterDelay(4, () -> RigidBodyHandle.of(ship)
                .teleport(
                        new Vector3d(
                                source.worldPosition().x + 12, source.worldPosition().y + 3, source.worldPosition().z),
                        new Quaterniond().rotateY(Math.PI / 2)));
        helper.runAfterDelay(6, () -> {
            Vec3 expected = ship.logicalPose().transformPosition(pos.getCenter());
            helper.assertTrue(source.position(level).distanceToSqr(expected) < 0.000001, "Moving sound anchor");
            helper.assertTrue(source.position(level).distanceToSqr(source.worldPosition()) > 50, "Sound must move");
            SirenSource reused = new SirenSource(pos, UUID.randomUUID(), source.worldPosition());
            helper.assertTrue(
                    reused.position(level).equals(source.worldPosition()), "Unknown ship uses world fallback");
            helper.assertFalse(reused.samePost(source), "Reused plot must not inherit another ship's sound");
            cleanup(level, ship);
            helper.succeed();
        });
    }

    private static boolean threat(ServerLevel level, BlockPos pos) {
        try {
            var scan = SirenBlockEntity.class.getDeclaredMethod("threatNearby", ServerLevel.class, BlockPos.class);
            scan.setAccessible(true);
            return (boolean) scan.invoke(siren(level, pos), level, pos);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @GameTest(template = "empty", batch = "siren_missile", timeoutTicks = 100)
    public static void shipDetectsInboundWorldMissiles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        siren(level, pos).applySettings(new SirenSettings(true, 16, 10, true, false));
        Vec3 here = SirenSource.capture(level, pos).worldPosition();
        var missile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
        missile.setPos(here.add(10, 0, 0));
        missile.setDeltaMovement(-1, 0, 0);
        level.addFreshEntity(missile);
        try {
            helper.assertTrue(threat(level, pos), "Inbound world missile must be seen from ship");
            missile.setDeltaMovement(1, 0, 0);
            helper.assertFalse(threat(level, pos), "Outbound missile must not trigger");
            missile.setPos(here.add(20, 0, 0));
            missile.setDeltaMovement(-1, 0, 0);
            helper.assertFalse(threat(level, pos), "Detection radius remains unchanged");
        } finally {
            missile.discard();
            cleanup(level, ship);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "siren_bomb", timeoutTicks = 100)
    public static void shipDetectsWorldBombsAndBlast(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        var siren = siren(level, pos);
        siren.applySettings(new SirenSettings(true, 16, 10, false, true));
        Vec3 here = SirenSource.capture(level, pos).worldPosition();
        var bomb = ModEntityTypes.SMALL_BOMB.get().create(level);
        bomb.setPos(here.add(0, 10, 0));
        bomb.setDeltaMovement(0, -0.1, 0);
        level.addFreshEntity(bomb);
        helper.assertTrue(threat(level, pos), "Falling world bomb must be seen from ship");
        bomb.discard();
        var seaBomb = ModEntityTypes.SEA_BOMB.get().create(level);
        seaBomb.setPos(here.add(0, 10, 0));
        seaBomb.setDeltaMovement(0, -0.1, 0);
        level.addFreshEntity(seaBomb);
        helper.assertTrue(threat(level, pos), "Sea bomb must be seen from ship");
        seaBomb.discard();
        BlastLog.record(level, here.add(5, 0, 0));
        helper.assertTrue(threat(level, pos), "Recent world blast must be seen from ship");
        // The scan follows world time, not the test's start tick. Wait through a complete scan interval.
        helper.succeedWhen(() -> {
            siren.setSpeed(64);
            siren.tick();
            helper.assertTrue(siren.wants() && siren.isWailing(), "Automatic scan raises the alarm");
            siren.applySettings(MANUAL);
            helper.assertFalse(siren.wants(), "Disabling detection clears automatic linger");
            cleanup(level, ship);
        });
    }

    @GameTest(template = "empty", batch = "siren_reach", timeoutTicks = 100)
    public static void settingsReachUsesWorldDistance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = ship(helper);
        BlockPos pos = post(ship);
        Vec3 here = SirenSource.capture(level, pos).worldPosition();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "siren-settings"));
        try {
            var access = ModNetworking.class.getDeclaredMethod("canAccess", Player.class, BlockPos.class);
            access.setAccessible(true);
            player.setPos(here.add(2, 0, 0));
            helper.assertTrue((boolean) access.invoke(null, player, pos), "Nearby ship settings must be accepted");
            player.setPos(here.add(9, 0, 0));
            helper.assertFalse((boolean) access.invoke(null, player, pos), "Remote settings must still be refused");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        } finally {
            cleanup(level, ship);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "siren_codec", timeoutTicks = 100)
    public static void packetsPreserveShipAndWorldCoordinates(GameTestHelper helper) {
        for (UUID id : new UUID[] {null, UUID.randomUUID()}) {
            var expected = new SirenWailPayload(
                    new SirenSource(new BlockPos(29_000_010, 81, -29_000_015), id, new Vec3(31.25, 70.5, -52.75)),
                    60,
                    0.5f);
            var buf = new RegistryFriendlyByteBuf(
                    Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                SirenWailPayload.STREAM_CODEC.encode(buf, expected);
                helper.assertTrue(SirenWailPayload.STREAM_CODEC.decode(buf).equals(expected), "Packet round trip");
                helper.assertTrue(buf.readableBytes() == 0, "Codec consumes full packet");
            } finally {
                buf.release();
            }
            helper.assertTrue(
                    new SirenWailPayload(expected.source(), 6000, 1).remainingTicks() == 120,
                    "Lost server updates must expire even with a long alarm timer");
        }
        helper.succeed();
    }
}
