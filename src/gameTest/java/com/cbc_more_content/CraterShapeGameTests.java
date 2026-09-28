package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BlastPropagation;
import com.cbc_more_content.effects.WarnauticsExplosion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class CraterShapeGameTests {
    @GameTest(template = "empty", batch = "crater_large", timeoutTicks = 120)
    public static void largeCrater(GameTestHelper helper) {
        crater(helper, BombSize.LARGE, 1, "large");
    }

    @GameTest(template = "empty", batch = "crater_moab", timeoutTicks = 120)
    public static void moabCrater(GameTestHelper helper) {
        crater(helper, BombSize.MOAB, 1, "moab");
    }

    @GameTest(template = "empty", batch = "crater_cruise", timeoutTicks = 120)
    public static void cruiseCrater(GameTestHelper helper) {
        crater(helper, BombSize.MOAB, 0.85F, "cruise");
    }

    private static void crater(GameTestHelper helper, BombSize size, float scale, String name) {
        var level = helper.getLevel();
        BlockPos top = helper.absolutePos(new BlockPos(100, 80, 100));
        int radius = 40;
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-radius, -18, -radius), top.offset(radius, 0, radius))) {
            var material = p.getY() == top.getY()
                    ? Blocks.GRASS_BLOCK
                    : p.getY() < top.getY() - 3 ? Blocks.STONE : Blocks.DIRT;
            level.setBlock(p, material.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        var origin = top.getCenter().add(0, 0.8, 0);
        var blast = new WarnauticsExplosion(
                level,
                null,
                BombDamageSource.create(level),
                origin,
                size.blockBlastPower * scale,
                size.entityBlastPower * scale,
                size.blastVolume());
        for (long seed : new long[] {193847L, 71L, 2026L}) {
            long start = System.nanoTime();
            var affected = new HashSet<>(BlastPropagation.gather(
                    level, blast, size.blockBlastPower * scale, size.blastVolume(), seed, 2600));
            double millis = (System.nanoTime() - start) / 1.0e6;
            StringBuilder json = new StringBuilder("{\"size\":\"" + size + "\",\"millis\":" + millis + ",\"blocks\":[");
            boolean first = true;
            for (BlockPos p : affected) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('[')
                        .append(p.getX() - top.getX())
                        .append(',')
                        .append(p.getY() - top.getY())
                        .append(',')
                        .append(p.getZ() - top.getZ())
                        .append(']');
            }
            json.append("]}");
            try {
                Files.createDirectories(Path.of("craters"));
                Files.writeString(Path.of("craters", name + "-" + seed + ".json"), json);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            helper.assertFalse(affected.isEmpty(), "A surface blast must excavate a crater");
            helper.assertTrue(affected.size() <= 2600, "The configured block limit must hold");
            double previousDepth = Double.POSITIVE_INFINITY;
            int bandWidth = size == BombSize.MOAB ? 4 : 3;
            for (int band = 0; band < 6; band++) {
                int cells = 0;
                int broken = 0;
                int surfaceHoles = 0;
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        double distance = Math.hypot(x, z);
                        if (distance < band * bandWidth || distance >= (band + 1) * bandWidth) {
                            continue;
                        }
                        cells++;
                        if (!affected.contains(top.offset(x, 0, z))) {
                            surfaceHoles++;
                        }
                        for (int y = -18; y <= 0; y++) {
                            if (affected.contains(top.offset(x, y, z))) {
                                broken++;
                            }
                        }
                    }
                }
                double depth = broken / (double) cells;
                helper.assertTrue(
                        depth <= previousDepth + 0.05,
                        size + " crater must grow shallower outwards; seed=" + seed + " band=" + band + " depths="
                                + previousDepth + " -> " + depth);
                if (band < 2) {
                    helper.assertTrue(surfaceHoles == 0, "The core must not contain ray-grid stripes");
                }
                if (size == BombSize.MOAB && band == 0) {
                    helper.assertTrue(depth > 3.5, name + " must have a pronounced core; actual depth=" + depth);
                }
                previousDepth = depth;
            }
        }

        // Check the actual final terrain too: scars and excavation compete for the
        // shared block budget, so raw ray selection alone cannot prove the result.
        level.random.setSeed(193847L);
        blast = new WarnauticsExplosion(
                level,
                null,
                BombDamageSource.create(level),
                origin,
                size.blockBlastPower * scale,
                size.entityBlastPower * scale,
                size.blastVolume());
        blast.setCanDamageTerrain(true);
        long start = System.nanoTime();
        blast.explode();
        helper.assertTrue(blast.getToBlow().size() <= 2600, "Scars and crater must share the block limit");
        blast.finalizeExplosion(false);
        double millis = (System.nanoTime() - start) / 1.0e6;
        var removed = new java.util.ArrayList<int[]>();
        var scars = new java.util.ArrayList<Object[]>();
        double farthestScar = 0;
        int coreBlocks = 0;
        int coreColumns = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (Math.hypot(x, z) < 4) {
                    coreColumns++;
                }
                for (int y = -18; y <= 0; y++) {
                    var state = level.getBlockState(top.offset(x, y, z));
                    if (state.isAir()) {
                        removed.add(new int[] {x, y, z});
                        if (Math.hypot(x, z) < 4) {
                            coreBlocks++;
                        }
                    } else {
                        var initial = y == 0 ? Blocks.GRASS_BLOCK : y < -3 ? Blocks.STONE : Blocks.DIRT;
                        if (!state.is(initial)) {
                            scars.add(new Object[] {
                                x,
                                y,
                                z,
                                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                        .getKey(state.getBlock())
                                        .toString()
                            });
                            farthestScar = Math.max(farthestScar, Math.hypot(x, z));
                        }
                    }
                }
            }
        }
        try {
            Files.writeString(
                    Path.of("craters", name + "-final.json"),
                    new com.google.gson.Gson()
                            .toJson(java.util.Map.of(
                                    "size", name, "millis", millis, "blocks", removed, "scars", scars)));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        helper.assertTrue(scars.size() > 256, name + " should leave more surface changes than the previous cap");
        helper.assertTrue(
                farthestScar > (name.equals("moab") ? 35 : 28),
                "Damaged ground should extend beyond the old footprint");
        if (size == BombSize.MOAB) {
            helper.assertTrue(coreBlocks / (double) coreColumns > 3.5, "Final crater must retain the deeper core");
        }

        for (BlockPos p : BlockPos.betweenClosed(top.offset(-radius, -18, -radius), top.offset(radius, 0, radius))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        helper.succeed();
    }
}
