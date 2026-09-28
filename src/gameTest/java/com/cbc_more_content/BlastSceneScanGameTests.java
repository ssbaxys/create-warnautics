package com.cbc_more_content;

import com.cbc_more_content.effects.BlastScene;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class BlastSceneScanGameTests {
    @GameTest(template = "empty", batch = "blast_local_scan", timeoutTicks = 100)
    public static void rotatedHullScanOnlyVisitsNearbySections(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(12, 92, 12));
        var blocks = new ArrayList<BlockPos>();
        for (int i = 0; i < 129; i++) {
            BlockPos at = origin.east(i);
            level.setBlock(at, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(at);
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, origin, blocks, new BoundingBox3i(origin, origin.east(128)));
        helper.assertFalse(ship.isRemoved(), "Assembled scan fixture must be alive");
        // This test exercises the geometric query synchronously; native motion is covered by the siren/cover tests.
        ship.logicalPose().orientation().set(new Quaterniond().rotateY(Math.PI / 4));
        BlockPos local = ship.getPlot().getCenterBlock();
        var center = ship.logicalPose().transformPosition(local.east(2).getCenter());
        Set<BlockPos> expected = new HashSet<>();
        for (int i = 0; i < 129; i++) {
            BlockPos at = local.east(i);
            if (ship.logicalPose().transformPosition(at.getCenter()).distanceToSqr(center) <= 9) {
                expected.add(at);
            }
        }
        int[] visits = {0};
        var found = new BlastScene(level, center, 3).matching(center, 3, state -> {
            visits[0]++;
            return state.is(Blocks.GLASS);
        });
        Set<BlockPos> actual = new HashSet<>();
        found.forEach(block -> actual.add(block.pos()));
        helper.assertTrue(
                actual.equals(expected), "Clipping must preserve exact world-radius results on rotated hulls");
        helper.assertTrue(visits[0] < 64, "Small blast scanned distant hull sections: " + visits[0]);
        for (int i = 128; i >= 0; i--) {
            level.removeBlock(local.east(i), false);
        }
        helper.succeed();
    }
}
