package com.cbc_more_content;

import com.cbc_more_content.block.ChainExplosiveBlock;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombSympatheticDetonation;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelExplosion;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class ChainDetonationGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", batch = "chain_all_world", timeoutTicks = 100)
    public static void everyExplosiveBlockChainsWithLegacySwitchesOff(GameTestHelper helper) {
        everyExplosive(helper, false);
    }

    @GameTest(template = "empty", batch = "chain_all_sable", timeoutTicks = 100)
    public static void everyExplosiveKeepsWorldPositionWhenSableBodyIsRemoved(GameTestHelper helper) {
        everyExplosive(helper, true);
    }

    private static void everyExplosive(GameTestHelper helper, boolean assemble) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 80, 80));
        boolean friendly = WarnauticsConfig.FRIENDLY_CHAIN_DETONATION.get();
        boolean external = WarnauticsConfig.EXTERNAL_CHAIN_DETONATION.get();
        WarnauticsConfig.FRIENDLY_CHAIN_DETONATION.set(false);
        WarnauticsConfig.EXTERNAL_CHAIN_DETONATION.set(false);
        List<Vec3> centers = new ArrayList<>();
        List<BlockPos> parts = new ArrayList<>();
        List<BlockPos> supports = new ArrayList<>();
        var blasts = new ArrayList<Vec3>();
        Consumer<ExplosionEvent.Detonate> recorder = record(blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        List<Block> explosives = List.of(
                ModBlocks.SMALL_BOMB.get(),
                ModBlocks.SEA_BOMB.get(),
                ModBlocks.MEDIUM_BOMB.get(),
                ModBlocks.LARGE_BOMB.get(),
                ModBlocks.MOAB.get(),
                ModBlocks.SMALL_MINE.get(),
                ModBlocks.BOUNDING_MINE.get(),
                ModBlocks.LARGE_MINE.get(),
                ModBlocks.SEA_MINE.get(),
                ModBlocks.C4.get(),
                ModBlocks.CRUISE_MISSILE.get());
        try {
            for (int i = 0; i < explosives.size(); i++) {
                Block block = explosives.get(i);
                helper.assertTrue(
                        block instanceof ChainExplosiveBlock,
                        "Explosive registry entry lacks chain reaction: " + block);
                var at = base.offset(i * 128, 0, 0);
                var cells = place(level, at, block);
                supports.add(at.below());
                Vec3 center = at.getCenter();
                if (assemble) {
                    var body = SubLevelAssemblyHelper.assembleBlocks(
                            level, at, cells, new BoundingBox3i(at.west(), at.east()));
                    var local = body.getPlot().getCenterBlock();
                    parts.addAll(cells.stream()
                            .map(cell -> local.offset(cell.subtract(at)))
                            .toList());
                    center = body.logicalPose().transformPosition(local.getCenter());
                } else {
                    parts.addAll(cells);
                }
                centers.add(center);
            }
            var trigger = external(level, base.getCenter(), 1);
            trigger.getToBlow().addAll(parts);
            trigger.finalizeExplosion(false);
            helper.assertTrue(blasts.isEmpty(), "A chain reaction must wait for a later game tick");
            for (var cell : parts) {
                helper.assertFalse(
                        level.getBlockState(cell).getBlock() instanceof ChainExplosiveBlock,
                        "Charge must be consumed once");
            }
        } catch (RuntimeException error) {
            NeoForge.EVENT_BUS.unregister(recorder);
            WarnauticsConfig.FRIENDLY_CHAIN_DETONATION.set(friendly);
            WarnauticsConfig.EXTERNAL_CHAIN_DETONATION.set(external);
            throw error;
        }
        helper.runAfterDelay(18, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            WarnauticsConfig.FRIENDLY_CHAIN_DETONATION.set(friendly);
            WarnauticsConfig.EXTERNAL_CHAIN_DETONATION.set(external);
            helper.assertTrue(blasts.size() == explosives.size(), "Expected one explosion per charge, got " + blasts);
            for (Vec3 center : centers) {
                long count = blasts.stream()
                        .filter(at -> at.distanceToSqr(center) < .01)
                        .count();
                helper.assertTrue(count == 1, "Missing/duplicate warhead at world center " + center + ": " + blasts);
                helper.assertTrue(
                        level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(3))
                                .isEmpty(),
                        "An exploded charge must not also drop an explosive item");
            }
            supports.forEach(p -> level.removeBlock(p, false));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_propagation", timeoutTicks = 120)
    public static void mixedChainReachesChargesOutsideFirstExplosion(GameTestHelper helper) {
        var level = helper.getLevel();
        var start = helper.absolutePos(new BlockPos(80, 50, 80));
        List<BlockPos> charges = new ArrayList<>();
        var blocks = new Block[] {
            ModBlocks.SMALL_BOMB.get(),
            ModBlocks.SEA_BOMB.get(),
            ModBlocks.C4.get(),
            ModBlocks.LARGE_MINE.get(),
            ModBlocks.SMALL_BOMB.get(),
            ModBlocks.C4.get(),
            ModBlocks.SMALL_BOMB.get()
        };
        for (int i = 0; i < blocks.length; i++) {
            var pos = start.east(i * 3);
            place(level, pos, blocks[i]);
            charges.add(pos);
        }
        var blasts = new ArrayList<Vec3>();
        List<String> diagnostics = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> recorder = event -> {
            if (event.getExplosion() instanceof WarnauticsExplosion blast) {
                blasts.add(blast.center());
                diagnostics.add("center=" + blast.center() + ", power=" + blast.radius() + ", terrain="
                        + blast.canDamageTerrain() + ", affected=" + blast.getToBlow());
            }
        };
        NeoForge.EVENT_BUS.addListener(recorder);
        var first = external(level, start.getCenter(), 2);
        first.explode();
        helper.assertFalse(
                first.getToBlow().contains(charges.getLast()), "Last charge must be beyond the initiating blast");
        first.finalizeExplosion(false);
        helper.runAfterDelay(60, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(
                    blasts.size() == charges.size(),
                    "The whole mixed chain must detonate once: " + diagnostics + "; remaining="
                            + charges.stream()
                                    .map(p -> level.getBlockState(p).toString())
                                    .toList());
            for (var pos : charges) {
                helper.assertFalse(
                        level.getBlockState(pos).getBlock() instanceof ChainExplosiveBlock, "Chain stopped at " + pos);
                level.removeBlock(pos.below(), false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_airframes", timeoutTicks = 100)
    public static void anyMoabOrMissilePartConsumesOneWarhead(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 80, 80));
        List<BlockPos> all = new ArrayList<>();
        List<BlockPos> hit = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            var pos = base.east(128 * i);
            all.addAll(place(level, pos, i < 3 ? ModBlocks.MOAB.get() : ModBlocks.CRUISE_MISSILE.get()));
            hit.add(pos.east(i % 3 - 1));
        }
        var blasts = new ArrayList<Vec3>();
        var recorder = record(blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        var trigger = external(level, base.getCenter(), 1);
        trigger.getToBlow().addAll(hit);
        trigger.finalizeExplosion(false);
        helper.runAfterDelay(15, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(
                    blasts.size() == 6, "A nose, body or tail hit must cause exactly one explosion per airframe");
            for (var pos : all) {
                helper.assertFalse(
                        level.getBlockState(pos).getBlock() instanceof ChainExplosiveBlock,
                        "Airframe fragment remained");
                level.removeBlock(pos.below(), false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_protection", timeoutTicks = 100)
    public static void protectedAndUnhitChargesDoNotChain(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(30, 30, 30));
        place(level, pos, ModBlocks.SMALL_BOMB.get());
        place(level, pos.east(3), ModBlocks.C4.get());
        var blasts = new ArrayList<Vec3>();
        var recorder = record(blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        var trigger = new WarnauticsExplosion(
                level,
                null,
                BombDamageSource.create(level),
                pos.getCenter(),
                11,
                14,
                com.cbc_more_content.bomb.BombSize.BlastVolume.SPHERE);
        trigger.setCanDamageTerrain(true);
        Consumer<ExplosionEvent.Detonate> veto = event -> {
            if (event.getExplosion() == trigger) {
                event.getAffectedBlocks().clear();
            }
        };
        NeoForge.EVENT_BUS.addListener(veto);
        try {
            trigger.explode();
            trigger.finalizeExplosion(false);
        } finally {
            NeoForge.EVENT_BUS.unregister(veto);
        }
        // Nearby, but not actually destroyed: old radius scanning must not ignite it.
        var external = external(level, pos.getCenter(), 4);
        NeoForge.EVENT_BUS.post(new ExplosionEvent.Detonate(level, external, new ArrayList<>()));
        external.finalizeExplosion(false);
        helper.runAfterDelay(12, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(blasts.size() == 1, "Only the initiating blast should have occurred");
            helper.assertTrue(level.getBlockState(pos).is(ModBlocks.SMALL_BOMB.get()), "Protected bomb must remain");
            helper.assertTrue(level.getBlockState(pos.east(3)).is(ModBlocks.C4.get()), "Protected C4 must remain");
            level.removeBlock(pos, false);
            level.removeBlock(pos.east(3), false);
            level.removeBlock(pos.below(), false);
            level.removeBlock(pos.east(3).below(), false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_fuze", timeoutTicks = 100)
    public static void oldCookoffCannotDuplicateDestroyedBomb(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(30, 30, 30));
        place(level, pos, ModBlocks.SMALL_BOMB.get());
        var blasts = new ArrayList<Vec3>();
        var recorder = record(blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        BombSympatheticDetonation.schedulePlacedBombCookoff(level, pos, 20, 20);
        var trigger = external(level, pos.getCenter(), 1);
        trigger.getToBlow().add(pos);
        trigger.finalizeExplosion(false);
        helper.runAfterDelay(30, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(blasts.size() == 1, "Explosion plus pending fire fuze must not duplicate the same bomb");
            level.removeBlock(pos.below(), false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_cancel_fuze", timeoutTicks = 100)
    public static void removingBombCancelsItsOldFuze(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(30, 30, 30));
        place(level, pos, ModBlocks.SMALL_BOMB.get());
        var blasts = new ArrayList<Vec3>();
        var recorder = record(blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        BombSympatheticDetonation.schedulePlacedBombCookoff(level, pos, 10, 10);
        level.removeBlock(pos, false);
        place(level, pos, ModBlocks.SMALL_BOMB.get());
        helper.runAfterDelay(15, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(blasts.isEmpty(), "A removed bomb cannot explode later or ignite its replacement");
            helper.assertTrue(
                    level.getBlockState(pos).is(ModBlocks.SMALL_BOMB.get()), "Replacement must remain intact");
            level.removeBlock(pos, false);
            level.removeBlock(pos.below(), false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_bounded_queue", timeoutTicks = 100)
    public static void rackReactionsAreSpreadOverTicksWithoutLoss(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 60, 80));
        Map<Long, Integer> perTick = new HashMap<>();
        List<BlockPos> charges = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            var pos = base.offset((i % 6) * 24, 0, (i / 6) * 24);
            place(level, pos, ModBlocks.SMALL_BOMB.get());
            charges.add(pos);
        }
        Consumer<ExplosionEvent.Detonate> recorder = event -> {
            if (event.getExplosion() instanceof WarnauticsExplosion) {
                perTick.merge(level.getGameTime(), 1, Integer::sum);
            }
        };
        NeoForge.EVENT_BUS.addListener(recorder);
        long start = level.getGameTime();
        var trigger = external(level, base.getCenter(), 1);
        trigger.getToBlow().addAll(charges);
        trigger.finalizeExplosion(false);
        helper.assertTrue(perTick.isEmpty(), "Callbacks must not recursively detonate the rack");
        helper.runAfterDelay(20, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(
                    perTick.values().stream().mapToInt(Integer::intValue).sum() == 24, "Queue must not lose charges");
            helper.assertTrue(
                    perTick.values().stream().allMatch(count -> count <= 4),
                    "At most four reactions per tick: " + perTick);
            helper.assertTrue(
                    perTick.keySet().stream().allMatch(tick -> tick >= start + 2),
                    "Fuzes use game ticks, not an immediate task queue");
            charges.forEach(pos -> level.removeBlock(pos.below(), false));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "chain_floor_order", timeoutTicks = 100)
    public static void mineStillDetonatesWhenItsSupportExplodesFirst(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(30, 30, 30));
        place(level, pos, ModBlocks.LARGE_MINE.get());
        level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), FLAGS);
        var blasts = new ArrayList<Vec3>();
        var recorder = record(blasts);
        NeoForge.EVENT_BUS.addListener(recorder);
        var trigger = external(level, pos.getCenter(), 2);
        level.getBlockState(pos.below()).onExplosionHit(level, pos.below(), trigger, (stack, p) -> {});
        level.getBlockState(pos).onExplosionHit(level, pos, trigger, (stack, p) -> {});
        helper.runAfterDelay(10, () -> {
            NeoForge.EVENT_BUS.unregister(recorder);
            helper.assertTrue(
                    blasts.size() == 1, "Destroying the floor first must not erase the mine's explosion callback");
            helper.succeed();
        });
    }

    private static Consumer<ExplosionEvent.Detonate> record(List<Vec3> blasts) {
        return event -> {
            if (event.getExplosion() instanceof WarnauticsExplosion
                    || event.getExplosion() instanceof ShrapnelExplosion) {
                blasts.add(event.getExplosion().center());
            }
        };
    }

    private static Explosion external(ServerLevel level, Vec3 at, float power) {
        return new Explosion(level, null, at.x, at.y, at.z, power, false, Explosion.BlockInteraction.DESTROY);
    }

    private static List<BlockPos> place(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos.below(), Blocks.BEDROCK.defaultBlockState(), FLAGS);
        BlockState state = block.defaultBlockState();
        if (block == ModBlocks.SEA_MINE.get()) {
            state = state.setValue(com.cbc_more_content.block.SeaMineBlock.WATERLOGGED, true);
        }
        if (block instanceof MoabBlock) {
            state = state.setValue(DropBombBlock.FACING, Direction.EAST);
            level.setBlock(pos, state.setValue(MoabBlock.PART, MoabBlock.Part.BODY), FLAGS);
            level.setBlock(pos.east(), state.setValue(MoabBlock.PART, MoabBlock.Part.NOSE), FLAGS);
            level.setBlock(pos.west(), state.setValue(MoabBlock.PART, MoabBlock.Part.TAIL), FLAGS);
            return List.of(pos, pos.east(), pos.west());
        }
        if (block instanceof CruiseMissileBlock) {
            state = state.setValue(CruiseMissileBlock.FACING, Direction.EAST);
            level.setBlock(pos, state.setValue(CruiseMissileBlock.PART, CruiseMissileBlock.Part.BODY), FLAGS);
            level.setBlock(pos.east(), state.setValue(CruiseMissileBlock.PART, CruiseMissileBlock.Part.NOSE), FLAGS);
            level.setBlock(pos.west(), state.setValue(CruiseMissileBlock.PART, CruiseMissileBlock.Part.TAIL), FLAGS);
            return List.of(pos, pos.east(), pos.west());
        }
        level.setBlock(pos, state, FLAGS);
        return List.of(pos);
    }
}
