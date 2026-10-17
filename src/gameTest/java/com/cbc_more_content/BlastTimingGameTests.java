package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BlastGlassShatter;
import com.cbc_more_content.effects.BlastPropagation;
import com.cbc_more_content.effects.BlastScorch;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.effects.WarnauticsExplosion;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side cost of a detonation on realistic ground: soil over stone with glazed
 * buildings around it. Logs the time spent in each stage so the slow one can be found,
 * and keeps the whole detonation inside a budget.
 */
@GameTestHolder("warnautics_timing")
@PrefixGameTestTemplate(false)
public class BlastTimingGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int FAR = 30000;

    private record Charge(String name, BombSize size, float block, float entity) {}

    private static final List<Charge> CHARGES = List.of(
            new Charge("small", BombSize.SMALL, 5.2f, 6.5f),
            new Charge("medium", BombSize.MEDIUM, 7.333f, 9.333f),
            new Charge("large", BombSize.LARGE, 12f, 16.5f),
            new Charge(
                    "cruise",
                    BombSize.MOAB,
                    BombSize.MOAB.blockBlastPower * .85f,
                    BombSize.MOAB.entityBlastPower * .85f),
            new Charge("moab", BombSize.MOAB, 33f, 56f));

    @GameTest(template = "empty", batch = "blast_timing", timeoutTicks = 400)
    public static void detonationStagesStayWithinBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StringBuilder report = new StringBuilder("Blast timing (ms):");
        for (int i = 0; i < CHARGES.size(); i++) {
            Charge charge = CHARGES.get(i);
            // Far outside the test grid so the terrain and craters never reach another test.
            BlockPos staged = helper.absolutePos(new BlockPos(FAR + i * 400, 80, FAR));
            terrain(level, staged);
            Vec3 origin = staged.getCenter().add(0, .8, 0);
            var blast = new WarnauticsExplosion(
                    level,
                    null,
                    BombDamageSource.create(level),
                    origin,
                    charge.block(),
                    charge.entity(),
                    charge.size().blastVolume());
            int cap = WarnauticsConfig.maxBlocksPerDetonation();
            long t0 = System.nanoTime();
            var crater = BlastPropagation.gather(
                    level, blast, charge.block(), charge.size().blastVolume(), 7L, cap);
            long t1 = System.nanoTime();
            var glass = BlastGlassShatter.gather(
                    level, origin, charge.size().blastVolume().horizontal(blast.radius()), crater, cap - crater.size());
            long t2 = System.nanoTime();
            double scarRadius = Math.max(
                    5,
                    charge.size().blastVolume().horizontal(blast.radius())
                            * (charge.size().blastVolume().isSphere() ? 2.2 : 0.85));
            var scars = BlastScorch.gather(
                    level, origin, scarRadius, BlastScorch.SCAR_STRENGTH, crater, BlastScorch.changeBudget(), 7L);
            long t3 = System.nanoTime();
            StringBuilder full = new StringBuilder();
            for (int repeat = 1; repeat <= 3; repeat++) {
                BlockPos live = staged.offset(0, 0, 400 * repeat);
                terrain(level, live);
                Vec3 liveOrigin = live.getCenter().add(0, .8, 0);
                long start = System.nanoTime();
                BombExplosionHandler.detonate(
                        level,
                        null,
                        BombDamageSource.create(level),
                        liveOrigin,
                        charge.block(),
                        charge.entity(),
                        charge.size());
                full.append(String.format("%s%.1f", repeat == 1 ? "" : "/", (System.nanoTime() - start) / 1e6));
            }
            report.append(String.format(
                    " | %s: rays=%.1f (%d) glass=%.1f (%d) scorch=%.1f (%d) full=%s",
                    charge.name(),
                    (t1 - t0) / 1e6,
                    crater.size(),
                    (t2 - t1) / 1e6,
                    glass.size(),
                    (t3 - t2) / 1e6,
                    scars.size(),
                    full));
        }
        CBCMoreContent.LOGGER.info(report.toString());
        helper.succeed();
    }

    /** Grass over dirt over stone, with glazed houses at increasing distances. */
    private static void terrain(ServerLevel level, BlockPos top) {
        int radius = 60;
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-radius, -20, -radius), top.offset(radius, 0, radius))) {
            var material = p.getY() == top.getY()
                    ? Blocks.GRASS_BLOCK
                    : p.getY() < top.getY() - 3 ? Blocks.STONE : Blocks.DIRT;
            level.setBlock(p, material.defaultBlockState(), FLAGS);
        }
        for (int distance : new int[] {18, 32, 48}) {
            for (int side = 0; side < 4; side++) {
                BlockPos corner =
                        top.offset(side % 2 == 0 ? distance : -distance, 1, side < 2 ? distance / 2 : -distance / 2);
                for (BlockPos p : BlockPos.betweenClosed(corner, corner.offset(5, 3, 5))) {
                    boolean shell = p.getX() == corner.getX()
                            || p.getX() == corner.getX() + 5
                            || p.getZ() == corner.getZ()
                            || p.getZ() == corner.getZ() + 5
                            || p.getY() == corner.getY() + 3;
                    level.setBlock(p, (shell ? Blocks.GLASS : Blocks.AIR).defaultBlockState(), FLAGS);
                }
            }
        }
    }
}
