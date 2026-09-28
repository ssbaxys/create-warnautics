package com.cbc_more_content;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class BlastImpulseGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final TicketType<Long> TEST_TICKET =
            TicketType.create("warnautics_impulse_test", Long::compareTo, 100);

    @GameTest(template = "empty", batch = "impulse_mass_power_distance", timeoutTicks = 100)
    public static void nativeResponseScalesWithMassPowerAndDistance(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 80, 80));
        var light = hull(level, base, 1, false);
        var heavy = hull(level, base.east(64), 8, false);
        var strong = hull(level, base.east(128), 1, false);
        var distant = hull(level, base.east(192), 1, false);
        List<ServerSubLevel> bodies = List.of(light, heavy, strong, distant);
        helper.runAfterDelay(5, () -> {
            double[] initial =
                    bodies.stream().mapToDouble(body -> velocity(body).x).toArray();
            blast(level, center(light).add(-2, 0, 0), 4);
            blast(level, center(heavy).add(-2, 0, 0), 4);
            blast(level, center(strong).add(-2, 0, 0), 8);
            blast(level, center(distant).add(-4, 0, 0), 4);
            helper.runAfterDelay(2, () -> {
                double smallKick = velocity(light).x - initial[0];
                double heavyKick = velocity(heavy).x - initial[1];
                double strongKick = velocity(strong).x - initial[2];
                double farKick = velocity(distant).x - initial[3];
                String values =
                        "light=" + smallKick + ", heavy=" + heavyKick + ", strong=" + strongKick + ", far=" + farKick;
                CBCMoreContent.LOGGER.info("Blast impulse comparison (m/s): {}", values);
                helper.assertTrue(smallKick > 0.01 && heavyKick > 0, "Both hulls receive outward momentum: " + values);
                helper.assertTrue(
                        heavyKick < smallKick * 0.35, "Same exposed face but greater mass must move less: " + values);
                helper.assertTrue(strongKick > smallKick * 1.4, "A stronger explosion must push harder: " + values);
                helper.assertTrue(strongKick <= 12.01, "Close explosions must not give tiny hulls unbounded speed");
                helper.assertTrue(
                        farKick > 0 && farKick < smallKick * 0.8,
                        "Pressure and intercepted area fall with distance: " + values);
                bodies.forEach(body -> clear(level, body));
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "impulse_occlusion", timeoutTicks = 100)
    public static void oneBlastPushesExposedHullButNotShieldedOrDistantHulls(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(80, 80, 80));
        var exposed = hull(level, origin.east(3), 1, false);
        var shielded = hull(level, origin.west(4), 1, false);
        var outside = hull(level, origin.east(30), 1, false);
        List<BlockPos> wall = new ArrayList<>();
        for (var pos : BlockPos.betweenClosed(origin.offset(-2, -6, -6), origin.offset(-2, 6, 6))) {
            level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), FLAGS);
            wall.add(pos.immutable());
        }
        helper.runAfterDelay(5, () -> {
            blast(level, origin.getCenter(), 5);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(velocity(exposed).x > 0.01, "Exposed hull must be pushed away");
                helper.assertTrue(Math.abs(velocity(shielded).x) < 0.001, "A solid world wall must block the impulse");
                helper.assertTrue(
                        Math.abs(velocity(outside).x) < 0.001, "Hull outside the pressure radius must not move");
                List.of(exposed, shielded, outside).forEach(body -> clear(level, body));
                wall.forEach(pos -> level.removeBlock(pos, false));
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "impulse_breach", timeoutTicks = 100)
    public static void damagedHullStillReceivesMomentumFromBrokenPlanks(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = hull(level, helper.absolutePos(new BlockPos(80, 80, 80)), 5, true);
        helper.runAfterDelay(5, () -> {
            var explosion = blast(level, center(body).add(-2, 0, 0), 5.2f);
            int broken = explosion.destroyedBlocks().size();
            helper.assertTrue(broken > 0, "Close blast must break the wooden face");
            helper.assertFalse(body.isRemoved(), "The reinforced back of the hull must survive");
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(velocity(body).x > 0.01, "A breached hull still receives pressure momentum");
                CBCMoreContent.LOGGER.info("Breached hull: {} broken blocks, velocity={} m/s", broken, velocity(body));
                clear(level, body);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "impulse_rotated", timeoutTicks = 100)
    public static void rotatedHullMovesAwayAndOffCenterBlastProducesSpin(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(80, 80, 80));
        var body = hull(level, at, 1, false);
        helper.runAfterDelay(3, () -> RigidBodyHandle.of(body)
                .teleport(
                        new Vector3d(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5),
                        new Quaterniond().rotateY(Math.PI / 2)));
        helper.runAfterDelay(6, () -> {
            blast(level, center(body).add(-2, 1, 0), 5);
            helper.runAfterDelay(2, () -> {
                var linear = velocity(body);
                var angular = RigidBodyHandle.of(body).getAngularVelocity(new Vector3d());
                helper.assertTrue(linear.x > 0.01, "Rotated hull must move away in world coordinates: " + linear);
                helper.assertTrue(Math.abs(angular.z) > 0.001, "Off-center pressure must turn the hull: " + angular);
                helper.assertTrue(linear.isFinite() && angular.isFinite(), "Blast response must stay finite");
                clear(level, body);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "impulse_fragmentation_mine", timeoutTicks = 100)
    public static void fragmentationMinePushesHullWithoutChangingItsCraterBalance(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = hull(level, helper.absolutePos(new BlockPos(80, 80, 80)), 1, false);
        helper.runAfterDelay(5, () -> {
            var at = center(body).add(-1.5, 0, 0);
            MineExplosionHandler.detonateSmallShrapnel(
                    level, null, MineDamageSource.create(level, MineType.SMALL), at, MineType.SMALL.entityBlastPower);
            level.getEntitiesOfClass(ShrapnelBurst.class, new AABB(at, at).inflate(10))
                    .forEach(ShrapnelBurst::discard);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(velocity(body).x > 0.001, "Even a small mine transfers a weak pressure impulse");
                helper.assertTrue(
                        level.getBlockState(body.getPlot().getCenterBlock()).is(Blocks.OBSIDIAN),
                        "Pressure alone must not excavate a shrapnel-mine crater");
                clear(level, body);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "impulse_configured_mass", timeoutTicks = 100)
    public static void loadedPhysicsPropertiesUseCassetteCountAndWholeMoabMass(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(12, 8, 12));
        for (int count = 1; count <= 4; count++) {
            var state = ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.CASSETTE, count);
            double mass = PhysicsBlockPropertyHelper.getMass(level, at, state);
            helper.assertTrue(Math.abs(mass - 0.625 * count) < 1e-6, "Loaded cassette mass must count remaining bombs");
        }
        double total = 0;
        for (var part : MoabBlock.Part.values()) {
            var state = ModBlocks.MOAB.get().defaultBlockState().setValue(MoabBlock.PART, part);
            total += PhysicsBlockPropertyHelper.getMass(level, at, state);
        }
        helper.assertTrue(Math.abs(total - 10.5) < 1e-6, "The three MOAB cells must total 10.5 mass units: " + total);
        helper.succeed();
    }

    private static WarnauticsExplosion blast(ServerLevel level, Vec3 center, float power) {
        level.random.setSeed(93271L);
        var explosion = new WarnauticsExplosion(
                level, null, BombDamageSource.create(level), center, power, power, BombSize.BlastVolume.SPHERE);
        explosion.setCanDamageTerrain(true);
        explosion.explode();
        explosion.finalizeExplosion(false);
        return explosion;
    }

    private static ServerSubLevel hull(ServerLevel level, BlockPos front, int depth, boolean wood) {
        level.getChunkSource().addRegionTicket(TEST_TICKET, new ChunkPos(front), 2, front.asLong());
        List<BlockPos> blocks = new ArrayList<>();
        var low = front.offset(0, -1, -1);
        var high = front.offset(depth - 1, 1, 1);
        for (var pos : BlockPos.betweenClosed(low, high)) {
            var block = wood && pos.getX() < high.getX() ? Blocks.OAK_PLANKS : Blocks.OBSIDIAN;
            level.setBlock(pos, block.defaultBlockState(), FLAGS);
            blocks.add(pos.immutable());
        }
        return SubLevelAssemblyHelper.assembleBlocks(level, front, blocks, new BoundingBox3i(low, high));
    }

    private static Vec3 center(ServerSubLevel body) {
        return body.logicalPose()
                .transformPosition(body.getPlot().getCenterBlock().getCenter());
    }

    private static Vector3d velocity(ServerSubLevel body) {
        return RigidBodyHandle.of(body).getLinearVelocity(new Vector3d());
    }

    private static void clear(ServerLevel level, ServerSubLevel body) {
        var box = body.getPlot().getBoundingBox();
        List<BlockPos> positions = new ArrayList<>();
        for (var pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            positions.add(pos.immutable());
        }
        positions.forEach(pos -> level.removeBlock(pos, false));
    }
}
