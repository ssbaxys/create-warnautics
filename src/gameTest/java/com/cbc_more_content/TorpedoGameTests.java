package com.cbc_more_content;

import com.cbc_more_content.munitions.SeaBombProjectile;
import com.cbc_more_content.registry.ModEntityTypes;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class TorpedoGameTests {
    private static final TicketType<Long> FIXTURE =
            TicketType.create("warnautics_torpedo_fixture", Long::compareTo, 100);
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static void water(ServerLevel level, BlockPos min, BlockPos max) {
        for (var pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), FLAGS);
        }
    }

    private static void clear(ServerLevel level, BlockPos min, BlockPos max) {
        for (var pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    private static SeaBombProjectile torpedo(ServerLevel level, Vec3 at) {
        var torpedo = ModEntityTypes.SEA_BOMB.get().create(level);
        torpedo.setPos(at);
        torpedo.setDeltaMovement(.4, 0, 0);
        torpedo.setOrientation(new Vec3(1, 0, 0));
        // Reproduce the old stale-water latch using the real vanilla fluid update.
        torpedo.baseTick();
        return torpedo;
    }

    private static CompoundTag saved(SeaBombProjectile torpedo) {
        var tag = new CompoundTag();
        torpedo.saveWithoutId(tag);
        return tag;
    }

    @GameTest(template = "empty", batch = "torpedo_air_arc", timeoutTicks = 100)
    public static void leavesWaterWithMomentumAndFallsOnArc(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(40, 90, 40));
        water(level, base, base.offset(3, 2, 2));
        var torpedo = torpedo(level, base.offset(1, 1, 1).getCenter());
        helper.assertTrue(torpedo.isInWater(), "Fixture reproduces cached underwater state");
        torpedo.tick();
        helper.assertTrue(torpedo.phase() == SeaBombProjectile.PHASE_SWIM, "Submerged launch starts propeller");
        for (int i = 0; i < 8 && torpedo.phase() == SeaBombProjectile.PHASE_SWIM; i++) {
            torpedo.tick();
        }
        helper.assertTrue(torpedo.phase() == SeaBombProjectile.PHASE_AIR, "Leaving actual water disables swimming");
        helper.assertTrue(torpedo.getDeltaMovement().x > .9, "Exit retains forward inertia");
        double startY = torpedo.getY();
        double vy = torpedo.getDeltaMovement().y;
        for (int i = 0; i < 6; i++) {
            level.getChunkAt(torpedo.blockPosition().east(2));
            torpedo.tick();
            helper.assertTrue(torpedo.getDeltaMovement().y < vy, "Gravity steepens the falling arc every air tick");
            vy = torpedo.getDeltaMovement().y;
        }
        helper.assertTrue(torpedo.getY() < startY - .8, "Torpedo falls rather than flying straight");
        helper.assertTrue(torpedo.getOrientation().y < 0, "Model follows the falling trajectory");
        torpedo.discard();
        clear(level, base, base.offset(3, 2, 2));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "torpedo_reentry", timeoutTicks = 100)
    public static void fallsIntoLowerWaterAndResumesWithoutRefuelling(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(40, 90, 40));
        water(level, base.offset(0, 12, 0), base.offset(3, 14, 2));
        water(level, base.offset(6, 0, 0), base.offset(38, 9, 2));
        var torpedo = torpedo(level, base.offset(1, 13, 1).getCenter());
        torpedo.tick();
        for (int i = 0; i < 8 && torpedo.phase() == SeaBombProjectile.PHASE_SWIM; i++) {
            torpedo.tick();
        }
        var tag = saved(torpedo);
        double spent = tag.getDouble("SwimDistance");
        helper.assertTrue(spent > 0 && torpedo.phase() == SeaBombProjectile.PHASE_AIR, "Has swum and left upper pool");
        torpedo.discard();
        torpedo = ModEntityTypes.SEA_BOMB.get().create(level);
        torpedo.load(tag);
        helper.assertTrue(saved(torpedo).getDouble("SwimDistance") == spent, "Airborne save keeps spent range");
        for (int i = 0; i < 35 && torpedo.phase() == SeaBombProjectile.PHASE_AIR; i++) {
            level.getChunkAt(torpedo.blockPosition().east(2));
            torpedo.tick();
        }
        helper.assertTrue(torpedo.phase() == SeaBombProjectile.PHASE_SWIM, "Ballistic fall enters the lower pool");
        helper.assertTrue(torpedo.getY() < base.getY() + 10.3, "Re-entry occurs at the lower water surface");
        helper.assertTrue(saved(torpedo).getDouble("SwimDistance") >= spent, "Water entry does not reset fuel");
        var before = torpedo.position();
        torpedo.tick();
        helper.assertTrue(torpedo.getX() > before.x + 1, "Propeller resumes forward swimming");
        helper.assertTrue(Math.abs(torpedo.getDeltaMovement().y) < 1e-8, "Water stops the downward arc");
        helper.assertTrue(saved(torpedo).getDouble("SwimDistance") > spent, "Range continues counting");
        torpedo.discard();
        clear(level, base, base.offset(38, 14, 2));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "torpedo_fuel_save", timeoutTicks = 100)
    public static void swimHeadingAndExhaustedEngineSurviveReload(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(40, 90, 40));
        water(level, base, base.offset(18, 3, 2));
        var torpedo = torpedo(level, base.offset(1, 2, 1).getCenter());
        torpedo.tick();
        var tag = saved(torpedo);
        tag.putDouble("SwimDistance", 798.5);
        torpedo.discard();
        torpedo = ModEntityTypes.SEA_BOMB.get().create(level);
        torpedo.load(tag);
        helper.assertTrue(torpedo.phase() == SeaBombProjectile.PHASE_SWIM, "Save retains swim phase");
        torpedo.tick();
        helper.assertTrue(
                torpedo.getDeltaMovement().x > 1 && Math.abs(torpedo.getDeltaMovement().z) < 1e-8,
                "Reload preserves heading, not default south");
        torpedo.tick();
        helper.assertTrue(torpedo.phase() == SeaBombProjectile.PHASE_SINK, "Original 800-block range exhausts engine");
        torpedo.setPos(base.offset(2, 8, 1).getCenter());
        torpedo.tick();
        helper.assertTrue(torpedo.phase() == SeaBombProjectile.PHASE_AIR, "Spent torpedo can fall through air");
        torpedo.setPos(base.offset(2, 2, 1).getCenter());
        torpedo.tick();
        helper.assertTrue(
                torpedo.phase() == SeaBombProjectile.PHASE_SINK, "Re-entry cannot restart an exhausted motor");
        torpedo.discard();
        clear(level, base, base.offset(18, 3, 2));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "torpedo_water_height", timeoutTicks = 100)
    public static void waterBelowDoesNotHoldTorpedoInAir(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(40, 90, 40));
        water(level, base, base.offset(14, 0, 2));
        var torpedo = torpedo(level, base.offset(1, 1, 1).getCenter());
        torpedo.tick();
        helper.assertTrue(
                torpedo.phase() == SeaBombProjectile.PHASE_AIR, "Water one block below the hull is not contact");
        helper.assertTrue(torpedo.getDeltaMovement().y < 0, "Above-surface projectile falls toward the water");
        torpedo.discard();
        clear(level, base, base.offset(14, 0, 2));
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "torpedo_collision_world", timeoutTicks = 100)
    public static void underwaterImpactDamagesWorldAtIncreasedPower(GameTestHelper helper) {
        impact(helper, false);
    }

    @GameTest(template = "empty", batch = "torpedo_collision_sable", timeoutTicks = 100)
    public static void underwaterImpactDamagesSableHullAtIncreasedPower(GameTestHelper helper) {
        impact(helper, true);
    }

    private static void impact(GameTestHelper helper, boolean sable) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(50, 90, 50));
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(at), 3, at.asLong());
        var cells = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(at.offset(0, -1, -1), at.offset(0, 1, 1))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), FLAGS);
            cells.add(p.immutable());
        }
        var ship = sable
                ? SubLevelAssemblyHelper.assembleBlocks(
                        level, at, cells, new BoundingBox3i(at.offset(0, -1, -1), at.offset(0, 1, 1)))
                : null;
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(ship == null || !ship.isRemoved(), "Collision fixture must remain loaded");
            var center = ship == null
                    ? at.getCenter()
                    : ship.logicalPose()
                            .transformPosition(ship.getPlot().getCenterBlock().getCenter());
            var wet = BlockPos.containing(center);
            water(level, wet.offset(-5, -2, -2), wet.offset(-1, 2, 2));
            List<net.minecraft.world.level.Explosion> blasts = new ArrayList<>();
            var torpedo = torpedo(level, center.add(-3.5, 0, 0));
            Consumer<ExplosionEvent.Start> listener = event -> {
                if (event.getExplosion().getDirectSourceEntity() == torpedo) {
                    blasts.add(event.getExplosion());
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                for (int i = 0; i < 8 && !torpedo.isRemoved(); i++) {
                    torpedo.tick();
                }
                helper.assertTrue(
                        blasts.size() == 1,
                        "Hull impact produces exactly one real explosion; count=" + blasts.size() + " phase="
                                + torpedo.phase() + " pos=" + torpedo.position() + " target=" + center);
                helper.assertTrue(
                        blasts.getFirst().radius() >= 6.374,
                        "Underwater block power uses new 7.5 base with 0.85 contact scale");
                var local = ship == null ? at : ship.getPlot().getCenterBlock();
                int broken = 0;
                for (var cell : cells) {
                    if (!level.getBlockState(local.offset(cell.subtract(at))).is(Blocks.OAK_PLANKS)) {
                        broken++;
                    }
                }
                helper.assertTrue(broken > 0, "Explosion must break the struck hull, including a Sable hull");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
                torpedo.discard();
                var local = ship == null ? at : ship.getPlot().getCenterBlock();
                clear(level, local.offset(0, -1, -1), local.offset(0, 1, 1));
                clear(level, wet.offset(-5, -2, -2), wet.offset(-1, 2, 2));
            }
            helper.succeed();
        });
    }
}
