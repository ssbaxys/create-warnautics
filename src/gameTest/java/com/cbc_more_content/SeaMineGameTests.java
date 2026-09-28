package com.cbc_more_content;

import com.cbc_more_content.block.SeaMineBlock;
import com.cbc_more_content.block.SeaMineBlockEntity;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class SeaMineGameTests {
    private static final BlockPos CENTER = new BlockPos(12, 6, 12);

    private static void basin(GameTestHelper helper) {
        for (BlockPos p : BlockPos.betweenClosed(4, 1, 4, 20, 12, 20)) {
            boolean wall = p.getY() == 1 || p.getX() == 4 || p.getX() == 20 || p.getZ() == 4 || p.getZ() == 20;
            helper.setBlock(p, wall ? Blocks.BEDROCK : Blocks.WATER);
        }
    }

    private static SeaMineBlockEntity mine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(CENTER);
        helper.setBlock(CENTER, ModBlocks.SEA_MINE.get());
        ServerSubLevel body =
                SubLevelAssemblyHelper.assembleBlocks(level, world, List.of(world), new BoundingBox3i(world, world));
        level.setBlock(world, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        return (SeaMineBlockEntity) level.getBlockEntity(body.getPlot().getCenterBlock());
    }

    @GameTest(template = "empty", batch = "sea_corrosion_surface", timeoutTicks = 100)
    public static void partiallySubmergedMineOxidizesThroughAllStages(GameTestHelper helper) {
        basin(helper);
        var level = helper.getLevel();
        var mine = mine(helper);
        for (var p : BlockPos.betweenClosed(CENTER.offset(-7, 0, -7), CENTER.offset(7, 6, 7))) {
            helper.setBlock(p, Blocks.AIR);
        }
        var host = com.cbc_more_content.compat.SableDropCompat.containingSubLevel(level, mine.getBlockPos());
        host.logicalPose().position().add(0, -0.3, 0);
        var center = SeaMineBlockEntity.worldPosition(level, mine.getBlockPos());
        helper.assertFalse(
                level.getFluidState(BlockPos.containing(center)).is(net.minecraft.tags.FluidTags.WATER),
                "The mine centre must be above water");
        helper.assertTrue(SeaMineBlockEntity.touchesWater(level, center), "Its casing is still wet");
        for (int stage = 1; stage <= 3; stage++) {
            mine.setCorrosionAge(stage * SeaMineBlockEntity.OXIDATION_TICKS_PER_STAGE - 1);
            SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
            helper.assertTrue(
                    mine.getBlockState().getValue(SeaMineBlock.OXIDATION) == stage,
                    "Corrosion must update the visible stage at the surface");
            var saved = mine.saveWithoutMetadata(level.registryAccess());
            mine.loadWithComponents(saved, level.registryAccess());
            helper.assertTrue(
                    mine.corrosionAge() == stage * SeaMineBlockEntity.OXIDATION_TICKS_PER_STAGE,
                    "Wet tick progress survives saving");
        }
        host.logicalPose().position().add(0, 4, 0);
        mine.setCorrosionAge(1234);
        for (int i = 0; i < 20; i++) {
            SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
        }
        helper.assertTrue(mine.corrosionAge() == 1234, "Dry mine must not gain wet ticks");
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sea_corrosion_command", timeoutTicks = 100)
    public static void corrosionDebugCommandTargetsMovingMineAndRequiresOperator(GameTestHelper helper) {
        basin(helper);
        var level = helper.getLevel();
        var mine = mine(helper);
        var center = SeaMineBlockEntity.worldPosition(level, mine.getBlockPos());
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "corrosion-test"));
        player.setPos(center.x, center.y - player.getEyeHeight(), center.z - 3);
        player.setXRot(0);
        player.setYRot(0);
        var dispatcher = level.getServer().getCommands().getDispatcher();
        try {
            int result = dispatcher.execute(
                    "cw debug sea_mine stage 3",
                    player.createCommandSourceStack().withPermission(2));
            helper.assertTrue(
                    result == 1 && mine.getBlockState().getValue(SeaMineBlock.OXIDATION) == 3,
                    "Operator command must update a plot-local mine in world space");
            boolean refused = false;
            try {
                dispatcher.execute(
                        "cw debug sea_mine stage 0",
                        player.createCommandSourceStack().withPermission(0));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "Normal players must not change corrosion with debug commands");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sea_world_damage", timeoutTicks = 100)
    public static void fullMineDetonationBreaksOrdinaryUnderwaterBlocks(GameTestHelper helper) {
        basin(helper);
        var level = helper.getLevel();
        var mine = mine(helper);
        var saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("ArmingTicks", 0);
        mine.loadWithComponents(saved, level.registryAccess());
        BlockPos target = helper.absolutePos(CENTER.east(2));
        level.setBlock(target, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        mine.detonate(level);
        helper.assertTrue(mine.isRemoved(), "Full mine detonation must remove the charge");
        helper.assertFalse(
                level.getBlockState(target).is(Blocks.IRON_BLOCK),
                "An ordinary underwater hull block must not be immune merely because it is not assembled");
        helper.assertTrue(level.getBlockState(helper.absolutePos(CENTER)).is(Blocks.WATER), "Water is not removed");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sea_iron_damage", timeoutTicks = 100)
    public static void fullMineDetonationBreaksSubmergedIronHull(GameTestHelper helper) {
        basin(helper);
        var level = helper.getLevel();
        var mine = mine(helper);
        var saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("ArmingTicks", 0);
        mine.loadWithComponents(saved, level.registryAccess());
        BlockPos target = helper.absolutePos(CENTER.east(2));
        level.setBlock(target, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        var hull = SubLevelAssemblyHelper.assembleBlocks(
                level, target, List.of(target), new BoundingBox3i(target, target));
        level.setBlock(target, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        mine.detonate(level);
        helper.assertTrue(mine.isRemoved(), "Mine must detonate before its host is discarded");
        helper.assertTrue(
                hull.isRemoved()
                        || !level.getBlockState(hull.getPlot().getCenterBlock()).is(Blocks.IRON_BLOCK),
                "Full mine detonation must break a submerged iron hull");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sea_damage", timeoutTicks = 100)
    public static void fullDetonationDamagesLivingEntitiesAndBoats(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        SeaMineBlockEntity mine = mine(helper);
        CompoundTag saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("ArmingTicks", 0);
        mine.loadWithComponents(saved, level.registryAccess());
        Vec3 at = SeaMineBlockEntity.worldPosition(level, mine.getBlockPos());
        var victim = net.minecraft.world.entity.EntityType.COW.create(level);
        victim.setPos(at.add(2, 0, 0));
        victim.setNoAi(true);
        level.addFreshEntity(victim);
        float health = victim.getHealth();
        var boat = net.minecraft.world.entity.EntityType.BOAT.create(level);
        boat.setPos(at.add(0, 0, 0.8));
        level.addFreshEntity(boat);
        try {
            SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
            helper.assertTrue(
                    mine.saveWithoutMetadata(level.registryAccess()).getBoolean("Triggered"),
                    "Nearby boat must trigger the armed mine");
            SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
            helper.assertFalse(level.getBlockState(mine.getBlockPos()).is(ModBlocks.SEA_MINE.get()), "Mine detonates");
            helper.assertTrue(victim.getHealth() < health, "Underwater mine must damage nearby living entities");
            helper.assertTrue(boat.isRemoved() || boat.getDamage() > 0, "Underwater mine must damage vanilla boats");
        } finally {
            victim.discard();
            boat.discard();
            level.removeBlock(mine.getBlockPos(), false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void waterPlacementAssemblesAndRestoresWater(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(CENTER);
        helper.setBlock(CENTER, ModBlocks.SEA_MINE.get().defaultBlockState().setValue(SeaMineBlock.WATERLOGGED, true));
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(level.getBlockState(world).is(Blocks.WATER), "Assembly must restore displaced water");
            for (ServerSubLevel body : SubLevelContainer.getContainer(level).getAllSubLevels()) {
                BlockPos local = body.getPlot().getCenterBlock();
                if (level.getBlockEntity(local) instanceof SeaMineBlockEntity mine
                        && SeaMineBlockEntity.worldPosition(level, local).distanceToSqr(world.getCenter()) < 4) {
                    helper.assertFalse(
                            mine.getBlockState().getValue(SeaMineBlock.WATERLOGGED),
                            "Floating mine must not carry a water source; actual=" + level.getBlockState(local));
                    level.removeBlock(local, false);
                    helper.succeed();
                    return;
                }
            }
            helper.fail("Placed mine did not become a floating physics body");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void floatsWithoutAerodynamicLift(GameTestHelper helper) {
        basin(helper);
        SeaMineBlockEntity mine = mine(helper);
        Vec3 start = SeaMineBlockEntity.worldPosition(helper.getLevel(), mine.getBlockPos());
        helper.assertFalse(mine.isArmed(), "New mine must allow time to leave");
        helper.runAfterDelay(80, () -> {
            Vec3 end = SeaMineBlockEntity.worldPosition(helper.getLevel(), mine.getBlockPos());
            helper.assertTrue(end.y > start.y + 0.25, "Submerged mine should rise: " + start + " -> " + end);
            helper.assertTrue(end.y < start.y + 8, "Mine should remain near the water surface");
            helper.getLevel().removeBlock(mine.getBlockPos(), false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "sea_legacy_mooring", timeoutTicks = 100)
    public static void oldMooringIsIgnoredOnLoad(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        SeaMineBlockEntity mine = mine(helper);
        CompoundTag saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putLong("Anchor", helper.absolutePos(CENTER.below(5)).asLong());
        saved.putUUID("AnchorSubLevel", java.util.UUID.randomUUID());
        saved.putDouble("RopeLength", 5);
        saved.putInt("ArmingTicks", 1);
        mine.loadWithComponents(saved, level.registryAccess());
        SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
        CompoundTag current = mine.saveWithoutMetadata(level.registryAccess());
        helper.assertFalse(
                current.contains("Anchor") || current.contains("AnchorSubLevel") || current.contains("RopeLength"),
                "Old saves must not restore a mooring");
        helper.assertTrue(mine.isArmed(), "Removing mooring data must preserve arming progress");
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void swimmerContactUsesWorldCoordinates(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        SeaMineBlockEntity mine = mine(helper);
        CompoundTag saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("ArmingTicks", 0);
        mine.loadWithComponents(saved, level.registryAccess());
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(
                level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "sea-mine-test"));
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 center = SeaMineBlockEntity.worldPosition(level, mine.getBlockPos());
        player.setPos(center.x, center.y, center.z);
        level.addNewPlayer(player);
        SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
        helper.assertTrue(
                mine.saveWithoutMetadata(level.registryAccess()).getBoolean("Triggered"),
                "Swimmer touching a moving mine must trip it outside the storage plot");
        player.discard();
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oxidationChangesModelAndInvalidRopesAreDiscarded(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        SeaMineBlockEntity mine = mine(helper);
        CompoundTag saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("WaterAge", SeaMineBlockEntity.OXIDATION_TICKS_PER_STAGE - 1);
        saved.putLong("Anchor", helper.absolutePos(new BlockPos(12, 1, 12)).asLong());
        saved.putDouble("RopeLength", Double.NaN);
        mine.loadWithComponents(saved, level.registryAccess());
        helper.assertTrue(
                !mine.saveWithoutMetadata(level.registryAccess()).contains("Anchor"),
                "Corrupt NBT must not create a NaN native constraint");
        SeaMineBlockEntity.tick(level, mine.getBlockPos(), mine.getBlockState(), mine);
        helper.assertValueEqual(
                level.getBlockState(mine.getBlockPos()).getValue(SeaMineBlock.OXIDATION), 1, "oxidation model stage");
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void underwaterBlastBreaksHullAndTerrain(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(CENTER);
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(p.immutable());
        }
        ServerSubLevel hull = SubLevelAssemblyHelper.assembleBlocks(
                level, center, blocks, new BoundingBox3i(center.offset(-1, -1, -1), center.offset(1, 1, 1)));
        for (BlockPos p : blocks) {
            level.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos local = hull.getPlot().getCenterBlock();
        // An ordinary-world block next to the hull must obey the same destruction rules.
        BlockPos terrain = center.offset(2, -2, 0);
        level.setBlock(terrain, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(5, () -> {
            Vec3 blast = hull.logicalPose().transformPosition(local.getCenter()).add(2, 0, 0);
            BombExplosionHandler.detonateSeaMine(
                    level,
                    null,
                    MineDamageSource.create(level, MineType.SEA),
                    blast,
                    MineType.SEA.blockBlastPower,
                    MineType.SEA.entityBlastPower);
            int remaining = 0;
            for (BlockPos p : BlockPos.betweenClosed(local.offset(-1, -1, -1), local.offset(1, 1, 1))) {
                if (level.getBlockState(p).is(Blocks.OAK_PLANKS)) {
                    remaining++;
                }
            }
            helper.assertTrue(remaining < 27, "Underwater hull remained completely intact");
            helper.assertFalse(level.getBlockState(terrain).is(Blocks.DIRT), "Sea mine must damage nearby terrain too");
            helper.assertTrue(level.getBlockState(center).is(Blocks.WATER), "Sea mine must not remove water");
            for (BlockPos p : BlockPos.betweenClosed(local.offset(-1, -1, -1), local.offset(1, 1, 1))) {
                level.removeBlock(p, false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "sea_collision", timeoutTicks = 100)
    public static void physicalHullContactDetonatesMine(GameTestHelper helper) {
        basin(helper);
        ServerLevel level = helper.getLevel();
        SeaMineBlockEntity mine = mine(helper);
        CompoundTag saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("ArmingTicks", 0);
        mine.loadWithComponents(saved, level.registryAccess());
        BlockPos world = helper.absolutePos(CENTER.offset(3, 0, 0));
        level.setBlock(world, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        ServerSubLevel hull =
                SubLevelAssemblyHelper.assembleBlocks(level, world, List.of(world), new BoundingBox3i(world, world));
        level.setBlock(world, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(5, () -> {
            Vec3 at = SeaMineBlockEntity.worldPosition(level, mine.getBlockPos());
            var handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(hull);
            handle.teleport(new org.joml.Vector3d(at.x + 1.4D, at.y, at.z), new org.joml.Quaterniond());
            handle.addLinearAndAngularVelocity(new org.joml.Vector3d(-5, 0, 0), new org.joml.Vector3d());
        });
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(
                    mine.isRemoved(),
                    "Native hull collision must detonate the armed mine; mine="
                            + SeaMineBlockEntity.worldPosition(level, mine.getBlockPos()) + " hull="
                            + hull.logicalPose()
                                    .transformPosition(
                                            hull.getPlot().getCenterBlock().getCenter()) + " armed=" + mine.isArmed());
            if (!hull.isRemoved()) {
                helper.assertFalse(
                        level.getBlockState(hull.getPlot().getCenterBlock()).is(Blocks.OAK_PLANKS),
                        "Physical contact blast must destroy the block that struck the mine");
                level.removeBlock(hull.getPlot().getCenterBlock(), false);
            }
            helper.succeed();
        });
    }
}
