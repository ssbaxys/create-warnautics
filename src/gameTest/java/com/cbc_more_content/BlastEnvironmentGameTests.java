package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BlastWater;
import com.cbc_more_content.effects.WarnauticsExplosion;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class BlastEnvironmentGameTests {
    private static boolean scar(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL);
    }

    @GameTest(template = "empty", batch = "environment_profiles", timeoutTicks = 200)
    public static void allBombProfilesScarSurvivingGrass(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(90, 80, 90));
        for (var size : BombSize.values()) {
            for (var pos : BlockPos.betweenClosed(top.offset(-18, -8, -18), top.offset(18, 0, 18))) {
                level.setBlock(
                        pos, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            var blast = new WarnauticsExplosion(
                    level,
                    null,
                    BombDamageSource.create(level),
                    top.getCenter().add(0, 0.8, 0),
                    size.blockBlastPower,
                    size.entityBlastPower,
                    size.blastVolume());
            blast.setCanDamageTerrain(true);
            blast.explode();
            helper.assertTrue(blast.getToBlow().size() <= 2600, "Destruction and scars share the configured cap");
            blast.finalizeExplosion(false);
            int scars = 0;
            for (var pos : BlockPos.betweenClosed(top.offset(-18, -8, -18), top.offset(18, 0, 18))) {
                if (scar(level.getBlockState(pos))) {
                    scars++;
                }
            }
            helper.assertTrue(scars > 0, size + " must leave damaged ground after excavation");
        }
        for (var pos : BlockPos.betweenClosed(top.offset(-18, -8, -18), top.offset(18, 0, 18))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "environment_protection", timeoutTicks = 100)
    public static void vetoProtectsCraterAndSoilTogether(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(12, 8, 12));
        for (var p : BlockPos.betweenClosed(top.offset(-5, 0, -5), top.offset(5, 0, 5))) {
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        var blast = new WarnauticsExplosion(
                level,
                null,
                BombDamageSource.create(level),
                top.getCenter().add(0, 0.8, 0),
                3.625F,
                4,
                BombSize.BlastVolume.SPHERE);
        blast.setCanDamageTerrain(true);
        int[] calls = {0};
        Consumer<ExplosionEvent.Detonate> listener = event -> {
            if (event.getExplosion() == blast) {
                calls[0]++;
                event.getAffectedBlocks().clear();
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            blast.explode();
            blast.finalizeExplosion(false);
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.assertTrue(calls[0] == 1, "All terrain changes must use one protection event");
        for (var p : BlockPos.betweenClosed(top.offset(-5, 0, -5), top.offset(5, 0, 5))) {
            helper.assertTrue(level.getBlockState(p).is(Blocks.GRASS_BLOCK), "Veto also preserves grass");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "environment_water", timeoutTicks = 100)
    public static void waterEffectsNeedRealAccessibleWater(GameTestHelper helper) {
        var level = helper.getLevel();
        var p = helper.absolutePos(new BlockPos(12, 8, 12));
        helper.assertTrue(BlastWater.find(level, p.getCenter(), 3) == null, "Dry terrain has no splash source");
        level.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        var wet = BlastWater.find(level, p.getCenter(), 3);
        helper.assertTrue(wet != null && wet.surface() != null, "Open water has a surface");
        double height = p.getY() + level.getFluidState(p).getHeight(level, p);
        helper.assertTrue(
                Math.abs(wet.surface().y - height) < 0.001, "Splash must use the actual fluid height, not y+40");
        helper.assertTrue(BlastWater.find(level, p.getCenter().add(2, 0, 0), 3) != null, "Nearby shore water reacts");
        for (var q : BlockPos.betweenClosed(p.offset(1, -1, -2), p.offset(1, 2, 2))) {
            level.setBlock(q, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.assertTrue(
                BlastWater.find(level, p.getCenter().add(2, 0, 0), 3) == null,
                "A solid wall prevents a splash behind it");
        level.setBlock(p.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        wet = BlastWater.find(level, p.getCenter(), 0.2);
        helper.assertTrue(
                wet != null && wet.surface() == null, "Water under a solid ceiling must not spawn a fountain above it");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "environment_deepwater", timeoutTicks = 100)
    public static void deepWaterSurfaceStartsAtTheWaterColumn(GameTestHelper helper) {
        var level = helper.getLevel();
        var p = helper.absolutePos(new BlockPos(12, 8, 12));
        for (int y = 0; y < 50; y++) {
            level.setBlock(
                    p.above(y), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        var wet = BlastWater.find(level, p.getCenter(), 0.2);
        helper.assertTrue(wet != null && wet.surface() != null, "A deep water column must resolve its surface");
        double height = p.getY() + 49 + level.getFluidState(p.above(49)).getHeight(level, p.above(49));
        helper.assertTrue(Math.abs(wet.surface().y - height) < 0.001, "Deep splash follows the true surface");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "environment_soil_parity", timeoutTicks = 100)
    public static void soilScarsMatchOnWorldAndSubLevel(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(12, 8, 12));
        List<BlockPos> blocks = new ArrayList<>();
        for (var p : BlockPos.betweenClosed(top.offset(-6, -3, -6), top.offset(6, 0, 6))) {
            blocks.add(p.immutable());
        }
        blocks.forEach(p -> level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL));
        level.random.setSeed(9191);
        var blast = new WarnauticsExplosion(
                level,
                null,
                BombDamageSource.create(level),
                top.getCenter().add(0, 0.8, 0),
                3.625F,
                4,
                BombSize.BlastVolume.SPHERE);
        blast.setCanDamageTerrain(true);
        blast.explode();
        blast.finalizeExplosion(false);
        var expected = blocks.stream().map(level::getBlockState).toList();
        helper.assertTrue(expected.stream().anyMatch(BlastEnvironmentGameTests::scar), "Fixture must have scars");
        blocks.forEach(p -> level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL));
        var ship = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                level,
                top,
                blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(top.offset(-6, -3, -6), top.offset(6, 0, 6)));
        var local = ship.getPlot().getCenterBlock();
        level.random.setSeed(9191);
        var at = ship.logicalPose().transformPosition(local.getCenter()).add(0, 0.8, 0);
        blast = new WarnauticsExplosion(
                level, null, BombDamageSource.create(level), at, 3.625F, 4, BombSize.BlastVolume.SPHERE);
        blast.setCanDamageTerrain(true);
        blast.explode();
        blast.finalizeExplosion(false);
        for (int i = 0; i < blocks.size(); i++) {
            var p = local.offset(blocks.get(i).subtract(top));
            helper.assertTrue(
                    level.getBlockState(p).equals(expected.get(i)),
                    "Soil scar or crater differs on ship at " + blocks.get(i).subtract(top));
        }
        blocks.forEach(p -> level.removeBlock(local.offset(p.subtract(top)), false));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "environment_seamine", timeoutTicks = 100)
    public static void seaMineScarsGroundUnderwater(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(20, 15, 20));
        for (var p : BlockPos.betweenClosed(top.offset(-12, -5, -12), top.offset(12, 0, 12))) {
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (var p : BlockPos.betweenClosed(top.offset(-12, 1, -12), top.offset(12, 4, 12))) {
            level.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        com.cbc_more_content.effects.BombExplosionHandler.detonateSeaMine(
                level,
                null,
                com.cbc_more_content.damage.MineDamageSource.create(level, com.cbc_more_content.mine.MineType.SEA),
                top.getCenter().add(0, 1.2, 0),
                6.5F,
                6.5F);
        int scars = 0;
        int removed = 0;
        for (var p : BlockPos.betweenClosed(top.offset(-12, -5, -12), top.offset(12, 0, 12))) {
            var state = level.getBlockState(p);
            if (scar(state)) {
                scars++;
            }
            if (state.isAir() || state.is(Blocks.WATER)) {
                removed++;
            }
        }
        helper.assertTrue(scars > 0 && removed > 0, "Sea mine must excavate and scar soil through water");
        helper.assertTrue(
                level.getFluidState(top.offset(10, 2, 10)).is(net.minecraft.tags.FluidTags.WATER),
                "Blast preserves surrounding water");
        helper.succeed();
    }
}
