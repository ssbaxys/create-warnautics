package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder("warnautics_aim9")
@PrefixGameTestTemplate(false)
public class Aim9ObstructionGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static void rack(GameTestHelper h, BlockPos pos) {
        ((Aim9Block) ModBlocks.AIM9.get())
                .setPlacedBy(
                        h.getLevel(),
                        pos,
                        ModBlocks.AIM9.get().defaultBlockState().setValue(Aim9Block.FACING, Direction.UP),
                        null,
                        ItemStack.EMPTY);
    }

    private static CruiseMissileProjectile target(GameTestHelper h, Vec3 at) {
        var missile = ModEntityTypes.CRUISE_MISSILE.get().create(h.getLevel());
        missile.setPos(at);
        missile.launch(new Vec3(1, 0, 0));
        h.getLevel().addFreshEntity(missile);
        return missile;
    }

    @GameTest(template = "empty", batch = "aim9_wall", timeoutTicks = 100)
    public static void wallsAndEjectionRoofKeepRoundInItsRack(GameTestHelper h) {
        var level = h.getLevel();
        var body = h.absolutePos(new BlockPos(20, 120, 20));
        rack(h, body);
        var target = target(h, body.getCenter().add(80, 0, 0));
        var wall = body.east(3);
        level.setBlock(wall, Blocks.BEDROCK.defaultBlockState(), FLAGS);
        h.assertTrue(
                Aim9Block.launch(level, body, level.getBlockState(body), target) == null,
                "Cannot launch through world wall");
        h.assertTrue(
                ((Aim9BlockEntity) level.getBlockEntity(body)).isLiveAirframe(), "Blocked rack keeps all three parts");
        level.removeBlock(wall, false);
        var roof = body.above(4);
        level.setBlock(roof, Blocks.BEDROCK.defaultBlockState(), FLAGS);
        h.assertTrue(
                Aim9Block.launch(level, body, level.getBlockState(body), target) == null,
                "Visible target cannot permit launch through roof");
        level.removeBlock(roof, false);
        var shot = Aim9Block.launch(level, body, level.getBlockState(body), target);
        h.assertTrue(shot != null, "Open rack launches immediately after clearance");
        shot.discard();
        target.discard();
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_first_tick", timeoutTicks = 100)
    public static void firstEjectionTickCannotPassThroughNewlyPlacedRoof(GameTestHelper h) {
        var at = h.absolutePos(new BlockPos(20, 120, 20));
        var target = target(h, at.getCenter().add(80, 0, 0));
        var shot = ModEntityTypes.AIM9.get().create(h.getLevel());
        shot.setPos(at.getCenter());
        shot.launch(target, Vec3.ZERO);
        h.getLevel().setBlock(at.above(), Blocks.BEDROCK.defaultBlockState(), FLAGS);
        shot.tickCount++;
        shot.tick();
        h.assertTrue(shot.isRemoved(), "Cold ejection has collision from its first tick");
        h.assertTrue(target.isAlive(), "Roof collision cannot intercept a target through cover");
        target.discard();
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_carrier_wall", timeoutTicks = 100)
    public static void rotatingCarrierWallsAndRoofBlockUntilOpened(GameTestHelper h) {
        var level = h.getLevel();
        var body = h.absolutePos(new BlockPos(20, 120, 20));
        rack(h, body);
        var cells = new ArrayList<BlockPos>(List.of(body.below(), body, body.above(), body.east(3)));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                cells.add(body.offset(x, 4, z));
                cells.add(body.offset(x, -2, z));
            }
        }
        for (int y = -1; y <= 3; y++) {
            cells.add(body.offset(-3, y, 0));
        }
        cells.add(body.offset(3, -1, 0));
        for (var cell : cells) {
            if (!level.getBlockState(cell).is(ModBlocks.AIM9.get())) {
                level.setBlock(cell, Blocks.BEDROCK.defaultBlockState(), FLAGS);
            }
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, body, cells, new BoundingBox3i(body.offset(-3, -2, -3), body.offset(3, 4, 3)));
        h.runAfterDelay(4, () -> {
            RigidBodyHandle.of(ship)
                    .teleport(
                            new Vector3d(body.getX() + .5, body.getY() + .5, body.getZ() + .5),
                            new Quaterniond().rotateY(.6).rotateZ(.25));
        });
        h.runAfterDelay(7, () -> {
            var local = ship.getPlot().getCenterBlock();
            var target = target(
                    h, ship.logicalPose().transformPosition(local.getCenter().add(80, 0, 0)));
            h.assertTrue(
                    Aim9Block.launch(level, local, level.getBlockState(local), target) == null,
                    "Own rotated hull blocks target line of sight");
            level.setBlock(local.east(3), Blocks.AIR.defaultBlockState(), FLAGS);
            h.assertTrue(
                    Aim9Block.launch(level, local, level.getBlockState(local), target) == null,
                    "Rotated roof blocks world-up ejection");
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    level.setBlock(local.offset(x, 4, z), Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
            var shot = Aim9Block.launch(level, local, level.getBlockState(local), target);
            var f = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(
                    level, local.getCenter(), Vec3.ZERO, new Vec3(0, 1, 0));
            var ignored = java.util.Set.copyOf(
                    com.cbc_more_content.compat.AirframeMovement.cells(level, local, level.getBlockState(local)));
            h.assertTrue(
                    shot != null,
                    "Open physical rack: live="
                            + (level.getBlockEntity(local) instanceof Aim9BlockEntity be && be.isLiveAirframe())
                            + " target=" + target.isAlive() + " origin=" + f.pos() + " velocity=" + f.vel() + " cells="
                            + ignored
                            + " LOS="
                            + com.cbc_more_content.compat.SableDropCompat.clearRay(
                                    level, f.pos(), target.position(), ignored)
                            + " eject="
                            + com.cbc_more_content.compat.SableDropCompat.clearRay(
                                    level,
                                    f.pos(),
                                    f.pos().add(f.vel().scale(14)).add(0, 10.99, 0),
                                    ignored));
            shot.discard();
            target.discard();
            h.succeed();
        });
    }
}
