package com.cbc_more_content;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.event.WarnauticsBlockDetonateEvent;
import com.cbc_more_content.munitions.MissileFlightProfile;
import com.cbc_more_content.registry.ModEntityTypes;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class SmallHullMissileGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final TicketType<Long> FIXTURE = TicketType.create("small_hull_missile", Long::compareTo, 100);

    @GameTest(template = "empty", batch = "small_hull_direct", timeoutTicks = 100)
    public static void directFlightReachesAndBreaksSmallMovingHull(GameTestHelper helper) {
        impact(helper, MissileFlightProfile.DIRECT);
    }

    @GameTest(template = "empty", batch = "small_hull_arc", timeoutTicks = 100)
    public static void arcFlightReachesAndBreaksSmallMovingHull(GameTestHelper helper) {
        impact(helper, MissileFlightProfile.ARC);
    }

    @GameTest(template = "empty", batch = "small_hull_evasive", timeoutTicks = 100)
    public static void evasiveFlightReachesAndBreaksSmallMovingHull(GameTestHelper helper) {
        impact(helper, MissileFlightProfile.EVASIVE);
    }

    private static ServerSubLevel hull(ServerLevel level, BlockPos at) {
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(at), 3, at.asLong());
        var blocks = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            level.setBlock(pos, Blocks.IRON_BLOCK.defaultBlockState(), FLAGS);
            blocks.add(pos.immutable());
        }
        return SubLevelAssemblyHelper.assembleBlocks(
                level, at, blocks, new BoundingBox3i(at.offset(-1, -1, -1), at.offset(1, 1, 1)));
    }

    private static void impact(GameTestHelper helper, MissileFlightProfile profile) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(60, 110, 60));
        var ship = hull(level, at);
        helper.runAfterDelay(4, () -> {
            var handle = RigidBodyHandle.of(ship);
            handle.teleport(
                    new Vector3d(at.getX() + .5, at.getY() + .5, at.getZ() + .5), new Quaterniond().rotateY(.45));
            handle.addLinearAndAngularVelocity(new Vector3d(4, 0, 0), new Vector3d());
        });
        helper.runAfterDelay(7, () -> {
            var local = ship.getPlot().getCenterBlock();
            var center = ship.logicalPose().transformPosition(local.getCenter());
            var missile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
            // This fixture verifies damage on a successful direct hit, not the separate miss lottery.
            missile.setUUID(new java.util.UUID(0, 1));
            missile.setPos(center.add(-12, 0, 0));
            missile.launch(new Vec3(1, 0, 0));
            missile.setGuidance(Guidance.LOCK, null, ship.getRuntimeId());
            missile.setFlightProfile(profile);
            List<Vec3> detonations = new ArrayList<>();
            Consumer<WarnauticsBlockDetonateEvent> listener = event -> {
                if (event.getExplosion().getDirectSourceEntity() == missile) {
                    detonations.add(ship.logicalPose().transformPositionInverse(event.getCenter()));
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                for (int tick = 0; tick < 25 && !missile.isRemoved(); tick++) {
                    missile.tickCount++;
                    missile.tick();
                }
                helper.assertTrue(detonations.size() == 1, "Guided run must produce exactly one impact explosion");
                Vec3 hit = detonations.getFirst().subtract(local.getCenter());
                helper.assertTrue(
                        Math.abs(hit.x) <= 1.501 && Math.abs(hit.y) <= 1.501 && Math.abs(hit.z) <= 1.501,
                        "Small hull must be struck, not proximity-fused outside it: " + hit);
                int removed = 0;
                for (var pos : BlockPos.betweenClosed(local.offset(-1, -1, -1), local.offset(1, 1, 1))) {
                    if (level.getBlockState(pos).isAir()) {
                        removed++;
                    }
                }
                helper.assertTrue(
                        removed >= 22, "Direct hit must severely damage a compact iron hull: " + removed + "/27");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
                missile.discard();
                clear(level, local);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "small_hull_miss", timeoutTicks = 100)
    public static void passingWideDoesNotDetonateAsIfItHitTheHullOrCoordinate(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(60, 110, 60));
        var ship = hull(level, at);
        helper.runAfterDelay(5, () -> {
            var local = ship.getPlot().getCenterBlock();
            var center = ship.logicalPose().transformPosition(local.getCenter());
            for (var guidance : List.of(Guidance.LOCK, Guidance.COORDINATES)) {
                var missile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
                missile.setPos(center.add(-10, 12, 0));
                missile.setGuidance(guidance, BlockPos.containing(center), ship.getRuntimeId());
                var tag = missile.saveWithoutId(new CompoundTag());
                tag.putInt("Fuel", 0);
                tag.putBoolean("Powered", false);
                missile.load(tag);
                missile.setDeltaMovement(1.4, 0, 0);
                for (int tick = 0; tick < 20 && !missile.isRemoved(); tick++) {
                    missile.tickCount++;
                    missile.tick();
                }
                try {
                    helper.assertFalse(
                            missile.isRemoved(), guidance + " must not explode several blocks away on a miss");
                } finally {
                    missile.discard();
                }
            }
            clear(level, local);
            helper.succeed();
        });
    }

    private static void clear(ServerLevel level, BlockPos local) {
        for (var pos : BlockPos.betweenClosed(local.offset(-1, -1, -1), local.offset(1, 1, 1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }
}
