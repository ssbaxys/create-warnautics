package com.cbc_more_content;

import com.cbc_more_content.block.LandMineBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BlastPropagation;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class BlastGeometryGameTests {
    private static final BlockPos CENTER = new BlockPos(12, 8, 12);

    @GameTest(template = "empty", batch = "geometry_cookoff", timeoutTicks = 160)
    public static void detachedCookoffKeepsWorldPosition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(CENTER);
        var registered = ModBlocks.SMALL_BOMB.get();
        level.setBlock(at, registered.defaultBlockState(), Block.UPDATE_ALL);
        var hull = SubLevelAssemblyHelper.assembleBlocks(level, at, List.of(at), new BoundingBox3i(at, at));
        BlockPos local = hull.getPlot().getCenterBlock();
        List<Vec3> detonations = new ArrayList<>();
        Consumer<com.cbc_more_content.event.WarnauticsBlockDetonateEvent> listener =
                event -> detonations.add(event.getCenter());
        NeoForge.EVENT_BUS.addListener(listener);
        var external = new net.minecraft.world.level.Explosion(
                level,
                null,
                at.getX(),
                at.getY(),
                at.getZ(),
                4,
                false,
                net.minecraft.world.level.Explosion.BlockInteraction.DESTROY);
        external.getToBlow().add(local);
        external.finalizeExplosion(false);
        helper.runAfterDelay(110, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            helper.assertTrue(
                    detonations.stream().anyMatch(point -> point.distanceToSqr(at.getCenter()) < 4),
                    "Detached bomb lost its world position: " + detonations);
            helper.succeed();
        });
    }

    private static WarnauticsExplosion explosion(ServerLevel level, Vec3 center) {
        return new WarnauticsExplosion(
                level, null, BombDamageSource.create(level), center, 11, 14, BombSize.BlastVolume.SPHERE);
    }

    @GameTest(template = "empty", batch = "geometry_veto", timeoutTicks = 100)
    public static void protectionVetoHasNoSideDestruction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(CENTER);
        helper.setBlock(CENTER, Blocks.DIRT);
        helper.setBlock(CENTER.east(2), Blocks.GLASS);
        var blast = explosion(level, center.getCenter().add(0, 1, 0));
        int[] calls = {0};
        Consumer<ExplosionEvent.Detonate> veto = event -> {
            if (event.getExplosion() == blast) {
                calls[0]++;
                event.getAffectedBlocks().clear();
                event.getAffectedEntities().clear();
            }
        };
        NeoForge.EVENT_BUS.addListener(veto);
        try {
            blast.explode();
            blast.finalizeExplosion(false);
        } finally {
            NeoForge.EVENT_BUS.unregister(veto);
        }
        helper.assertTrue(calls[0] == 1, "One real explosion must post one detonation event");
        helper.assertBlockPresent(Blocks.DIRT, CENTER);
        helper.assertBlockPresent(Blocks.GLASS, CENTER.east(2));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "geometry_cap", timeoutTicks = 120)
    public static void capSelectsSameNearbyBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(CENTER);
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-4, -4, -4), center.offset(4, 4, 4))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(p.immutable());
        }
        Vec3 origin = center.getCenter().add(-5.6, 0, 0);
        var control = explosion(level, origin);
        Set<BlockPos> expected = new HashSet<>();
        for (BlockPos p : BlastPropagation.gather(level, control, 11, BombSize.BlastVolume.SPHERE, 71, 32)) {
            expected.add(p.subtract(center));
        }
        helper.assertTrue(expected.size() == 32, "Control must reach the cap");
        var hull = SubLevelAssemblyHelper.assembleBlocks(
                level, center, blocks, new BoundingBox3i(center.offset(-4, -4, -4), center.offset(4, 4, 4)));
        BlockPos local = hull.getPlot().getCenterBlock();
        helper.runAfterDelay(5, () -> {
            var test = explosion(
                    level,
                    hull.logicalPose().transformPosition(local.getCenter()).add(-5.6, 0, 0));
            Set<BlockPos> actual = new HashSet<>();
            for (BlockPos p : BlastPropagation.gather(level, test, 11, BombSize.BlastVolume.SPHERE, 71, 32)) {
                actual.add(p.subtract(local));
            }
            helper.assertTrue(
                    actual.equals(expected), "Cap must rank world distance, not storage coordinates: " + actual);
            for (BlockPos p : BlockPos.betweenClosed(local.offset(-4, -4, -4), local.offset(4, 4, 4))) {
                level.removeBlock(p, false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "geometry_rotation", timeoutTicks = 120)
    public static void rotatedHullBlocksWorldBlast(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(CENTER);
        List<BlockPos> wall = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(0, -3, -3), center.offset(0, 3, 3))) {
            level.setBlock(p, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            wall.add(p.immutable());
        }
        var hull = SubLevelAssemblyHelper.assembleBlocks(
                level, center, wall, new BoundingBox3i(center.offset(0, -3, -3), center.offset(0, 3, 3)));
        helper.runAfterDelay(4, () -> RigidBodyHandle.of(hull)
                .teleport(
                        new Vector3d(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5),
                        new Quaterniond().rotateY(Math.PI / 2)));
        helper.runAfterDelay(6, () -> {
            Vec3 at = hull.logicalPose()
                    .transformPosition(hull.getPlot().getCenterBlock().getCenter());
            BlockPos behind = BlockPos.containing(at.add(0, 0, -2));
            BlockPos exposed = BlockPos.containing(at.add(0, 0, 2));
            level.setBlock(behind, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(exposed, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            var blast = explosion(level, at.add(0, 0, 3));
            blast.explode();
            blast.finalizeExplosion(false);
            helper.assertTrue(
                    level.getBlockState(behind).is(Blocks.OAK_PLANKS),
                    "Rotated hull must shield ordinary terrain behind it");
            helper.assertTrue(level.getBlockState(exposed).isAir(), "Exposed world block must break");
            BlockPos local = hull.getPlot().getCenterBlock();
            for (BlockPos p : BlockPos.betweenClosed(local.offset(0, -3, -3), local.offset(0, 3, 3))) {
                level.removeBlock(p, false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "geometry_smallmine", timeoutTicks = 100)
    public static void smallMineFragmentsStartInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(CENTER);
        helper.setBlock(CENTER, ModBlocks.SMALL_MINE.get());
        var hull = SubLevelAssemblyHelper.assembleBlocks(level, at, List.of(at), new BoundingBox3i(at, at));
        BlockPos local = hull.getPlot().getCenterBlock();
        LandMineBlock.detonate(level, local, level.getBlockState(local));
        var bursts = level.getEntitiesOfClass(
                rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst.class, new AABB(at).inflate(2));
        helper.assertFalse(
                bursts.isEmpty(),
                "Small mine fragments must spawn at its world position before the last plot block is removed");
        bursts.forEach(entity -> entity.discard());
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "geometry_bounding", timeoutTicks = 100)
    public static void boundingMinePopsIntoWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(CENTER);
        helper.setBlock(CENTER, ModBlocks.BOUNDING_MINE.get());
        var hull = SubLevelAssemblyHelper.assembleBlocks(level, at, List.of(at), new BoundingBox3i(at, at));
        BlockPos local = hull.getPlot().getCenterBlock();
        try {
            var trip = LandMineBlock.class.getDeclaredMethod(
                    "trip", ServerLevel.class, BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
            trip.setAccessible(true);
            trip.invoke(ModBlocks.BOUNDING_MINE.get(), level, local, level.getBlockState(local));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        var entities =
                level.getEntitiesOfClass(com.cbc_more_content.entity.BoundingMineEntity.class, new AABB(at).inflate(2));
        helper.assertFalse(entities.isEmpty(), "Bounding mine must pop into world space from a hull");
        entities.forEach(entity -> entity.discard());
        helper.succeed();
    }
}
