package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.registry.ModEntityTypes;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class BlastParityGameTests {
    private static final BlockPos CENTER = new BlockPos(12, 8, 12);

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_smallBomb")
    public static void smallBomb(GameTestHelper helper) {
        compare(helper, BombSize.SMALL, false);
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_seaBomb")
    public static void seaBomb(GameTestHelper helper) {
        compare(helper, BombSize.SEA, false);
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_mediumBomb")
    public static void mediumBomb(GameTestHelper helper) {
        compare(helper, BombSize.MEDIUM, false);
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_largeBomb")
    public static void largeBomb(GameTestHelper helper) {
        compare(helper, BombSize.LARGE, false);
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_moab")
    public static void moab(GameTestHelper helper) {
        compare(helper, BombSize.MOAB, false);
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_cruiseMissile")
    public static void cruiseMissile(GameTestHelper helper) {
        compare(helper, BombSize.MOAB, false, true);
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "parity_antiTankMine")
    public static void antiTankMine(GameTestHelper helper) {
        compare(helper, BombSize.LARGE, true);
    }

    private static List<BlockPos> fixture(ServerLevel level, BlockPos center) {
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 3, 3))) {
            var material = p.getX() == center.getX() + 2 ? Blocks.STONE : Blocks.OAK_PLANKS;
            level.setBlock(p, material.defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(p.immutable());
        }
        return blocks;
    }

    private static Set<BlockPos> destroyed(ServerLevel level, BlockPos center) {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 3, 3))) {
            if (level.getBlockState(p).isAir()) {
                result.add(p.subtract(center));
            }
        }
        return result;
    }

    private static void blast(ServerLevel level, Vec3 center, BombSize size, boolean mine, boolean missile) {
        if (missile) {
            var projectile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
            projectile.setPos(center);
            level.random.setSeed(193847L);
            projectile.hurt(level.damageSources().generic(), 1);
            return;
        }
        level.random.setSeed(193847L);
        if (mine) {
            BombExplosionHandler.detonateAntiTankMine(
                    level,
                    null,
                    com.cbc_more_content.damage.MineDamageSource.create(
                            level, com.cbc_more_content.mine.MineType.LARGE),
                    center,
                    3.4f,
                    4.5f);
        } else {
            BombExplosionHandler.detonate(
                    level,
                    null,
                    BombDamageSource.create(level),
                    center,
                    size.blockBlastPower,
                    size.entityBlastPower,
                    size);
        }
    }

    private static void compare(GameTestHelper helper, BombSize size, boolean mine) {
        compare(helper, size, mine, false);
    }

    private static void compare(GameTestHelper helper, BombSize size, boolean mine, boolean missile) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(CENTER);
        fixture(level, center);
        blast(level, center.getCenter().add(-4.6, 0, 0), size, mine, missile);
        Set<BlockPos> expected = destroyed(level, center);
        helper.assertFalse(expected.isEmpty(), "World control fixture must be damaged");
        List<BlockPos> blocks = fixture(level, center);
        var hull = SubLevelAssemblyHelper.assembleBlocks(
                level, center, blocks, new BoundingBox3i(center.offset(-3, -3, -3), center.offset(3, 3, 3)));
        BlockPos local = hull.getPlot().getCenterBlock();
        helper.runAfterDelay(5, () -> {
            Vec3 blast = hull.logicalPose().transformPosition(local.getCenter()).add(-4.6, 0, 0);
            blast(level, blast, size, mine, missile);
            Set<BlockPos> actual = destroyed(level, local);
            Set<BlockPos> different = new HashSet<>(expected);
            different.removeAll(actual);
            Set<BlockPos> extra = new HashSet<>(actual);
            extra.removeAll(expected);
            helper.assertTrue(
                    different.isEmpty() && extra.isEmpty(),
                    size + " world=" + expected.size() + " hull=" + actual.size() + " missing=" + different.size()
                            + " extra=" + extra.size());
            for (BlockPos p : BlockPos.betweenClosed(local.offset(-3, -3, -3), local.offset(3, 3, 3))) {
                level.removeBlock(p, false);
            }
            helper.succeed();
        });
    }
}
