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
                // Keep the baseline pursuit fixture deterministic; miss/recovery has its own tests.
                interceptor.setUUID(new UUID(0, new long[] {1, 4, 5}[i]));
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
                // The cruise airframe may collide first and its blast can slow the interceptor
                // before the deferred cook-off. Check motor acceleration before contact.
                if (round.tickCount >= 34
                        && !round.isRemoved()
                        && targets.get(i).isAlive()) {
                    h.assertTrue(
                            round.isPowered() && round.getDeltaMovement().length() > 4,
                            "Powered acceleration: ticks=" + round.tickCount + " motor="
                                    + round.saveWithoutId(new CompoundTag()).getInt("MotorTicks") + " speed="
                                    + round.getDeltaMovement().length() + " scenario=" + i);
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
        // A salvo leaves a rack that stands in loaded ground. Ticket each launch area first
        // and fly once those chunks are live, so every round can see its neighbours.
        for (int scenario = 0; scenario < MissileFlightProfile.values().length; scenario++) {
            var pos = BlockPos.containing(at.add(0, 0, scenario * 300 + 3.5));
            level.getChunkSource()
                    .addRegionTicket(FIXTURE, new net.minecraft.world.level.ChunkPos(pos), 3, pos.asLong(), true);
            loadFlightChunks(level, pos);
        }
        h.runAfterDelay(20, () -> flySalvos(h, level, at));
    }

    private static void flySalvos(GameTestHelper h, ServerLevel level, Vec3 at) {
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

    @GameTest(template = "empty", batch = "aim9_ship_auto", timeoutTicks = 200)
    public static void rackOnAStationaryShipFiresByItself(GameTestHelper h) {
        shipRackFires(h, new BlockPos(50, 160, 50), 0);
    }

    @GameTest(template = "empty", batch = "aim9_ship_auto_moving", timeoutTicks = 200)
    public static void rackOnAMovingShipFiresByItself(GameTestHelper h) {
        shipRackFires(h, new BlockPos(50, 160, 650), 1.5);
    }

    /** Other tests' missiles still in the air would be fair game for a rack set up here. */
    private static void clearSkies(ServerLevel level, BlockPos around) {
        var area = new AABB(around).inflate(450);
        int stray = 0;
        for (var e : level.getEntitiesOfClass(
                Entity.class, area, e -> e instanceof CruiseMissileProjectile || e instanceof Aim9Projectile)) {
            e.discard();
            stray++;
        }
        if (stray > 0) {
            com.cbc_more_content.CBCMoreContent.LOGGER.info("AIM-9 ship test cleared {} stray missiles", stray);
        }
    }

    /** What the climb from a ship rack runs into first, for a failure message. */
    private static String blocker(ServerLevel level, BlockPos body) {
        var cells = java.util.Set.copyOf(
                com.cbc_more_content.compat.AirframeMovement.cells(level, body, level.getBlockState(body)));
        var frame = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(level, body.getCenter(), Vec3.ZERO, null);
        StringBuilder out = new StringBuilder("cells=" + cells + " body=" + body + " world=" + frame.pos());
        for (double t = 0; t <= 11; t += .25) {
            var p = BlockPos.containing(body.getCenter().add(0, t, 0));
            if (!cells.contains(p)
                    && !level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                out.append(" plotHit=").append(p).append(' ').append(level.getBlockState(p));
                break;
            }
        }
        for (double t = 0; t <= 11; t += .25) {
            var p = BlockPos.containing(frame.pos().add(0, t, 0));
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                out.append(" worldHit=").append(p).append(' ').append(level.getBlockState(p));
                break;
            }
        }
        Object carrier = com.cbc_more_content.compat.SableDropCompat.carrierOf(level, body);
        dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.create.raycasts.SableRaycastHelper
                .rayCastUntilWithSublevels(level, frame.pos(), frame.pos().add(0, 10.99, 0), p -> false, (sub, p) -> {
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty() && !cells.contains(p)) {
                out.append(" subHit=")
                        .append(p)
                        .append(" same=")
                        .append(sub == carrier)
                        .append(' ')
                        .append(level.getBlockState(p));
                return true;
            }
            return false;
        });
        return out.toString();
    }

    /** Builds a deck, assembles it, and sets a rack on it with the item, as a player would. */
    private static BlockPos rackPlacedOnShip(GameTestHelper h, BlockPos relative, boolean roofOver) {
        var level = h.getLevel();
        var center = h.absolutePos(relative);
        clearSkies(level, center);
        level.getChunkSource()
                .addRegionTicket(FIXTURE, new net.minecraft.world.level.ChunkPos(center), 2, center.asLong(), true);
        loadFlightChunks(level, center);
        // Afloat, as a ship is in the game: a basin of water under the deck.
        for (var p : BlockPos.betweenClosed(center.offset(-8, -5, -8), center.offset(8, -1, 8))) {
            boolean wall = Math.abs(p.getX() - center.getX()) == 8
                    || Math.abs(p.getZ() - center.getZ()) == 8
                    || p.getY() == center.getY() - 5;
            level.setBlock(
                    p,
                    wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
        }
        var cells = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(center.offset(-3, 0, -3), center.offset(3, 0, 3))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells.add(p.immutable());
        }
        if (roofOver) {
            // A bridge wing reaching out over the launcher, six blocks up.
            for (var p : BlockPos.betweenClosed(center.offset(-1, 1, -3), center.offset(-1, 6, -3))) {
                level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                cells.add(p.immutable());
            }
            for (var p : BlockPos.betweenClosed(center.offset(-1, 6, -2), center.offset(1, 6, 1))) {
                level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                cells.add(p.immutable());
            }
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, center, cells, new BoundingBox3i(center.offset(-3, 0, -3), center.offset(3, 6, 3)));
        var deck = ship.getPlot().getCenterBlock();
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "aim9-deck"));
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        player.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(com.cbc_more_content.registry.ModItems.AIM9.get()));
        player.getMainHandItem()
                .useOn(new net.minecraft.world.item.context.UseOnContext(
                        player,
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(
                                deck.getCenter().add(0, .5, 0), Direction.UP, deck, false)));
        for (int up = 1; up <= 3; up++) {
            if (level.getBlockEntity(deck.above(up)) instanceof Aim9BlockEntity be) {
                be.configure(true, true, 220);
                return deck.above(up);
            }
        }
        throw new AssertionError("The AIM-9 item did not place a rack on the ship's deck");
    }

    @GameTest(template = "empty", batch = "aim9_ship_item_placed", timeoutTicks = 200)
    public static void rackSetOnAShipDeckByHandFiresByItself(GameTestHelper h) {
        var level = h.getLevel();
        var body = rackPlacedOnShip(h, new BlockPos(50, 8, 250), false);
        CruiseMissileProjectile[] target = {null};
        h.runAfterDelay(40, () -> {
            var center = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(
                            level, body.getCenter(), Vec3.ZERO, null)
                    .pos();
            loadFlightChunks(level, BlockPos.containing(center.add(0, 0, 70)));
            target[0] = cruise(level, center.add(-60, 20, 40), new Vec3(1, 0, 0));
            FLIGHT_FIXTURES.add(target[0]);
        });
        h.succeedWhen(() -> {
            h.assertTrue(target[0] != null, "Target spawned");
            var readiness = level.getBlockEntity(body) instanceof Aim9BlockEntity be ? be.readiness() : null;
            h.assertTrue(
                    readiness != Aim9Block.Readiness.SHIP_ABOVE && readiness != Aim9Block.Readiness.WORLD_ABOVE,
                    "Open deck reads as clear, got " + readiness + " " + blocker(level, body));
            var launched = level.getEntitiesOfClass(
                    Aim9Projectile.class,
                    target[0].getBoundingBox().inflate(400),
                    e -> target[0].getUUID().equals(e.targetId()));
            if (launched.isEmpty()) {
                var origin = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(
                                level, body.getCenter(), Vec3.ZERO, null)
                        .pos();
                String why = level.getBlockEntity(body) instanceof Aim9BlockEntity rack
                        ? rack.readiness() + " check=" + Aim9Block.check(level, body, target[0])
                        : "no rack " + level.getBlockState(body);
                h.fail("The hand-placed rack on the ship launched by itself: " + why + " alive="
                        + target[0].isAlive() + " dist="
                        + Math.round(target[0].position().distanceTo(origin))
                        + " " + blocker(level, body));
            }
            launched.forEach(Entity::discard);
            target[0].discard();
        });
    }

    @GameTest(template = "empty", batch = "aim9_ship_roofed", timeoutTicks = 100)
    public static void rackUnderItsOwnSuperstructureSaysSo(GameTestHelper h) {
        var level = h.getLevel();
        var body = rackPlacedOnShip(h, new BlockPos(50, 8, 450), true);
        h.succeedWhen(() -> h.assertTrue(
                level.getBlockEntity(body) instanceof Aim9BlockEntity be
                        && be.readiness() == Aim9Block.Readiness.SHIP_ABOVE,
                "A rack under the bridge wing reports the ship in the way"));
    }

    /** A rack standing on a ship's deck, switched on, with a cruise missile going past. */
    private static void shipRackFires(GameTestHelper h, BlockPos relative, double speed) {
        var level = h.getLevel();
        var body = h.absolutePos(relative);
        clearSkies(level, body);
        rack(h, body).configure(true, true, 220);
        var cells = new ArrayList<BlockPos>(List.of(body.below(), body, body.above()));
        var deck = body.below(2);
        for (var p : BlockPos.betweenClosed(deck.offset(-2, 0, -2), deck.offset(2, 0, 2))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells.add(p.immutable());
        }
        // A deckhouse ahead of the rack, as on a real ship: not over the rack, but in the
        // way of any check that wrongly sweeps the ship's own motion through it.
        for (var p : BlockPos.betweenClosed(deck.offset(2, 1, -1), deck.offset(2, 6, 1))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells.add(p.immutable());
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, body, cells, new BoundingBox3i(deck.offset(-2, 0, -2), deck.offset(2, 6, 2)));
        h.runAfterDelay(4, () -> {
            if (speed > 0) {
                RigidBodyHandle.of(ship).addLinearAndAngularVelocity(new Vector3d(speed * 20, 0, 0), new Vector3d());
            }
        });
        CruiseMissileProjectile[] target = {null};
        h.runAfterDelay(10, () -> {
            var local = ship.getPlot().getCenterBlock();
            var center = ship.logicalPose().transformPosition(local.getCenter());
            loadFlightChunks(level, BlockPos.containing(center.add(0, 0, 70)));
            target[0] = cruise(level, center.add(-60, -30, 40), new Vec3(1, 0, 0));
            FLIGHT_FIXTURES.add(target[0]);
        });
        for (int at = 12; at <= 60; at += 8) {
            int tick = at;
            h.runAfterDelay(at, () -> {
                var plotCenter = ship.getPlot().getCenterBlock();
                BlockPos plotBody = null;
                for (var p : BlockPos.betweenClosed(plotCenter.offset(-6, -6, -6), plotCenter.offset(6, 8, 6))) {
                    if (level.getBlockEntity(p) instanceof Aim9BlockEntity) {
                        plotBody = p.immutable();
                        break;
                    }
                }
                var launchedNow = target[0] == null
                        ? List.<Aim9Projectile>of()
                        : level.getEntitiesOfClass(
                                Aim9Projectile.class,
                                target[0].getBoundingBox().inflate(400),
                                e -> target[0].getUUID().equals(e.targetId()));
                com.cbc_more_content.CBCMoreContent.LOGGER.info(
                        "AIM9 ship diag t={} speed={} shipY={} rack={} {} launched={} target={}",
                        tick,
                        speed,
                        Math.round(ship.logicalPose().transformPosition(plotCenter.getCenter()).y),
                        plotBody,
                        plotBody != null && level.getBlockEntity(plotBody) instanceof Aim9BlockEntity be
                                ? be.readiness() + " enabled=" + be.enabled()
                                        + (target[0] != null
                                                ? " check=" + Aim9Block.check(level, plotBody, target[0])
                                                : "")
                                        + " " + blocker(level, plotBody)
                                : "-",
                        launchedNow.size(),
                        target[0] == null ? "-" : target[0].position() + " alive=" + target[0].isAlive());
            });
        }
        h.succeedWhen(() -> {
            h.assertTrue(target[0] != null, "Target spawned");
            var launched = level.getEntitiesOfClass(
                    Aim9Projectile.class,
                    target[0].getBoundingBox().inflate(400),
                    e -> target[0].getUUID().equals(e.targetId()));
            if (launched.isEmpty()) {
                var plotCenter = ship.getPlot().getCenterBlock();
                BlockPos plotBody = plotCenter;
                for (var p : BlockPos.betweenClosed(plotCenter.offset(-6, -6, -6), plotCenter.offset(6, 8, 6))) {
                    if (level.getBlockEntity(p) instanceof Aim9BlockEntity) {
                        plotBody = p.immutable();
                        break;
                    }
                }
                String why = level.getBlockEntity(plotBody) instanceof Aim9BlockEntity be
                        ? be.readiness() + " enabled=" + be.enabled() + " check="
                                + Aim9Block.check(level, plotBody, target[0])
                        : "no rack at " + plotBody + " " + level.getBlockState(plotBody);
                var origin = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(
                                level, plotBody.getCenter(), Vec3.ZERO, null)
                        .pos();
                h.fail("The rack on the ship launched by itself: " + why + " target alive="
                        + target[0].isAlive() + " eject=" + target[0].isEjecting() + " dist="
                        + Math.round(target[0].position().distanceTo(origin)) + " " + blocker(level, plotBody));
            }
            launched.forEach(Entity::discard);
            target[0].discard();
        });
    }

    /**
     * A small ship with one rack standing on its deck and one hanging under it. Upright, the
     * top one is clear and the hanging one has the deck over it; turned upside down it is
     * the other way round. The rack must judge by the world's up, not the ship's.
     */
    @GameTest(template = "empty", batch = "aim9_ship_flipped", timeoutTicks = 100)
    public static void flippedShipJudgesTheSkyByWorldUp(GameTestHelper h) {
        var level = h.getLevel();
        var center = h.absolutePos(new BlockPos(50, 30, 650));
        clearSkies(level, center);
        level.getChunkSource()
                .addRegionTicket(FIXTURE, new net.minecraft.world.level.ChunkPos(center), 2, center.asLong(), true);
        loadFlightChunks(level, center);
        var cells = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(center.offset(-1, 0, -1), center.offset(1, 0, 1))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells.add(p.immutable());
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(
                level, center, cells, new BoundingBox3i(center.offset(-1, 0, -1), center.offset(1, 0, 1)));
        var deck = ship.getPlot().getCenterBlock();
        var block = (Aim9Block) ModBlocks.AIM9.get();
        BlockPos onTop = deck.above(2);
        BlockPos hanging = deck.below(2);
        block.setPlacedBy(
                level,
                onTop,
                block.defaultBlockState().setValue(Aim9Block.FACING, Direction.UP),
                null,
                ItemStack.EMPTY);
        block.setPlacedBy(
                level,
                hanging,
                block.defaultBlockState().setValue(Aim9Block.FACING, Direction.DOWN),
                null,
                ItemStack.EMPTY);
        h.runAfterDelay(2, () -> {
            var top = Aim9Block.skyCheck(level, onTop);
            var under = Aim9Block.skyCheck(level, hanging);
            h.assertTrue(
                    top == Aim9Block.Readiness.READY && under == Aim9Block.Readiness.SHIP_ABOVE,
                    "Upright: top " + top + ", hanging " + under);
            var at = ship.logicalPose().transformPosition(deck.getCenter());
            RigidBodyHandle.of(ship).teleport(new Vector3d(at.x, at.y, at.z), new Quaterniond().rotateX(Math.PI));
        });
        h.runAfterDelay(1, () -> {
            for (var rack : List.of(onTop, hanging)) {
                if (level.getBlockEntity(rack) instanceof Aim9BlockEntity be) {
                    be.configure(true, true, 220);
                }
            }
        });
        h.runAfterDelay(40, () -> {
            // The racks' own scans, not just the check: they must keep scanning on a turned ship.
            var topBe = (Aim9BlockEntity) level.getBlockEntity(onTop);
            var underBe = (Aim9BlockEntity) level.getBlockEntity(hanging);
            h.assertTrue(
                    topBe.readiness() == Aim9Block.Readiness.SHIP_ABOVE
                            && underBe.readiness() == Aim9Block.Readiness.READY,
                    "Racks report after the flip: top " + topBe.readiness() + ", hanging " + underBe.readiness());
        });
        h.runAfterDelay(5, () -> {
            var top = Aim9Block.skyCheck(level, onTop);
            var under = Aim9Block.skyCheck(level, hanging);
            var worldTop = ship.logicalPose().transformPosition(onTop.getCenter());
            var worldUnder = ship.logicalPose().transformPosition(hanging.getCenter());
            var up = ship.logicalPose().transformNormalInverse(new Vec3(0, 1, 0));
            h.assertTrue(
                    top == Aim9Block.Readiness.SHIP_ABOVE && under == Aim9Block.Readiness.READY,
                    "Upside down: deck-top rack (now at y " + Math.round(worldTop.y) + ") " + top
                            + ", hanging rack (now at y " + Math.round(worldUnder.y) + ") " + under
                            + ", world up in ship space " + up);
        });
        h.runAfterDelay(45, h::succeed);
    }
}
