package com.cbc_more_content.compat;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Watching hulls move, for the designator.
 * <p>
 * Speed is measured rather than read: the client sub-level keeps its networked velocity
 * private, and the bounding box it does expose is enough. Sampling the centre once a
 * tick and differencing it gives blocks per tick directly, and costs nothing beyond the
 * handful of hulls that exist.
 */
public final class SableTrackCompat {
    /** Below this a hull is drifting or parked and the designator ignores it. */
    public static final double MOVING = 0.05D;
    /** At or above this it reads as running, and the marker goes hot. */
    public static final double FAST = 0.30D;

    private static final Map<UUID, Vec3> PREVIOUS = new HashMap<>();

    private SableTrackCompat() {}

    /** A moving hull as the designator sees it. */
    public record Track(UUID id, Vec3 centre, double size, double speed) {
        public boolean fast() {
            return this.speed >= FAST;
        }
    }

    /**
     * Samples every hull in the client level and returns the ones that are under way.
     * <p>
     * Must be called exactly once a tick: the speed of each hull is the distance its
     * centre has travelled since the previous call.
     */
    public static List<Track> sampleMoving(ClientLevel level) {
        List<Track> moving = new ArrayList<>();
        try {
            ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                PREVIOUS.clear();
                return moving;
            }
            Map<UUID, Vec3> seen = new HashMap<>();
            for (ClientSubLevel sub : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                BoundingBox3dc box = sub.boundingBox();
                UUID id = sub.getUniqueId();
                if (box == null || id == null) {
                    continue;
                }
                Vec3 centre = new Vec3(
                        (box.minX() + box.maxX()) * 0.5D,
                        (box.minY() + box.maxY()) * 0.5D,
                        (box.minZ() + box.maxZ()) * 0.5D);
                seen.put(id, centre);

                Vec3 last = PREVIOUS.get(id);
                if (last == null) {
                    continue;
                }
                double speed = last.distanceTo(centre);
                if (speed < MOVING) {
                    continue;
                }
                double size =
                        Math.max(Math.max(box.maxX() - box.minX(), box.maxY() - box.minY()), box.maxZ() - box.minZ());
                moving.add(new Track(id, centre, size, speed));
            }
            PREVIOUS.keySet().retainAll(seen.keySet());
            PREVIOUS.putAll(seen);
        } catch (Throwable ignored) {
            PREVIOUS.clear();
        }
        return moving;
    }

    /** Drops the sampling history, so a rejoin does not read as a hull teleporting. */
    public static void forget() {
        PREVIOUS.clear();
    }

    /**
     * Runtime id of the hull the client locked, or -1 if it has gone.
     * <p>
     * The client only ever names a hull by its uuid; runtime ids are a server concept
     * and are what the missile actually follows.
     */
    public static int runtimeIdOf(ServerLevel level, UUID id) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return -1;
            }
            SubLevel sub = container.getSubLevel(id);
            return sub instanceof ServerSubLevel server && !server.isRemoved() ? server.getRuntimeId() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** World-space centre of a hull by uuid, or null once it is gone. */
    @Nullable
    public static Vec3 centreOf(ServerLevel level, UUID id) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return null;
            }
            SubLevel sub = container.getSubLevel(id);
            if (sub == null || sub.isRemoved()) {
                return null;
            }
            BoundingBox3dc box = sub.boundingBox();
            return box == null
                    ? null
                    : new Vec3(
                            (box.minX() + box.maxX()) * 0.5D,
                            (box.minY() + box.maxY()) * 0.5D,
                            (box.minZ() + box.maxZ()) * 0.5D);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
