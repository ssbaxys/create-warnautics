package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.util.assembly.SimAssemblyContraption;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder("warnautics_aim9")
@PrefixGameTestTemplate(false)
public class AirframeAssemblyGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static BlockState cell(Block block, Direction facing, int offset) {
        var state = block.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
        if (block instanceof Aim9Block) {
            return state.setValue(
                    Aim9Block.PART,
                    offset < 0 ? Aim9Block.Part.TAIL : offset > 0 ? Aim9Block.Part.NOSE : Aim9Block.Part.BODY);
        }
        if (block instanceof CruiseMissileBlock) {
            return state.setValue(
                    CruiseMissileBlock.PART,
                    offset < 0
                            ? CruiseMissileBlock.Part.TAIL
                            : offset > 0 ? CruiseMissileBlock.Part.NOSE : CruiseMissileBlock.Part.BODY);
        }
        return state.setValue(
                MoabBlock.PART,
                offset < 0 ? MoabBlock.Part.TAIL : offset > 0 ? MoabBlock.Part.NOSE : MoabBlock.Part.BODY);
    }

    private static void place(GameTestHelper h, BlockPos body, Block block, Direction facing) {
        for (int offset = -1; offset <= 1; offset++) {
            h.getLevel().setBlock(body.relative(facing, offset), cell(block, facing, offset), FLAGS);
        }
    }

    @GameTest(template = "empty", batch = "airframe_glue", timeoutTicks = 100)
    public static void oneGluedCellCollectsEveryPartFromEitherSideInAllOrientations(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var body = h.absolutePos(new BlockPos(50, 120, 50));
        for (var block : List.of(ModBlocks.AIM9.get(), ModBlocks.CRUISE_MISSILE.get(), ModBlocks.MOAB.get())) {
            for (var facing : Direction.values()) {
                for (int part = -1; part <= 1; part++) {
                    place(h, body, block, facing);
                    BlockPos glued = body.relative(facing, part);
                    BlockPos hull =
                            glued.relative(facing.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.EAST);
                    level.setBlock(hull, Blocks.IRON_BLOCK.defaultBlockState(), FLAGS);
                    BlockPos unrelated = body.offset(6, 0, 0);
                    level.setBlock(unrelated, Blocks.IRON_BLOCK.defaultBlockState(), FLAGS);
                    var glue = new SuperGlueEntity(level, SuperGlueEntity.span(glued, hull));
                    level.addFreshEntity(glue);
                    for (var seed : List.of(glued, hull)) {
                        var assembly = new SimAssemblyContraption(null, false);
                        h.assertTrue(assembly.searchMovedStructure(level, seed), "Native assembly succeeds");
                        h.assertTrue(
                                assembly.getBlocks().size() == 4,
                                "Exactly hull and three airframe parts: " + block + " " + facing + " " + part + " "
                                        + assembly.getBlocks());
                        for (int i = -1; i <= 1; i++) {
                            h.assertTrue(
                                    assembly.getBlocks().contains(body.relative(facing, i)),
                                    "No orphaned airframe cell");
                        }
                        h.assertFalse(assembly.getBlocks().contains(unrelated), "Nearby unglued blocks stay separate");
                    }
                    glue.discard();
                    for (var pos : List.of(
                            body, body.relative(facing), body.relative(facing.getOpposite()), hull, unrelated)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_native_sable_scan", timeoutTicks = 1000)
    public static void automaticallyInterceptsAfterOnlyTailIsGluedToMovingCarrier(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        level.resetEmptyTime();
        var body = h.absolutePos(new BlockPos(50, 160, 50));
        var fixture =
                net.minecraft.server.level.TicketType.<Long>create("native_aim9_carrier_test", Long::compareTo, 1200);
        level.getChunkSource()
                .addRegionTicket(fixture, new net.minecraft.world.level.ChunkPos(body), 3, body.asLong(), true);
        place(h, body, ModBlocks.AIM9.get(), Direction.UP);
        ((Aim9BlockEntity) level.getBlockEntity(body)).configure(true, true, 220);
        var hull = body.below().east();
        level.setBlock(hull, Blocks.IRON_BLOCK.defaultBlockState(), FLAGS);
        var glue = new SuperGlueEntity(level, SuperGlueEntity.span(body.below(), hull));
        level.addFreshEntity(glue);
        var assembled = SimAssemblyHelper.assembleFromSingleBlock(level, hull, hull, true, true);
        h.assertTrue(assembled != null, "Actual Simulated assembly creates a body");
        var ship = (dev.ryanhcode.sable.sublevel.ServerSubLevel) assembled.subLevel();
        var local = body.offset(assembled.offset());
        h.assertTrue(
                level.getBlockEntity(local) instanceof Aim9BlockEntity, "Launcher ticker moved with complete airframe");
        var be = (Aim9BlockEntity) level.getBlockEntity(local);
        h.assertTrue(be.enabled() && be.range() == 220, "Settings retained");
        CruiseMissileProjectile[] contact = {null};
        Aim9Projectile[] shot = {null};
        h.runAfterDelay(20, () -> {
            h.assertFalse(
                    ship.isRemoved(),
                    "Ship removed before launching: body=" + level.getBlockState(local) + " tail="
                            + level.getBlockState(local.below()) + " hull="
                            + level.getBlockState(hull.offset(assembled.offset())));
            var handle = RigidBodyHandle.of(ship);
            h.assertTrue(
                    handle != null && handle.isValid(),
                    "Physics body becomes available after native Simulated assembly");
            handle.teleport(
                    new Vector3d(body.getX() + .5, body.getY() + .5, body.getZ() + .5), new Quaterniond().rotateZ(.25));
            handle.addLinearAndAngularVelocity(new Vector3d(2, 0, 0), new Vector3d(0, .08, 0));
            var center = ship.logicalPose().transformPosition(local.getCenter());
            for (int x = -3; x <= 12; x++) {
                for (int z = -3; z <= 3; z++) {
                    level.getChunk(
                            BlockPos.containing(center).getX() / 16 + x,
                            BlockPos.containing(center).getZ() / 16 + z);
                }
            }
            contact[0] = ModEntityTypes.CRUISE_MISSILE.get().create(level);
            contact[0].setPos(center.add(90, 10, 0));
            level.getChunkSource()
                    .addRegionTicket(
                            fixture,
                            new net.minecraft.world.level.ChunkPos(contact[0].blockPosition()),
                            3,
                            contact[0].blockPosition().asLong(),
                            true);
            contact[0].launch(new Vec3(1, 0, 0));
            level.addFreshEntity(contact[0]);
        });
        h.onEachTick(() -> {
            if (contact[0] == null) {
                return;
            }
            for (var entity : level.getEntitiesOfClass(Aim9Projectile.class, new AABB(body).inflate(1000))) {
                if (contact[0].getUUID().equals(entity.targetId())) {
                    shot[0] = entity;
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(
                    contact[0] != null && shot[0] != null,
                    "Actual Sable ticker: live=" + be.isLiveAirframe() + " enabled=" + be.enabled()
                            + " removedShip=" + ship.isRemoved() + " actor="
                            + (be instanceof dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor)
                            + " targetTicks=" + (contact[0] == null ? -1 : contact[0].tickCount)
                            + " origin=" + ship.logicalPose().transformPosition(local.getCenter())
                            + " target=" + (contact[0] == null ? null : contact[0].position()));
            h.assertTrue(
                    contact[0].isRemoved() && shot[0].isRemoved(),
                    "Moving cruise missile intercepted from moving Sable carrier");
            h.assertTrue(level.getBlockState(local).isAir(), "Rack consumed");
            level.getEntitiesOfClass(SuperGlueEntity.class, new AABB(local).inflate(4))
                    .forEach(Entity::discard);
            level.setBlock(hull.offset(assembled.offset()), Blocks.AIR.defaultBlockState(), FLAGS);
        });
    }
}
