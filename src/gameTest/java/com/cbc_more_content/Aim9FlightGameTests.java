package com.cbc_more_content;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.Aim9BlockEntity;
import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.munitions.MissileCollision;
import com.cbc_more_content.munitions.MissileFlightProfile;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@GameTestHolder("warnautics_aim9")
@PrefixGameTestTemplate(false)
public class Aim9FlightGameTests {
    private static final net.minecraft.server.level.TicketType<Long> FIXTURE =
            net.minecraft.server.level.TicketType.create("aim9_flight_test", Long::compareTo, 400);
    private static final java.util.List<Entity> FLIGHT_FIXTURES = new java.util.ArrayList<>();

    @net.minecraft.gametest.framework.AfterBatch(batch = "aim9_moving_intercept")
    public static void cleanupMovingFlight(ServerLevel level) {
        FLIGHT_FIXTURES.forEach(Entity::discard);
        FLIGHT_FIXTURES.clear();
    }

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static void loadFlightChunks(ServerLevel level, BlockPos position) {
        var center = new net.minecraft.world.level.ChunkPos(position);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.getChunk(center.x + x, center.z + z);
            }
        }
    }

    private static CruiseMissileProjectile cruise(ServerLevel level, Vec3 at, Vec3 direction) {
        level.resetEmptyTime();
        var missile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
        missile.setPos(at);
        missile.launch(direction);
        level.getChunkSource()
                .addRegionTicket(
                        FIXTURE,
                        new net.minecraft.world.level.ChunkPos(BlockPos.containing(at)),
                        2,
                        BlockPos.containing(at).asLong(),
                        true);
        loadFlightChunks(level, BlockPos.containing(at));
        level.addFreshEntity(missile);
        return missile;
    }

    private static void advance(Entity entity) {
        if (!entity.isRemoved()) {
            entity.tickCount++;
            entity.tick();
        }
    }

    private static Aim9BlockEntity rack(GameTestHelper h, BlockPos pos) {
        h.getLevel()
                .getChunkSource()
                .addRegionTicket(FIXTURE, new net.minecraft.world.level.ChunkPos(pos), 2, pos.asLong(), true);
        loadFlightChunks(h.getLevel(), pos);
        var block = (Aim9Block) ModBlocks.AIM9.get();
        block.setPlacedBy(
                h.getLevel(),
                pos,
                block.defaultBlockState().setValue(Aim9Block.FACING, Direction.UP),
                null,
                ItemStack.EMPTY);
        return (Aim9BlockEntity) h.getLevel().getBlockEntity(pos);
    }

    @GameTest(template = "empty", batch = "aim9_moving_intercept", timeoutTicks = 1200)
    public static void interceptorColdLaunchesThenCatchesMovingAndCrossingCruiseMissiles(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(50, 160, 50)).getCenter();
        var targets = new ArrayList<CruiseMissileProjectile>();
        var interceptors = new ArrayList<Aim9Projectile>();
        int scenario = 0;
        for (Vec3 velocity : List.of(new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(-1, .3, .2))) {
            Vec3 start = origin.add(0, 0, scenario++ * 1500);
            level.getChunkSource()
                    .addRegionTicket(
                            FIXTURE,
                            new net.minecraft.world.level.ChunkPos(BlockPos.containing(start)),
                            2,
                            BlockPos.containing(start).asLong(),
                            true);
            loadFlightChunks(level, BlockPos.containing(start));
            targets.add(cruise(level, start.add(110, 0, 0), velocity));
            FLIGHT_FIXTURES.add(targets.getLast());
        }
        h.runAfterDelay(40, () -> {
            for (int i = 0; i < targets.size(); i++) {
                var interceptor = ModEntityTypes.AIM9.get().create(level);
                interceptor.setPos(origin.add(0, 0, i * 1500));
                interceptor.launch(targets.get(i), Vec3.ZERO);
                level.addFreshEntity(interceptor);
                interceptors.add(interceptor);
                FLIGHT_FIXTURES.add(interceptor);
            }
        });
        boolean[] coldChecked = new boolean[3];
        boolean[] boostChecked = new boolean[3];
        h.onEachTick(() -> {
            for (int i = 0; i < interceptors.size(); i++) {
                var round = interceptors.get(i);
                if (round.tickCount >= 10 && round.tickCount < 14 && !round.isRemoved()) {
                    h.assertFalse(round.isPowered(), "Ejection has no motor");
                    h.assertTrue(round.getY() > origin.y + 7, "Ejection rises visibly");
                    coldChecked[i] = true;
                }
                if (round.tickCount >= 34 && !round.isRemoved()) {
                    h.assertTrue(
                            round.isPowered() && round.getDeltaMovement().length() > 4,
                            "Powered acceleration: ticks=" + round.tickCount + " motor="
                                    + round.saveWithoutId(new CompoundTag()).getInt("MotorTicks") + " speed="
                                    + round.getDeltaMovement().length());
                    boostChecked[i] = true;
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(interceptors.size() == 3, "Interceptors spawned");
            for (int i = 0; i < 3; i++) {
                h.assertTrue(targets.get(i).isRemoved(), "Moving contact intercepted: " + i);
                h.assertTrue(interceptors.get(i).isRemoved(), "Interceptor consumed: " + i);
                h.assertTrue(coldChecked[i], "Cold phase observed: " + i);
                h.assertTrue(
                        boostChecked[i]
                                || interceptors
                                                .get(i)
                                                .saveWithoutId(new CompoundTag())
                                                .getInt("MotorTicks")
                                        > 0,
                        "Motor ignited before interception: " + i);
            }
            h.assertTrue(boostChecked[0], "Outrunning target requires full acceleration");
        });
    }

    @GameTest(template = "empty", batch = "aim9_range_controls", timeoutTicks = 100)
    public static void disabledAndFilteredRacksHoldFireAndSphericalRangeIsEnforced(GameTestHelper h) {
        var level = h.getLevel();
        // Pick a scan phase matching this server tick; this exercises the actual BE ticker.
        BlockPos pos = h.absolutePos(new BlockPos(50, 160, 50));
        while (Math.floorMod(level.getGameTime() + pos.asLong(), 10) != 0) {
            pos = pos.above();
        }
        var be = rack(h, pos);
        final BlockPos scanPos = pos;
        var target = new CruiseMissileProjectile(ModEntityTypes.CRUISE_MISSILE.get(), level) {
            @Override
            public void tick() {}
        };
        target.setPos(pos.getCenter().add(220, 0, 0));
        level.getChunkSource()
                .addRegionTicket(
                        FIXTURE, new net.minecraft.world.level.ChunkPos(target.blockPosition()), 2, pos.asLong(), true);
        loadFlightChunks(level, target.blockPosition());
        for (Vec3 offset : List.of(new Vec3(41, 0, 0), new Vec3(39, 39, 0))) {
            BlockPos extra = BlockPos.containing(pos.getCenter().add(offset));
            level.getChunkSource()
                    .addRegionTicket(FIXTURE, new net.minecraft.world.level.ChunkPos(extra), 2, extra.asLong(), true);
            loadFlightChunks(level, extra);
        }
        level.addFreshEntity(target);
        h.runAfterDelay(40, () -> {
            BlockPos position = scanPos;
            target.setPos(position.getCenter().add(41, 0, 0));
            be.configure(false, true, 40);
            Aim9BlockEntity.serverTick(level, position, be.getBlockState(), be);
            h.assertTrue(be.isLiveAirframe(), "Disabled launcher must remain on the rack");
            be.configure(true, false, 220);
            Aim9BlockEntity.serverTick(level, position, be.getBlockState(), be);
            h.assertTrue(be.isLiveAirframe(), "Cruise filter off must hold fire");
            be.configure(true, true, -500);
            h.assertTrue(be.range() == 40, "Server clamps forged negative range");
            Aim9BlockEntity.serverTick(level, position, be.getBlockState(), be);
            h.assertTrue(be.isLiveAirframe(), "Target one block outside radius must not launch");
            target.setPos(position.getCenter().add(39, 39, 0));
            Aim9BlockEntity.serverTick(level, position, be.getBlockState(), be);
            h.assertTrue(be.isLiveAirframe(), "Range is spherical, not a cube");
            be.configure(true, true, 99999);
            h.assertTrue(be.range() == 220, "Server clamps oversized range");
            target.setPos(position.getCenter().add(220, 0, 0));
            Aim9BlockEntity.serverTick(level, position, be.getBlockState(), be);
            h.assertFalse(
                    be.isLiveAirframe(),
                    "A target exactly at maximum range launches: time=" + level.getGameTime() + " phase="
                            + Math.floorMod(level.getGameTime() + position.asLong(), 10) + " target="
                            + target.position() + " candidates="
                            + level.getEntitiesOfClass(CruiseMissileProjectile.class, new AABB(position).inflate(230))
                                    .size()
                            + " live=" + target.isAlive());
            for (var missile : level.getEntitiesOfClass(
                    Aim9Projectile.class, new AABB(position).inflate(230), e -> target.getUUID()
                            .equals(e.targetId()))) {
                h.assertTrue(target.getUUID().equals(missile.targetId()), "Round owns the selected target");
                missile.discard();
            }
            target.discard();
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "aim9_claim", timeoutTicks = 100)
    public static void secondRackDoesNotWasteAnotherInterceptorOnAnAssignedTarget(GameTestHelper h) {
        var level = h.getLevel();
        // Racks are outside the tiny empty template and survive other batches' teardown.
        // Keep this target's real flight away from the obstacles those tests deliberately place.
        BlockPos pos = h.absolutePos(new BlockPos(50, 160, 6500));
        BlockPos second = pos.east(5);
        var first = rack(h, pos);
        var next = rack(h, second);
        var target = cruise(level, pos.getCenter().add(90, 0, 0), new Vec3(1, 0, 0));
        h.runAfterDelay(4, () -> {
            first.configure(true, true, 220);
            next.configure(true, true, 220);
        });
        h.runAfterDelay(25, () -> {
            var shots = level.getEntitiesOfClass(Aim9Projectile.class, new AABB(pos).inflate(140), e -> target.getUUID()
                    .equals(e.targetId()));
            try {
                h.assertTrue(target.isAlive(), "Contact survives in the isolated flight corridor");
                h.assertTrue(
                        first.isLiveAirframe() != next.isLiveAirframe(),
                        "One rack launches, one retains its round: targets="
                                + level.getEntitiesOfClass(CruiseMissileProjectile.class, new AABB(pos).inflate(150))
                                        .size()
                                + " cruiseTicks=" + target.tickCount + " pos=" + target.position() + " ticking="
                                + level.getChunkSource()
                                        .isPositionTicking(new net.minecraft.world.level.ChunkPos(pos).toLong()));
                h.assertTrue(shots.size() == 1, "Exactly one interceptor for one contact, actual=" + shots.size());
            } finally {
                shots.forEach(Entity::discard);
                target.discard();
                for (var body : List.of(pos, second)) {
                    for (var cell : List.of(body, body.above(), body.below())) {
                        level.setBlock(cell, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "aim9_lost_target", timeoutTicks = 100)
    public static void missingTargetEndsFlightAndSaveRestoresGuidance(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(50, 160, 50)).getCenter();
        var target = cruise(level, at.add(90, 0, 0), new Vec3(1, 0, 0));
        var interceptor = ModEntityTypes.AIM9.get().create(level);
        interceptor.setPos(at);
        interceptor.launch(target, new Vec3(.3, 0, .2));
        var tag = interceptor.saveWithoutId(new CompoundTag());
        var loaded = ModEntityTypes.AIM9.get().create(level);
        loaded.load(tag);
        h.assertTrue(target.getUUID().equals(loaded.targetId()), "Target UUID survives save/reload");
        h.assertTrue(
                loaded.getDeltaMovement().distanceTo(interceptor.getDeltaMovement()) < .001,
                "Inherited carrier velocity survives reload");
        target.discard();
        for (int tick = 0; tick < 35; tick++) {
            advance(loaded);
        }
        h.assertTrue(loaded.isRemoved(), "No immortal interceptor after losing its target");
        interceptor.discard();
        loaded.discard();
        h.succeed();
    }

    @GameTest(template = "empty", batch = "missile_contact", timeoutTicks = 100)
    public static void parallelNearMissesSurviveButPhysicalCrossingStillHits(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(50, 160, 50)).getCenter();
        var first = cruise(level, at, new Vec3(1, 0, 0));
        var second = cruise(level, at.add(0, 0, 1), new Vec3(1, 0, 0));
        h.assertTrue(
                MissileCollision.contact(first, second, at, at.add(1.4, 0, 0)) == null,
                "Broad-phase overlap with one-block lateral gap is not a contact");
        for (int tick = 0; tick < 18; tick++) {
            advance(first);
            advance(second);
        }
        h.assertFalse(first.isRemoved() || second.isRemoved(), "Nearby rounds must not spontaneously detonate");
        first.setPos(at);
        second.setPos(at.add(0, 0, .1));
        h.assertTrue(
                MissileCollision.contact(first, second, at, at.add(1.4, 0, 0)) != null,
                "Actual airframe overlap must remain collidable");
        second.setPos(at.add(2, 0, 2));
        second.launch(new Vec3(0, 0, -1));
        second.setDeltaMovement(0, 0, -4);
        h.assertTrue(
                MissileCollision.contact(first, second, at, at.add(4, 0, 0)) != null,
                "Fast perpendicular crossing cannot tunnel between ticks");
        first.discard();
        second.discard();
        h.succeed();
    }

    @GameTest(template = "empty", batch = "missile_salvo", timeoutTicks = 100)
    public static void eightRoundSalvoSeparatesWithoutPrematureDetonationForAllProfiles(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(50, 160, 50)).getCenter();
        int scenario = 0;
        for (var profile : MissileFlightProfile.values()) {
            Vec3 origin = at.add(0, 0, scenario++ * 300);
            var rounds = new ArrayList<CruiseMissileProjectile>();
            UUID salvo = UUID.randomUUID();
            for (int index = 0; index < 8; index++) {
                var missile = cruise(level, origin.add(0, 0, index), new Vec3(1, 0, 0));
                missile.setFlightProfile(profile);
                missile.setGuidance(Guidance.COORDINATES, BlockPos.containing(origin.add(400, 0, 3.5)), -1);
                missile.setSalvo(salvo, index, 8, origin.add(0, 0, 3.5));
                rounds.add(missile);
            }
            for (int tick = 0; tick < 65; tick++) {
                for (var round : rounds) {
                    advance(round);
                    h.assertFalse(
                            round.isRemoved(), profile + ": all eight rounds stay alive before terminal approach");
                }
            }
            double minDistance = Double.MAX_VALUE;
            for (int i = 0; i < rounds.size(); i++) {
                for (int j = i + 1; j < rounds.size(); j++) {
                    minDistance = Math.min(
                            minDistance,
                            rounds.get(i).position().distanceTo(rounds.get(j).position()));
                }
            }
            h.assertTrue(minDistance > 2, profile + ": separation must open a usable gap, minimum=" + minDistance);
            h.assertTrue(
                    rounds.stream().allMatch(e -> e.getX() > origin.x + 45),
                    "Avoidance keeps the salvo advancing toward its target");
            rounds.forEach(Entity::discard);
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "aim9_launch_sable", timeoutTicks = 100)
    public static void interceptorLaunchesFromRotatingMovingCarrierWithoutPlotGhosts(GameTestHelper h) {
        var level = h.getLevel();
        var body = h.absolutePos(new BlockPos(50, 160, 50));
        rack(h, body).configure(true, true, 220);
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, body, List.of(body.below(), body, body.above()), new BoundingBox3i(body.below(), body.above()));
        h.runAfterDelay(4, () -> {
            var handle = RigidBodyHandle.of(ship);
            handle.teleport(
                    new Vector3d(body.getX() + .5, body.getY() + .5, body.getZ() + .5),
                    new Quaterniond().rotateY(.4).rotateZ(.3));
            handle.addLinearAndAngularVelocity(new Vector3d(6, 0, 0), new Vector3d(0, .1, 0));
        });
        h.runAfterDelay(7, () -> {
            var local = ship.getPlot().getCenterBlock();
            var old = (Aim9BlockEntity) level.getBlockEntity(local);
            h.assertTrue(old.enabled() && old.range() == 220, "Settings survive assembly");
            var center = ship.logicalPose().transformPosition(local.getCenter());
            var target = cruise(level, center.add(100, 0, 0), new Vec3(1, 0, 0));
            var interceptor = Aim9Block.launch(level, local, level.getBlockState(local), target);
            h.assertTrue(
                    interceptor != null && interceptor.position().distanceTo(center) < .1,
                    "Round spawns at world-space carrier position");
            h.assertTrue(interceptor.getDeltaMovement().x > .15, "Round inherits carrier momentum");
            h.assertFalse(old.isLiveAirframe(), "Old renderer cannot leave a rack ghost");
            for (var pos : List.of(local, local.above(), local.below())) {
                h.assertTrue(level.getBlockState(pos).isAir(), "All plot cells removed at launch");
            }
            for (int tick = 0; tick < 8; tick++) {
                advance(target);
                advance(interceptor);
            }
            h.assertFalse(interceptor.isRemoved(), "Round clears its old sublevel without hitting a phantom rack");
            interceptor.discard();
            target.discard();
            h.succeed();
        });
    }
}
