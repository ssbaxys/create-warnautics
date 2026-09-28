package com.cbc_more_content;

import com.cbc_more_content.effects.BlastFxScheduler;
import com.cbc_more_content.effects.BlastScene;
import com.cbc_more_content.entity.BlastDebrisEntity;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class DebrisFxGameTests {
    private static BlastDebrisEntity falling(GameTestHelper helper) {
        var base = helper.absolutePos(new BlockPos(4, 2, 4));
        for (var pos : BlockPos.betweenClosed(base.offset(-2, 0, -2), base.offset(2, 0, 2))) {
            helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        var piece = BlastDebrisEntity.create(
                helper.getLevel(),
                Blocks.STONE.defaultBlockState(),
                base.getCenter().add(0, 2, 0),
                new Vec3(0, -.65, 0));
        helper.getLevel().addFreshEntity(piece);
        return piece;
    }

    @GameTest(template = "empty", batch = "debris_physics", timeoutTicks = 30)
    public static void impactActuallyRebounds(GameTestHelper helper) {
        var piece = falling(helper);
        helper.succeedWhen(() -> {
            helper.assertTrue(piece.getDeltaMovement().y > .08, "Saved incoming velocity must produce a real rebound");
            piece.discard();
        });
    }

    @GameTest(template = "empty", batch = "debris_physics", timeoutTicks = 170)
    public static void sleepsMeltsAndDisappears(GameTestHelper helper) {
        var piece = falling(helper);
        boolean[] slept = {false}, melted = {false};
        helper.onEachTick(() -> {
            if (piece.isSettled()) {
                slept[0] = true;
                helper.assertTrue(piece.getDeltaMovement().lengthSqr() < .000001, "Sleeping debris must stop moving");
            }
            if (piece.meltProgress(0) > .15 && piece.meltProgress(0) < .95) {
                melted[0] = true;
            }
            if (piece.isRemoved()) {
                helper.assertTrue(
                        slept[0] && melted[0], "Debris must visibly melt after resting, not pop out in flight");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", batch = "debris_physics", timeoutTicks = 30)
    public static void wallCollisionReflectsWithoutTunnelling(GameTestHelper helper) {
        var origin = helper.absolutePos(new BlockPos(4, 3, 4));
        for (var p : BlockPos.betweenClosed(origin.offset(2, -1, -2), origin.offset(2, 3, 2))) {
            helper.getLevel().setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        var piece = BlastDebrisEntity.create(
                helper.getLevel(), Blocks.IRON_BLOCK.defaultBlockState(), origin.getCenter(), new Vec3(1.4, 0, 0));
        helper.getLevel().addFreshEntity(piece);
        helper.succeedWhen(() -> {
            helper.assertTrue(piece.getDeltaMovement().x < -.1, "Wall must reflect horizontal momentum");
            helper.assertTrue(piece.getX() < origin.getX() + 2, "Fragment must stay on the near side of the wall");
            piece.discard();
        });
    }

    @GameTest(template = "empty", batch = "debris_water", timeoutTicks = 40)
    public static void waterDampsFragments(GameTestHelper helper) {
        var origin = helper.absolutePos(new BlockPos(4, 5, 4));
        for (var p : BlockPos.betweenClosed(origin.offset(-2, -2, -2), origin.offset(4, 2, 2))) {
            helper.getLevel().setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        var wet = BlastDebrisEntity.create(
                helper.getLevel(), Blocks.STONE.defaultBlockState(), origin.getCenter(), new Vec3(.6, 0, 0));
        // Stay clear of GameTest's elevated beacon/reporting blocks above the structure.
        var dryOrigin = origin.above(40);
        for (var p : BlockPos.betweenClosed(dryOrigin.offset(-2, -2, -2), dryOrigin.offset(6, 2, 2))) {
            helper.getLevel().setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        var dry = BlastDebrisEntity.create(
                helper.getLevel(), Blocks.STONE.defaultBlockState(), dryOrigin.getCenter(), new Vec3(.6, 0, 0));
        helper.getLevel().addFreshEntity(wet);
        helper.getLevel().addFreshEntity(dry);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(
                    wet.getDeltaMovement().x < dry.getDeltaMovement().x * .7,
                    "Water must absorb momentum: wet=" + wet.getDeltaMovement() + " dry=" + dry.getDeltaMovement()
                            + " inWater=" + wet.isInWater() + " ages=" + wet.tickCount + "," + dry.tickCount);
            wet.discard();
            dry.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "debris_sable", timeoutTicks = 70)
    public static void fragmentsCollideWithSableDeck(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = helper.absolutePos(new BlockPos(8, 8, 8));
        var blocks = new java.util.ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(center.offset(-3, 0, -3), center.offset(3, 0, 3))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(p.below(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            blocks.add(p.immutable());
        }
        var ship = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                level,
                center,
                blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(center.offset(-3, 0, -3), center.offset(3, 0, 3)));
        helper.runAfterDelay(8, () -> {
            var local = ship.getPlot().getCenterBlock();
            var at = ship.logicalPose().transformPosition(local.getCenter());
            var piece = BlastDebrisEntity.create(
                    level, Blocks.STONE.defaultBlockState(), at.add(0, 1.8, 0), new Vec3(0, -.65, 0));
            level.addFreshEntity(piece);
            helper.succeedWhen(() -> {
                helper.assertTrue(piece.getDeltaMovement().y > .08, "Fragment must rebound off a physical deck");
                var deck = ship.logicalPose().transformPosition(local.getCenter());
                helper.assertTrue(
                        piece.getY() > deck.y + .3,
                        "Rebound must happen above the deck, not through it on the ground below");
                piece.discard();
                for (var p : BlockPos.betweenClosed(local.offset(-3, 0, -3), local.offset(3, 0, 3))) {
                    level.removeBlock(p, false);
                }
            });
        });
    }

    @GameTest(template = "empty", batch = "scar_surface_scan", timeoutTicks = 80)
    public static void earlySurfaceFilterPreservesExposedBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = helper.absolutePos(new BlockPos(8, 8, 8));
        for (var p : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 0, 3))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Set<BlockPos> removed = Set.of(center, center.below());
        var scene = new BlastScene(level, center.getCenter(), 8);
        Set<BlockPos> expected = new HashSet<>();
        for (var block : scene.matching(center.getCenter(), 8, state -> state.is(Blocks.DIRT))) {
            var above = block.pos().above();
            if (removed.contains(above)
                    || level.getBlockState(above)
                            .getCollisionShape(level, above)
                            .isEmpty()) {
                expected.add(block.pos());
            }
        }
        var fast = scene.matching(center.getCenter(), 8, state -> state.is(Blocks.DIRT), removed);
        Set<BlockPos> actual = new HashSet<>();
        fast.forEach(block -> actual.add(block.pos()));
        helper.assertTrue(actual.equals(expected), "Early pruning must preserve the original surface selection");
        helper.assertTrue(actual.contains(center.below(2)), "Crater removal exposes buried soil");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fx_scheduling", timeoutTicks = 40)
    public static void cosmeticQueueHonoursDelayAndBudget(GameTestHelper helper) {
        int[] count = {0};
        long[] lastTick = {-1};
        int[] perTick = {0};
        long start = helper.getLevel().getGameTime();
        for (int i = 0; i < 16; i++) {
            BlastFxScheduler.schedule(helper.getLevel(), 5, () -> {
                long now = helper.getLevel().getGameTime();
                helper.assertTrue(now >= start + 5, "Cosmetic work ran before its due tick");
                if (lastTick[0] != now) {
                    lastTick[0] = now;
                    perTick[0] = 0;
                }
                helper.assertTrue(++perTick[0] <= 6, "A burst exceeded the per-tick cosmetic budget");
                count[0]++;
            });
        }
        helper.runAfterDelay(4, () -> helper.assertTrue(count[0] == 0, "Delay must be real"));
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(count[0] == 16, "Normal bursts must complete without losing effects");
            helper.succeed();
        });
    }
}
