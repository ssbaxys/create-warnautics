package com.cbc_more_content.munitions;

import com.cbc_more_content.CBCMoreContent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps the ground under a munition in flight loaded and ticking, so a bomb, rocket or
 * torpedo finishes its flight and goes off where it lands even with nobody near it, instead
 * of freezing at the edge of the loaded area and going off all at once when someone walks up.
 * <p>
 * The munition renews its ticket every tick it ticks: its own chunk and the ring round it,
 * and the ground a second or so ahead along its flight, so a chunk still to be generated is
 * asked for early. A fast munition can still reach a chunk whose entities are not loaded
 * yet, and it stops there until they are; while it is stopped it cannot renew anything, so
 * the flights are also kept track of here, and the ticket of a stopped munition is held for
 * it until it moves on, rather than lapsing and unloading it with its chunk. A flight is
 * forgotten once its munition is gone, or after a long stall.
 * <p>
 * The tickets are never forced-ticking ones: NeoForge does not purge those when they time
 * out, and every flight would have left its trail of chunks ticking for good.
 */
public final class FlightTicket {
    private static final TicketType<UUID> MUNITION = TicketType.create("warnautics_munition", UUID::compareTo, 40);
    private static final TicketType<BlockPos> LAUNCHER =
            TicketType.create("warnautics_launcher", Vec3iOrder::compare, 60);
    /** The launcher's own chunk ticking is enough: it does not move. */
    private static final int DISTANCE = 2;
    /** A munition's chunk and every chunk around it. */
    private static final int FLIGHT_DISTANCE = 4;
    /** How far ahead along its flight a munition asks for the ground to be ready. */
    private static final double LOOK_AHEAD_TICKS = 24.0D;

    /** Entity-ticking on the chunk itself and the ring round it. */
    private static final int AHEAD_DISTANCE = 3;
    /** A munition stalled this long, waiting for ground that never comes, is let go. */
    private static final long MAX_STALL_TICKS = 600;

    private static final Map<UUID, Flight> FLIGHTS = new HashMap<>();

    private FlightTicket() {}

    private static final class Flight {
        final ServerLevel level;
        Vec3 position;
        Vec3 velocity;
        long lastSeen;

        Flight(ServerLevel level) {
            this.level = level;
        }
    }

    /** Called from the munition's server tick. */
    public static void keepLoaded(ServerLevel level, Entity munition) {
        Flight flight = FLIGHTS.get(munition.getUUID());
        if (flight == null || flight.level != level) {
            flight = new Flight(level);
            FLIGHTS.put(munition.getUUID(), flight);
        }
        flight.position = munition.position();
        flight.velocity = munition.getDeltaMovement();
        flight.lastSeen = level.getGameTime();
        hold(level, munition.getUUID(), flight.position, flight.velocity);
    }

    private static void hold(ServerLevel level, UUID id, Vec3 position, Vec3 velocity) {
        ChunkPos here = new ChunkPos(BlockPos.containing(position));
        level.getChunkSource().addRegionTicket(MUNITION, here, FLIGHT_DISTANCE, id);
        if (velocity.horizontalDistanceSqr() > 0.25D) {
            // Every chunk along the next second or so of flight, not only the end of it: a
            // gap in between is exactly where a fast munition would stop.
            ChunkPos previous = here;
            for (double t = 2.0D; t <= LOOK_AHEAD_TICKS; t += 2.0D) {
                ChunkPos ahead = new ChunkPos(BlockPos.containing(position.add(velocity.scale(t))));
                if (!ahead.equals(previous)) {
                    level.getChunkSource().addRegionTicket(MUNITION, ahead, AHEAD_DISTANCE, id);
                    previous = ahead;
                }
            }
        }
    }

    /** A launcher in the middle of a salvo: its block entity has to keep ticking to finish it. */
    public static void keepLoaded(ServerLevel level, BlockPos launcher) {
        level.getChunkSource().addRegionTicket(LAUNCHER, new ChunkPos(launcher), DISTANCE, launcher.immutable());
    }

    private static final class Vec3iOrder {
        static int compare(BlockPos a, BlockPos b) {
            return a.compareTo(b);
        }
    }

    @EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
    public static final class Events {
        private Events() {}

        /** Holds the ground for munitions that have stopped, and forgets the ones that are gone. */
        @SubscribeEvent
        public static void tick(ServerTickEvent.Post event) {
            if (FLIGHTS.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<UUID, Flight>> it = FLIGHTS.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Flight flight = entry.getValue();
                long now = flight.level.getGameTime();
                if (now - flight.lastSeen < 1) {
                    continue;
                }
                Entity munition = flight.level.getEntity(entry.getKey());
                if (munition != null) {
                    if (munition.isRemoved()) {
                        it.remove();
                    } else if (now - flight.lastSeen > MAX_STALL_TICKS) {
                        it.remove();
                    } else {
                        hold(flight.level, entry.getKey(), munition.position(), munition.getDeltaMovement());
                    }
                    continue;
                }
                BlockPos last = BlockPos.containing(flight.position);
                // Not to be found where entities are ticking: it went off, or was removed.
                // Where they are not, it is waiting for its chunk; keep holding the ground.
                if (flight.level.isPositionEntityTicking(last) || now - flight.lastSeen > MAX_STALL_TICKS) {
                    it.remove();
                } else {
                    hold(flight.level, entry.getKey(), flight.position, flight.velocity);
                }
            }
        }

        @SubscribeEvent
        public static void stopped(ServerStoppedEvent event) {
            FLIGHTS.clear();
        }
    }
}
