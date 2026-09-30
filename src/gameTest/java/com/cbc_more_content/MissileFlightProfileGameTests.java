package com.cbc_more_content;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.munitions.MissileFlightProfile;
import com.cbc_more_content.registry.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Flight plans must produce different paths and fuel use in the real server entity. */
@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class MissileFlightProfileGameTests {
    @GameTest(template = "empty", batch = "missile_flight_profiles", timeoutTicks = 130)
    public static void profilesChangePathSpeedAndRange(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(50, 100, 50));
        CruiseMissileProjectile[] missiles = new CruiseMissileProjectile[3];
        MissileFlightProfile[] profiles = MissileFlightProfile.values();
        double[] startingZ = new double[3];
        for (int i = 0; i < missiles.length; i++) {
            BlockPos start = base.offset(0, 0, i * 30);
            BlockPos destination = start.offset(400, 0, 0);
            var missile = new CruiseMissileProjectile(ModEntityTypes.CRUISE_MISSILE.get(), level);
            missile.setPos(Vec3.atCenterOf(start));
            missile.setGuidance(Guidance.COORDINATES, destination, -1);
            missile.setFlightProfile(profiles[i]);
            missile.launch(new Vec3(1, 0, 0));
            missiles[i] = missile;
            startingZ[i] = missile.getZ();
        }

        double[] apex = {Double.NEGATIVE_INFINITY};
        double[] lateral = {0};
        double[] lowSpeed = {Double.POSITIVE_INFINITY};
        double[] highSpeed = {0};
        // The distant fixture is outside normal entity-ticking range. Advance the
        // actual server projectile directly, as the existing Sable flight test does.
        for (int tick = 1; tick <= 105; tick++) {
            for (var missile : missiles) {
                missile.tick();
            }
            if (tick == 20 || tick == 35 || tick == 50 || tick == 65 || tick == 80) {
                for (var missile : missiles) {
                    helper.assertFalse(missile.isRemoved(), "A flight plan must not discard the projectile");
                }
                apex[0] = Math.max(apex[0], missiles[1].getY() - missiles[0].getY());
                lateral[0] = Math.max(lateral[0], Math.abs(missiles[2].getZ() - startingZ[2]));
                double speed = missiles[2].getDeltaMovement().length();
                lowSpeed[0] = Math.min(lowSpeed[0], speed);
                highSpeed[0] = Math.max(highSpeed[0], speed);
            }
        }
        helper.assertTrue(
                apex[0] > 4.0,
                "Arc profile must climb above direct flight: height=" + apex[0] + " ticks=" + missiles[1].tickCount);
        helper.assertTrue(lateral[0] > 0.35, "Evasive profile must weave sideways: offset=" + lateral[0]);
        helper.assertTrue(
                highSpeed[0] - lowSpeed[0] > 0.18,
                "Evasive throttle must change in flight: min=" + lowSpeed[0] + " max=" + highSpeed[0]);
        int directFuel = missiles[0].saveWithoutId(new CompoundTag()).getInt("Fuel");
        int arcFuel = missiles[1].saveWithoutId(new CompoundTag()).getInt("Fuel");
        int evasiveFuel = missiles[2].saveWithoutId(new CompoundTag()).getInt("Fuel");
        helper.assertTrue(directFuel > arcFuel && arcFuel > evasiveFuel, "Flight modes must spend different fuel");
        var restored = new CruiseMissileProjectile(ModEntityTypes.CRUISE_MISSILE.get(), level);
        restored.load(missiles[1].saveWithoutId(new CompoundTag()));
        helper.assertTrue(restored.flightProfile() == MissileFlightProfile.ARC, "Saved missile keeps its plan");
        missiles[0].discard();
        missiles[1].discard();
        missiles[2].discard();
        helper.succeed();
    }
}
