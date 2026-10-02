package com.cbc_more_content;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.munitions.MissileFlightProfile;
import com.cbc_more_content.munitions.MissileGuidanceError;
import com.cbc_more_content.registry.ModEntityTypes;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("warnautics_aim9")
@PrefixGameTestTemplate(false)
public class MissileMissGameTests {
    private static final Vec3 FORWARD = new Vec3(1, 0, 0);
    private static final net.minecraft.server.level.TicketType<Long> FIXTURE =
            net.minecraft.server.level.TicketType.create("missile_miss_fixture", Long::compareTo, 400);

    private static void advance(Entity entity) {
        if (!entity.isRemoved()) {
            entity.tickCount++;
            entity.tick();
        }
    }

    private static UUID cruiseId(boolean miss, int first) {
        for (int index = first; index < first + 10000; index++) {
            UUID id = new UUID(0, ((long) index << 32) | 0x40008000L);
            if ((MissileGuidanceError.cruiseOffset(id, 1.8).length() >= 6) == miss) {
                return id;
            }
        }
        throw new AssertionError("No guidance fixture seed");
    }

    private static CruiseMissileProjectile target(GameTestHelper h, Vec3 at) {
        var pos = BlockPos.containing(at);
        var level = h.getLevel();
        var center = new net.minecraft.world.level.ChunkPos(pos);
        level.getChunkSource().addRegionTicket(FIXTURE, center, 2, pos.asLong(), true);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.getChunk(center.x + x, center.z + z);
            }
        }
        var target = new CruiseMissileProjectile(ModEntityTypes.CRUISE_MISSILE.get(), h.getLevel()) {
            @Override
            public void tick() {}
        };
        target.setPos(at);
        target.launch(FORWARD);
        target.setDeltaMovement(Vec3.ZERO);
        h.assertTrue(h.getLevel().addFreshEntity(target), "Target fixture spawns");
        return target;
    }

    private static Aim9Projectile hotRound(GameTestHelper h, CruiseMissileProjectile target, Vec3 at, UUID id) {
        h.getLevel().resetEmptyTime();
        var pos = BlockPos.containing(at);
        var center = new net.minecraft.world.level.ChunkPos(pos);
        h.getLevel().getChunkSource().addRegionTicket(FIXTURE, center, 2, pos.asLong(), true);
        h.getLevel().getChunkAt(pos);
        var round = ModEntityTypes.AIM9.get().create(h.getLevel());
        round.setUUID(id);
        round.setPos(at);
        round.launch(target, Vec3.ZERO);
        var tag = round.saveWithoutId(new CompoundTag());
        tag.putInt("EjectTicks", 0);
        tag.putInt("MotorTicks", 40);
        round.load(tag);
        round.setDeltaMovement(FORWARD.scale(5.5));
        return round;
    }

    @GameTest(template = "empty", batch = "missile_error_distribution", timeoutTicks = 100)
    public static void errorsAreOccasionalBoundedAndHarderInterceptsMissMoreOften(GameTestHelper h) {
        int cruiseMisses = 0, easyMisses = 0, hardMisses = 0;
        double easy =
                MissileGuidanceError.interceptMissChance(new Vec3(30, 0, 0), FORWARD.scale(5.5), Vec3.ZERO, Vec3.ZERO);
        double hard = MissileGuidanceError.interceptMissChance(
                new Vec3(30, 0, 0), FORWARD.scale(5.5), new Vec3(-3, 0, 4), new Vec3(0, 0, .8));
        h.assertTrue(
                easy >= .08 && hard <= .50 && hard > easy + .25,
                "Geometry and a last-second manoeuvre increase the risk");
        for (int shot = 0; shot < 10000; shot++) {
            UUID id = new UUID(0, ((long) shot << 32) | 0x40008000L);
            Vec3 error = MissileGuidanceError.cruiseOffset(id, 1.8);
            h.assertTrue(error.length() <= 10 && error.y == 0, "Cruise error remains bounded and horizontal");
            if (error.length() >= 6) {
                cruiseMisses++;
            } else {
                h.assertTrue(error.length() <= 1.8, "Ordinary shots retain their small dispersion");
            }
            if (MissileGuidanceError.missesIntercept(id, 0, easy)) {
                easyMisses++;
            }
            if (MissileGuidanceError.missesIntercept(id, 0, hard)) {
                hardMisses++;
            }
        }
        h.assertTrue(
                cruiseMisses > 1000 && cruiseMisses < 1400,
                "About 12 percent of cruise launches miss: " + cruiseMisses);
        h.assertTrue(
                easyMisses > 600 && easyMisses < 1000 && hardMisses > easyMisses * 3,
                "Difficulty changes the distribution, not just the animation");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "cruise_actual_miss", timeoutTicks = 100)
    public static void everyCruiseProfileCanMissAndReloadDoesNotMoveItsImpactPoint(GameTestHelper h) {
        var level = h.getLevel();
        Vec3 start = h.absolutePos(new BlockPos(70, 170, 9000)).getCenter();
        BlockPos destination = BlockPos.containing(start.add(120, 0, 0));
        for (var profile : MissileFlightProfile.values()) {
            for (boolean miss : new boolean[] {false, true}) {
                var round = ModEntityTypes.CRUISE_MISSILE.get().create(level);
                round.setUUID(cruiseId(miss, 100));
                round.setPos(start);
                round.launch(FORWARD);
                round.setGuidance(Guidance.COORDINATES, destination, -1);
                round.setFlightProfile(profile);
                Vec3 expected = MissileGuidanceError.cruiseOffset(round.getUUID(), 1.8);
                var loaded = ModEntityTypes.CRUISE_MISSILE.get().create(level);
                loaded.load(round.saveWithoutId(new CompoundTag()));
                round.discard();
                h.assertTrue(
                        MissileGuidanceError.cruiseOffset(loaded.getUUID(), 1.8).equals(expected),
                        "A save cannot reroll guidance error");
                Vec3[] impact = {null};
                Consumer<ExplosionEvent.Start> recorder = event -> {
                    if (event.getExplosion().getDirectSourceEntity() == loaded) {
                        impact[0] = event.getExplosion().center();
                        event.setCanceled(true);
                    }
                };
                NeoForge.EVENT_BUS.addListener(recorder);
                try {
                    for (int tick = 0; tick < 160 && !loaded.isRemoved(); tick++) {
                        advance(loaded);
                    }
                    h.assertTrue(impact[0] != null, "Flight reaches the committed aim: " + profile);
                    double distance = impact[0].distanceTo(destination.getCenter());
                    h.assertTrue(
                            miss ? distance > 4 && distance < 12.4 : distance < 4.4,
                            "The miss changes the actual explosion location: " + profile + " " + distance);
                } finally {
                    NeoForge.EVENT_BUS.unregister(recorder);
                    loaded.discard();
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_miss_return", timeoutTicks = 100)
    public static void failedInterceptPassesTurnsInAnArcAndRetriesTheSameTargetAfterReload(GameTestHelper h) {
        Vec3 at = h.absolutePos(new BlockPos(70, 170, 11000)).getCenter();
        var target = target(h, at);
        h.runAfterDelay(40, () -> checkReturn(h, target, at));
    }

    private static void checkReturn(GameTestHelper h, CruiseMissileProjectile target, Vec3 at) {
        h.assertTrue(
                h.getLevel().getEntity(target.getUUID()) == target, "Flight fixture is registered for UUID guidance");
        Aim9Projectile[] round = {hotRound(h, target, at.add(-34, 0, 0), new UUID(0, 2))};
        Consumer<ExplosionEvent.Start> cancelFixtureBlast = event -> {
            if (event.getExplosion().getDirectSourceEntity() == target
                    || event.getExplosion().getDirectSourceEntity() == round[0]) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(cancelFixtureBlast);
        boolean returned = false, restored = false, turnRestored = false;
        int turnTicks = 0;
        try {
            for (int tick = 0; tick < 200 && !round[0].isRemoved(); tick++) {
                var before = round[0].saveWithoutId(new CompoundTag());
                Vec3 previousHeading = round[0].getDeltaMovement().normalize();
                advance(round[0]);
                var state = round[0].saveWithoutId(new CompoundTag());
                if (state.getBoolean("Recovering")) {
                    returned = true;
                    h.assertTrue(target.isAlive(), "A failed pass cannot destroy the target remotely");
                    double angle = Math.acos(net.minecraft.util.Mth.clamp(
                            previousHeading.dot(round[0].getDeltaMovement().normalize()), -1, 1));
                    h.assertTrue(angle <= .141, "The return has a bounded turn per tick: " + angle);
                    turnTicks++;
                    if ((!restored && turnTicks >= 3) || (!turnRestored && state.getBoolean("RecoveryTurning"))) {
                        var replacement = ModEntityTypes.AIM9.get().create(h.getLevel());
                        replacement.load(state);
                        round[0].discard();
                        round[0] = replacement;
                        var restoredState = replacement.saveWithoutId(new CompoundTag());
                        h.assertTrue(
                                restoredState.getBoolean("Recovering")
                                        && restoredState.getInt("MotorTicks") == state.getInt("MotorTicks")
                                        && restoredState.getInt("ApproachNumber") == state.getInt("ApproachNumber")
                                        && restoredState.getBoolean("RecoveryTurning")
                                                == state.getBoolean("RecoveryTurning"),
                                "Reload preserves return, fuel and attempt number");
                        if (state.getBoolean("RecoveryTurning")) {
                            turnRestored = true;
                        } else {
                            restored = true;
                        }
                    }
                }
                h.assertTrue(target.getUUID().equals(round[0].targetId()), "Return retains the assigned target");
                if (round[0].isRemoved()) {
                    h.assertTrue(
                            before.getInt("MotorTicks") < 240, "Second approach ends in interception, not exhaustion");
                }
            }
            h.assertTrue(
                    returned && restored && turnRestored && turnTicks >= 8,
                    "A visible return arc was exercised across reload: turnTicks=" + turnTicks);
            h.assertTrue(
                    target.isRemoved() && round[0].isRemoved(),
                    "A second attempt physically intercepts the same contact");
            h.assertTrue(
                    round[0].saveWithoutId(new CompoundTag()).getInt("ApproachNumber") >= 2,
                    "The approach lottery runs once per pass");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelFixtureBlast);
            round[0].discard();
            target.discard();
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_moving_miss_return", timeoutTicks = 100)
    public static void missedMovingCruiseMissileIsInterceptedOnAReturnPassBeforeEitherMotorExpires(GameTestHelper h) {
        Vec3 at = h.absolutePos(new BlockPos(70, 170, 17000)).getCenter();
        boolean[] flying = {false};
        var target = new CruiseMissileProjectile(ModEntityTypes.CRUISE_MISSILE.get(), h.getLevel()) {
            @Override
            public void tick() {
                if (flying[0]) {
                    super.tick();
                }
            }
        };
        target.setPos(at);
        target.launch(FORWARD);
        var tag = target.saveWithoutId(new CompoundTag());
        tag.putInt("PoweredTicks", 40);
        target.load(tag);
        var center = new net.minecraft.world.level.ChunkPos(target.blockPosition());
        // This fixture follows a fast tail chase through a full return, well outside
        // the empty template. Activate its flight corridor before UUID tracking starts.
        for (int x = -3; x <= 54; x++) {
            for (int z = -4; z <= 4; z++) {
                var flightChunk = new net.minecraft.world.level.ChunkPos(center.x + x, center.z + z);
                h.getLevel().getChunkSource().addRegionTicket(FIXTURE, flightChunk, 2, flightChunk.toLong(), true);
                h.getLevel().getChunk(flightChunk.x, flightChunk.z);
            }
        }
        Vec3 launch = at.add(-34, 0, 0);
        BlockPos launchPos = BlockPos.containing(launch);
        h.getLevel()
                .getChunkSource()
                .addRegionTicket(
                        FIXTURE, new net.minecraft.world.level.ChunkPos(launchPos), 2, launchPos.asLong(), true);
        h.getLevel().getChunkAt(launchPos);
        h.assertTrue(h.getLevel().addFreshEntity(target), "Moving contact fixture spawns");
        h.runAfterDelay(40, () -> {
            h.assertTrue(h.getLevel().getEntity(target.getUUID()) == target, "Moving target is registered");
            var round = hotRound(h, target, launch, new UUID(0, 2));
            boolean[] intercepted = {false};
            Consumer<ExplosionEvent.Start> recorder = event -> {
                if (event.getExplosion().getDirectSourceEntity() == target) {
                    intercepted[0] = true;
                    event.setCanceled(true);
                } else if (event.getExplosion().getDirectSourceEntity() == round) {
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(recorder);
            boolean returned = false, turned = false;
            try {
                flying[0] = true;
                // Advance both real flight implementations together. Keeping this synchronous
                // separates guidance from the GameTest server's asynchronous chunk activation.
                for (int tick = 0; tick < 200 && !round.isRemoved(); tick++) {
                    advance(target);
                    advance(round);
                    var state = round.saveWithoutId(new CompoundTag());
                    returned |= state.getBoolean("Recovering");
                    turned |= state.getBoolean("RecoveryTurning");
                }
                var end = round.saveWithoutId(new CompoundTag());
                h.assertTrue(
                        returned && turned,
                        "A genuine moving miss and return arc occurred: motor=" + end.getInt("MotorTicks")
                                + " lost=" + end.getInt("LostTicks") + " attempts=" + end.getInt("ApproachNumber")
                                + " closest=" + end.getDouble("ClosestApproach") + " burst=" + intercepted[0]);
                h.assertTrue(
                        intercepted[0] && target.isRemoved() && round.isRemoved(),
                        "Return physically catches the moving contact: motor="
                                + round.saveWithoutId(new CompoundTag()).getInt("MotorTicks") + " targetFuel="
                                + target.saveWithoutId(new CompoundTag()).getInt("Fuel"));
                h.assertTrue(
                        target.saveWithoutId(new CompoundTag()).getInt("Fuel") > 0
                                && round.saveWithoutId(new CompoundTag()).getInt("MotorTicks") < 240,
                        "Interception happens before either motor expires");
            } finally {
                NeoForge.EVENT_BUS.unregister(recorder);
                round.discard();
                target.discard();
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "aim9_return_fuel", timeoutTicks = 100)
    public static void returningInterceptorUsesItsRemainingFuelAndCannotRetryForever(GameTestHelper h) {
        Vec3 at = h.absolutePos(new BlockPos(70, 170, 13000)).getCenter();
        var target = target(h, at.add(-40, 0, 0));
        h.runAfterDelay(40, () -> checkFuel(h, target, at));
    }

    private static void checkFuel(GameTestHelper h, CruiseMissileProjectile target, Vec3 at) {
        h.assertTrue(
                h.getLevel().getEntity(target.getUUID()) == target, "Flight fixture is registered for UUID guidance");
        var round = hotRound(h, target, at, new UUID(0, 2));
        var tag = round.saveWithoutId(new CompoundTag());
        tag.putInt("MotorTicks", 230);
        tag.putBoolean("Recovering", true);
        tag.putBoolean("ApproachChecked", true);
        tag.putInt("ApproachNumber", 1);
        round.load(tag);
        try {
            for (int tick = 0; tick < 30 && !round.isRemoved(); tick++) {
                advance(round);
            }
            h.assertTrue(round.isRemoved() && !round.isPowered(), "Motor stops when the original fuel budget expires");
            h.assertTrue(target.isAlive(), "An unreachable target is not killed on fuel exhaustion");
            h.assertTrue(
                    round.saveWithoutId(new CompoundTag()).getInt("MotorTicks") == 241,
                    "Return did not refill the motor");
        } finally {
            round.discard();
            target.discard();
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_error_collision", timeoutTicks = 100)
    public static void anActualContactStillInterceptsEvenOnAnInaccurateApproach(GameTestHelper h) {
        Vec3 at = h.absolutePos(new BlockPos(70, 170, 15000)).getCenter();
        var target = target(h, at.add(1, 0, 0));
        h.runAfterDelay(40, () -> checkContact(h, target, at));
    }

    private static void checkContact(GameTestHelper h, CruiseMissileProjectile target, Vec3 at) {
        h.assertTrue(
                h.getLevel().getEntity(target.getUUID()) == target, "Flight fixture is registered for UUID guidance");
        var round = hotRound(h, target, at, new UUID(0, 2));
        var tag = round.saveWithoutId(new CompoundTag());
        tag.putBoolean("ApproachChecked", true);
        tag.putDouble("ApproachOffsetZ", 8);
        round.load(tag);
        Consumer<ExplosionEvent.Start> recorder = event -> {
            if (event.getExplosion().getDirectSourceEntity() == target
                    || event.getExplosion().getDirectSourceEntity() == round) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(recorder);
        try {
            advance(round);
            h.assertTrue(
                    target.isRemoved() && round.isRemoved(),
                    "Guidance error cannot disable the real contact/proximity fuse");
        } finally {
            NeoForge.EVENT_BUS.unregister(recorder);
            target.discard();
            round.discard();
        }
        h.succeed();
    }
}
