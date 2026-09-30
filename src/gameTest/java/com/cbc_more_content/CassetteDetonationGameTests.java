package com.cbc_more_content;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.effects.BombSympatheticDetonation;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class CassetteDetonationGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", batch = "cassette_chain_world", timeoutTicks = 100)
    public static void destroyedCassettesExplodeOncePerRemainingBomb(GameTestHelper helper) {
        cassetteMatrix(helper, false, Trigger.EXPLOSION);
    }

    @GameTest(template = "empty", batch = "cassette_chain_sable", timeoutTicks = 100)
    public static void destroyedSableCassettesKeepEveryBombAtWorldPosition(GameTestHelper helper) {
        cassetteMatrix(helper, true, Trigger.EXPLOSION);
    }

    @GameTest(template = "empty", batch = "cassette_impact_world", timeoutTicks = 100)
    public static void impactDetonatesEachBombWithoutReplayingStaleState(GameTestHelper helper) {
        cassetteMatrix(helper, false, Trigger.IMPACT);
    }

    @GameTest(template = "empty", batch = "cassette_impact_sable", timeoutTicks = 100)
    public static void sableImpactRetainsCassetteAfterLastPlotBlockDisappears(GameTestHelper helper) {
        cassetteMatrix(helper, true, Trigger.IMPACT);
    }

    @GameTest(template = "empty", batch = "cassette_fire", timeoutTicks = 100)
    public static void burningCassetteDetonatesEachBombInOrder(GameTestHelper helper) {
        cassetteMatrix(helper, false, Trigger.FIRE);
    }

    private static void cassetteMatrix(GameTestHelper helper, boolean assemble, Trigger trigger) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 70, 80));
        List<Cassette> cassettes = new ArrayList<>();
        for (int count = 1; count <= 4; count++) {
            var pos = base.east(count * 64);
            place(level, pos, count);
            Vec3 center = pos.getCenter();
            if (assemble) {
                var body = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
                pos = body.getPlot().getCenterBlock();
                center = body.logicalPose().transformPosition(pos.getCenter());
            }
            cassettes.add(new Cassette(pos, center, count));
        }
        List<Blast> blasts = new ArrayList<>();
        var recorder = record(level, blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        long start = level.getGameTime();
        try {
            for (var cassette : cassettes) {
                BlockState state = level.getBlockState(cassette.pos());
                switch (trigger) {
                    case EXPLOSION -> {
                        // An earlier fire fuze and repeated callbacks cannot add another cassette.
                        BombSympatheticDetonation.schedulePlacedBombCookoff(level, cassette.pos(), 35, 35);
                        destroy(level, List.of(cassette.pos()));
                        state.onBlockExploded(level, cassette.pos(), external(level, cassette.center()));
                    }
                    case IMPACT -> {
                        DropBombBlock.detonateInPlace(level, cassette.pos(), state);
                        DropBombBlock.detonateInPlace(level, cassette.pos(), state);
                    }
                    case FIRE -> BombSympatheticDetonation.schedulePlacedBombCookoff(level, cassette.pos(), 4, 4);
                }
            }
            helper.assertTrue(
                    blasts.size() == (trigger == Trigger.IMPACT ? 4 : 0),
                    "Impact must detonate the first charge immediately; remaining charges wait for their fuzes");
        } catch (RuntimeException error) {
            NeoForge.EVENT_BUS.unregister(recorder);
            throw error;
        }
        helper.runAfterDelay(70, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(blasts.size() == 10, "Cassettes 1+2+3+4 must give ten blasts: " + blasts);
            float singlePower = blasts.getFirst().power();
            helper.assertTrue(singlePower > 0, "Each bomb must have real blast power");
            for (var cassette : cassettes) {
                var sequence = at(blasts, cassette.center());
                assertSequence(helper, sequence, cassette.count());
                helper.assertTrue(
                        sequence.getFirst().tick() >= start + (trigger == Trigger.IMPACT ? 0 : 2),
                        "Deferred detonations must wait for a game tick");
                helper.assertTrue(
                        sequence.stream().allMatch(blast -> Math.abs(blast.power() - singlePower) < 0.001f),
                        "Every bomb keeps the power of one small bomb, without scaling or merging blasts");
                helper.assertFalse(
                        level.getBlockState(cassette.pos()).is(ModBlocks.SMALL_BOMB.get()),
                        "Consumed cassette must not remain in the world or plot");
                helper.assertTrue(
                        level.getEntitiesOfClass(
                                        ItemEntity.class, new AABB(cassette.center(), cassette.center()).inflate(4))
                                .isEmpty(),
                        "Consumed cassette cannot also drop live bombs");
            }
            for (int count = 1; count <= 4; count++) {
                level.removeBlock(base.east(count * 64).below(), false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "cassette_partial_release", timeoutTicks = 100)
    public static void alreadyReleasedBombDoesNotExplodeAgainWithCassette(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(40, 70, 40));
        place(level, pos, 4);
        // This test observes the released entity several ticks later. Leave a drop
        // shaft open; with bedrock directly underneath, an impact fuze correctly
        // consumes the projectile before the assertion can see it.
        level.removeBlock(pos.below(), false);
        level.setBlockAndUpdate(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.runAfterDelay(3, () -> {
            level.removeBlock(pos.east(), false);
            var remaining = level.getBlockState(pos);
            helper.assertTrue(
                    remaining.is(ModBlocks.SMALL_BOMB.get()) && remaining.getValue(DropBombBlock.CASSETTE) == 3,
                    "Redstone must have released exactly one bomb from the four-bomb cassette");
            var released = level.getEntitiesOfClass(DropBombProjectile.class, new AABB(pos).inflate(12));
            helper.assertTrue(released.size() == 1, "One real projectile must be released");
            released.forEach(DropBombProjectile::discard);
            List<Blast> blasts = new ArrayList<>();
            var recorder = record(level, blasts);
            NeoForge.EVENT_BUS.addListener(recorder);
            destroy(level, List.of(pos));
            helper.runAfterDelay(45, () -> {
                NeoForge.EVENT_BUS.unregister(recorder);
                assertSequence(helper, blasts, 3);
                level.removeBlock(pos.below(), false);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "cassette_backlog", timeoutTicks = 140)
    public static void queuedCassettesKeepTheirIntervalsAndDoNotLoseCharges(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 70, 80));
        List<BlockPos> cassettes = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            var pos = base.offset((i % 6) * 24, 0, (i / 6) * 24);
            place(level, pos, 4);
            cassettes.add(pos);
        }
        List<Blast> blasts = new ArrayList<>();
        var recorder = record(level, blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        destroy(level, cassettes);
        helper.runAfterDelay(80, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(blasts.size() == 96, "Twenty-four full cassettes must produce 96 explosions");
            Map<Long, Integer> perTick = new HashMap<>();
            blasts.forEach(blast -> perTick.merge(blast.tick(), 1, Integer::sum));
            helper.assertTrue(
                    perTick.values().stream().allMatch(count -> count <= 4),
                    "Cassette continuations must respect the four-reactions-per-tick limit: " + perTick);
            for (var pos : cassettes) {
                assertSequence(helper, at(blasts, pos.getCenter()), 4);
                level.removeBlock(pos.below(), false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "cassette_propagation", timeoutTicks = 100)
    public static void oneCassetteCanIgniteTheNextWithoutDuplicatingEither(GameTestHelper helper) {
        var level = helper.getLevel();
        var first = helper.absolutePos(new BlockPos(80, 70, 80));
        var next = first.east(2);
        place(level, first, 4);
        place(level, next, 3);
        List<Blast> blasts = new ArrayList<>();
        var recorder = record(level, blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        // Only the first block is initially destroyed. Its real blast must ignite the second.
        destroy(level, List.of(first));
        helper.assertTrue(level.getBlockState(next).is(ModBlocks.SMALL_BOMB.get()), "Second cassette starts intact");
        helper.runAfterDelay(65, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(
                    blasts.size() == 7, "A chain between four- and three-bomb cassettes must give seven blasts");
            assertSequence(helper, at(blasts, first.getCenter()), 4);
            assertSequence(helper, at(blasts, next.getCenter()), 3);
            helper.assertFalse(
                    level.getBlockState(next).is(ModBlocks.SMALL_BOMB.get()), "Chain must reach next cassette");
            level.removeBlock(first.below(), false);
            level.removeBlock(next.below(), false);
            helper.succeed();
        });
    }

    private static void assertSequence(GameTestHelper helper, List<Blast> blasts, int count) {
        helper.assertTrue(blasts.size() == count, "Expected " + count + " separate explosions, got " + blasts);
        for (int i = 1; i < blasts.size(); i++) {
            helper.assertTrue(
                    blasts.get(i).tick() - blasts.get(i - 1).tick() >= 8,
                    "Each bomb in one cassette must leave a distinct pause after the previous bomb: " + blasts);
        }
    }

    private static List<Blast> at(List<Blast> blasts, Vec3 center) {
        return blasts.stream()
                .filter(blast -> blast.center().distanceToSqr(center) < 0.01)
                .toList();
    }

    private static Consumer<ExplosionEvent.Detonate> record(ServerLevel level, List<Blast> blasts) {
        return event -> {
            if (event.getLevel() == level && event.getExplosion() instanceof WarnauticsExplosion explosion) {
                blasts.add(new Blast(explosion.center(), level.getGameTime(), explosion.radius()));
            }
        };
    }

    private static void place(ServerLevel level, BlockPos pos, int count) {
        level.setBlock(pos.below(), Blocks.BEDROCK.defaultBlockState(), FLAGS);
        level.setBlock(
                pos, ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.CASSETTE, count), FLAGS);
    }

    private static void destroy(ServerLevel level, List<BlockPos> positions) {
        var explosion = external(level, positions.getFirst().getCenter());
        explosion.getToBlow().addAll(positions);
        explosion.finalizeExplosion(false);
    }

    private static Explosion external(ServerLevel level, Vec3 center) {
        return new Explosion(level, null, center.x, center.y, center.z, 1, false, Explosion.BlockInteraction.DESTROY);
    }

    private enum Trigger {
        EXPLOSION,
        IMPACT,
        FIRE
    }

    private record Cassette(BlockPos pos, Vec3 center, int count) {}

    private record Blast(Vec3 center, long tick, float power) {}
}
