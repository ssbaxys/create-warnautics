package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModEntityTypes;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("warnautics_aim9")
@PrefixGameTestTemplate(false)
public class AirborneBlastGameTests {
    private static CruiseMissileProjectile cruise(GameTestHelper h, Vec3 at) {
        var missile = ModEntityTypes.CRUISE_MISSILE.get().create(h.getLevel());
        missile.setPos(at);
        missile.launch(new Vec3(0, 0, 1));
        missile.tickCount = 12;
        h.getLevel().addFreshEntity(missile);
        return missile;
    }

    @GameTest(template = "empty", batch = "airborne_weak_pressure", timeoutTicks = 100)
    public static void peripheralBombBlastDeflectsRoundsWithoutIgnitingASalvo(GameTestHelper h) {
        var origin = h.absolutePos(new BlockPos(50, 170, 50)).getCenter();
        var rounds = new ArrayList<Entity>();
        rounds.add(cruise(h, origin.add(15, 0, 0)));
        var bomb = ModEntityTypes.SMALL_BOMB.get().create(h.getLevel());
        bomb.setPos(origin.add(0, 0, 15));
        bomb.tickCount = 12;
        h.getLevel().addFreshEntity(bomb);
        rounds.add(bomb);
        var charge = ModEntityTypes.C4.get().create(h.getLevel());
        charge.setPos(origin.add(-15, 0, 0));
        h.getLevel().addFreshEntity(charge);
        rounds.add(charge);
        BombExplosionHandler.detonate(
                h.getLevel(), null, BombDamageSource.create(h.getLevel()), origin, 0, 10, BombSize.SMALL);
        h.assertTrue(rounds.getFirst().getDeltaMovement().x > .08, "Cruise missile receives sideways pressure impulse");
        h.assertTrue(
                bomb.getDeltaMovement().z > .08 && charge.getDeltaMovement().x < -.05,
                "Bomb and thrown charge receive pressure impulse");
        var missile = (CruiseMissileProjectile) rounds.getFirst();
        missile.tick();
        h.assertTrue(missile.getDeltaMovement().x > .06, "Autopilot does not cancel impulse in the next tick");
        h.runAfterDelay(12, () -> {
            for (var round : rounds) {
                h.assertTrue(round.isAlive(), "Peripheral blast must not ignite the whole salvo: " + round.getType());
                round.discard();
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "airborne_core_pressure", timeoutTicks = 100)
    public static void exposedCoreCanIgniteArmedMissileButColdEjectionRemainsSafe(GameTestHelper h) {
        var origin = h.absolutePos(new BlockPos(50, 170, 50)).getCenter();
        var armed = cruise(h, origin.add(2, 0, 0));
        var cold = cruise(h, origin.add(-2, 0, 0));
        cold.ejectUpward();
        var charge = ModEntityTypes.C4.get().create(h.getLevel());
        charge.setPos(origin.add(0, 2, 0));
        var chargeTag = charge.saveWithoutId(new CompoundTag());
        chargeTag.putBoolean("Armed", true);
        chargeTag.putBoolean("Remote", true);
        charge.load(chargeTag);
        h.getLevel().addFreshEntity(charge);
        BombExplosionHandler.detonate(
                h.getLevel(), null, BombDamageSource.create(h.getLevel()), origin, 0, 10, BombSize.SMALL);
        h.assertTrue(armed.isAlive() && cold.isAlive(), "Cook-off is deferred beyond the initiating entity loop");
        h.runAfterDelay(10, () -> {
            h.assertTrue(armed.isRemoved(), "Exposed armed missile in core cooks off");
            h.assertTrue(charge.isRemoved(), "An exposed armed falling C4 charge cooks off");
            h.assertTrue(cold.isAlive(), "An unarmed ejection does not become a chain fuse");
            cold.discard();
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "airborne_external_blast", timeoutTicks = 100)
    public static void vanillaExplosionAlsoDeflectsWithoutCallingInstantCookoff(GameTestHelper h) {
        var origin = h.absolutePos(new BlockPos(50, 170, 50)).getCenter();
        var missile = cruise(h, origin.add(12, 0, 0));
        h.getLevel().explode(null, origin.x, origin.y, origin.z, 8, Level.ExplosionInteraction.NONE);
        h.assertTrue(missile.getDeltaMovement().x > .05, "External explosion pressure reaches missile");
        h.runAfterDelay(10, () -> {
            h.assertTrue(missile.isAlive(), "External peripheral blast cannot ignite missile from generic hurt");
            missile.discard();
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "airborne_cover", timeoutTicks = 100)
    public static void intactCoverStopsPressureCookoff(GameTestHelper h) {
        var origin = h.absolutePos(new BlockPos(50, 170, 50));
        var missile = cruise(h, origin.getCenter().add(5, 0, 0));
        var wall = new ArrayList<BlockPos>();
        for (int y = -3; y <= 3; y++) {
            for (int z = -3; z <= 3; z++) {
                var cell = origin.offset(2, y, z);
                wall.add(cell);
                h.getLevel()
                        .setBlock(
                                cell,
                                Blocks.BEDROCK.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        BombExplosionHandler.detonate(
                h.getLevel(), null, BombDamageSource.create(h.getLevel()), origin.getCenter(), 0, 10, BombSize.SMALL);
        h.runAfterDelay(10, () -> {
            h.assertTrue(missile.isAlive(), "Strong nearby blast behind intact bedrock does not ignite missile");
            h.assertTrue(Math.abs(missile.getDeltaMovement().x) < .02, "Cover attenuates pressure impulse");
            missile.discard();
            wall.forEach(cell -> h.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 2 | 16));
            h.succeed();
        });
    }
}
