package com.cbc_more_content;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BlastScorch;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.item.BombVestItem;
import com.cbc_more_content.mine.MineType;
import java.util.HashSet;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class AllExplosiveSoilGameTests {
    @GameTest(template = "empty", batch = "soil_cannon", timeoutTicks = 100)
    public static void cannonSoilChangesAfterCraterIncludingSmallRounds(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(60, 60, 60));
        var floor = new java.util.ArrayList<BlockPos>();
        for (int i = 0; i < 3; i++) {
            var center = top.offset(i * 40, 0, 0);
            ground(helper, center, 14);
            var at = center.getCenter().add(0, 0.55, 0);
            float power = i == 1 ? 1.2F : 5;
            var blast = new rbasamoyai.createbigcannons.munitions.ShellExplosion(
                    level,
                    null,
                    com.cbc_more_content.damage.BombDamageSource.create(level),
                    at.x,
                    at.y,
                    at.z,
                    power,
                    power,
                    false,
                    net.minecraft.world.level.Explosion.BlockInteraction.DESTROY,
                    true);
            blast.setCanDamageTerrain(i != 2);
            if (i == 2) {
                // Exercise our listener's permission gate without asking CBC's own
                // explosion implementation to destroy this fixture first.
                NeoForge.EVENT_BUS.post(new ExplosionEvent.Detonate(level, blast, new java.util.ArrayList<>()));
            } else {
                blast.explode();
                blast.finalizeExplosion(false);
            }
            if (i == 0) {
                for (var p : BlockPos.betweenClosed(center.offset(-8, -5, -8), center.offset(8, -1, 8))) {
                    if (level.getBlockState(p).is(Blocks.GRASS_BLOCK)
                            && level.getBlockState(p.above()).isAir()) {
                        floor.add(p.immutable());
                    }
                }
            }
        }
        helper.assertFalse(floor.isEmpty(), "Shell must expose a new crater floor");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(
                    floor.stream().anyMatch(p -> !level.getBlockState(p).is(Blocks.GRASS_BLOCK)),
                    "Scarring must reach ground exposed by shell finalization");
            boolean smallScar = false;
            for (var p : BlockPos.betweenClosed(top.offset(37, 0, -3), top.offset(43, 0, 3))) {
                var state = level.getBlockState(p);
                smallScar |= state.is(Blocks.DIRT)
                        || state.is(Blocks.COARSE_DIRT)
                        || state.is(Blocks.ROOTED_DIRT)
                        || state.is(Blocks.PODZOL);
            }
            helper.assertTrue(smallScar, "Small explosive rounds below the mushroom FX threshold must also scar soil");
            assertGrass(helper, top.offset(80, 0, 0), 14);
            for (int i = 0; i < 3; i++) {
                clear(helper, top.offset(i * 40, 0, 0), 14);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "soil_all_charges", timeoutTicks = 200)
    public static void minesC4AndVestScarGrass(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(80, 70, 80));
        for (String name : new String[] {"small", "bounding", "anti_tank", "c4", "vest"}) {
            ground(helper, top, 18);
            Vec3 at = top.getCenter().add(0, name.equals("bounding") ? 1.8 : 0.8, 0);
            level.random.setSeed(123456);
            switch (name) {
                case "small", "bounding" -> {
                    var type = name.equals("small") ? MineType.SMALL : MineType.BOUNDING;
                    MineExplosionHandler.detonateSmallShrapnel(
                            level, null, MineDamageSource.create(level, type), at, type.entityBlastPower);
                }
                case "anti_tank" -> BombExplosionHandler.detonateAntiTankMine(
                        level,
                        null,
                        MineDamageSource.create(level, MineType.LARGE),
                        at,
                        MineType.LARGE.blockBlastPower,
                        MineType.LARGE.entityBlastPower);
                case "c4" -> C4BlockEntity.explode(level, at);
                case "vest" -> {
                    var wearer = EntityType.COW.create(level);
                    helper.assertTrue(wearer != null, "Wearer exists");
                    wearer.setPos(at);
                    BombVestItem.detonate(level, wearer);
                    wearer.discard();
                }
                default -> throw new IllegalStateException(name);
            }
            var materials = new HashSet<Block>();
            int count = 0;
            double farthest = 0;
            for (var p : BlockPos.betweenClosed(top.offset(-18, -5, -18), top.offset(18, 0, 18))) {
                var state = level.getBlockState(p);
                if (state.is(Blocks.DIRT)
                        || state.is(Blocks.COARSE_DIRT)
                        || state.is(Blocks.ROOTED_DIRT)
                        || state.is(Blocks.PODZOL)) {
                    materials.add(state.getBlock());
                    count++;
                    farthest = Math.max(farthest, Math.hypot(p.getX() - top.getX(), p.getZ() - top.getZ()));
                }
            }
            helper.assertTrue(count >= 20, name + " must leave substantial soil damage, got " + count);
            helper.assertTrue(materials.size() == 4, name + " must use all four soil variants");
            helper.assertTrue(farthest > 4, name + " must scar ground beyond the old small-mine radius");
        }
        clear(helper, top, 18);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "soil_standalone_protection", timeoutTicks = 100)
    public static void standaloneScarsRespectProtectionAndSetting(GameTestHelper helper) {
        var top = helper.absolutePos(new BlockPos(20, 15, 20));
        ground(helper, top, 8);
        Consumer<ExplosionEvent.Detonate> veto =
                event -> event.getAffectedBlocks().clear();
        NeoForge.EVENT_BUS.addListener(veto);
        try {
            BlastScorch.scuff(helper.getLevel(), top.getCenter().add(0, 0.8, 0), 8, BlastScorch.SCAR_STRENGTH);
        } finally {
            NeoForge.EVENT_BUS.unregister(veto);
        }
        assertGrass(helper, top, 8);
        var setting = rbasamoyai.createbigcannons.config.CBCConfigs.server().munitions.projectilesChangeSurroundings;
        boolean previous = setting.get();
        try {
            setting.set(false);
            BlastScorch.scuff(helper.getLevel(), top.getCenter().add(0, 0.8, 0), 8, BlastScorch.SCAR_STRENGTH);
        } finally {
            setting.set(previous);
        }
        assertGrass(helper, top, 8);
        clear(helper, top, 8);
        helper.succeed();
    }

    private static void assertGrass(GameTestHelper helper, BlockPos top, int radius) {
        for (var p : BlockPos.betweenClosed(top.offset(-radius, 0, -radius), top.offset(radius, 0, radius))) {
            helper.assertTrue(
                    helper.getLevel().getBlockState(p).is(Blocks.GRASS_BLOCK),
                    "Protected/disabled scarring must preserve grass");
        }
    }

    private static void ground(GameTestHelper helper, BlockPos top, int radius) {
        for (var p : BlockPos.betweenClosed(top.offset(-radius, -5, -radius), top.offset(radius, 0, radius))) {
            helper.getLevel()
                    .setBlock(
                            p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    private static void clear(GameTestHelper helper, BlockPos top, int radius) {
        for (var p : BlockPos.betweenClosed(top.offset(-radius, -5, -radius), top.offset(radius, 0, radius))) {
            helper.getLevel()
                    .setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
