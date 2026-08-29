package com.cbc_more_content.compat;

import com.cbc_more_content.CBCMoreContent;
import java.lang.reflect.Method;
import java.util.Collection;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Create Radar bridge, over its published tracking API.
 * <p>
 * Reached by reflection rather than by a compile dependency, because Radar is optional
 * and only ships from source. The surface used here is small and public — the
 * {@code RadarSource} interface ({@code getContacts}, {@code isRunning}, {@code getRange})
 * and {@code RadarContact} ({@code getId}, {@code getPosition}, {@code getVelocity}) — so
 * this is a real contract and not a guess at internals.
 */
public final class RadarCompat {
    public static final String MOD_ID = "create_radar";
    private static final String SOURCE_CLASS = "com.happysg.radar.api.tracking.RadarSource";

    private static boolean resolved;

    @Nullable
    private static Class<?> sourceClass;

    @Nullable
    private static Method getContacts;

    @Nullable
    private static Method isRunning;

    @Nullable
    private static Method contactId;

    @Nullable
    private static Method contactPosition;

    @Nullable
    private static Method contactVelocity;

    private RadarCompat() {}

    public static boolean loaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /** One track as the missile sees it. */
    public record Contact(String id, Vec3 position, Vec3 velocity) {}

    /** True if this block entity is something Radar considers a source of tracks. */
    public static boolean isController(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null || !resolve()) {
            return false;
        }
        return sourceClass != null && sourceClass.isInstance(blockEntity);
    }

    /**
     * True for any Create Radar block, whether or not it is a valid pairing target.
     * <p>
     * The mod's Network Controller is the block its own tutorials point players at, but
     * it only ever hands its picture to the mod's own weapon-network wiring — it does not
     * implement {@code RadarSource}, so there is no published contract for a third-party
     * mod to read a track off it, only private fields that would break on every update.
     * This exists so a click on it can be told apart from a click on a real radar dish,
     * rather than being read as a random block and silently ignored.
     */
    public static boolean isRadarModBlock(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null || !loaded()) {
            return false;
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        return key != null && MOD_ID.equals(key.getNamespace());
    }

    /**
     * The track worth intercepting, or null when the network is dark, empty or looking
     * at nothing in range. Everything is re-read each call: a radar picture is only ever
     * a snapshot.
     * <p>
     * Scored on speed first and range second, because an interceptor exists for the
     * thing that is moving. Picking purely by distance sent missiles at whatever drifted
     * closest, which on a busy map is a parked plot rather than the hull bearing down.
     */
    @Nullable
    public static Contact bestContact(
            Level level, BlockPos controller, Vec3 from, com.cbc_more_content.radar.InterceptSettings settings) {
        Contact best = null;
        double bestScore = -Double.MAX_VALUE;
        double maxRange = settings.maxRange();
        double rangeSqr = maxRange * maxRange;
        for (Contact contact : contacts(level, controller)) {
            double distanceSqr = contact.position().distanceToSqr(from);
            if (distanceSqr > rangeSqr) {
                continue;
            }
            if (contact.velocity().length() < settings.minSpeed()) {
                continue;
            }
            if (settings.hullsOnly() && !SableDropCompat.isInsideSubLevel(level, contact.position())) {
                continue;
            }
            // Blocks per tick, weighted heavily, less a mild penalty for being far off.
            double score = contact.velocity().length() * 40.0D - Math.sqrt(distanceSqr) / maxRange;
            if (score > bestScore) {
                bestScore = score;
                best = contact;
            }
        }
        return best;
    }

    /** A specific track by id, so a missile keeps chasing the same thing. */
    @Nullable
    public static Contact contactById(Level level, BlockPos controller, String id) {
        for (Contact contact : contacts(level, controller)) {
            if (contact.id().equals(id)) {
                return contact;
            }
        }
        return null;
    }

    /**
     * How far around a bound block to look for the actual radars.
     * <p>
     * The block players are told to build their network around is the Network Controller,
     * which is not itself a radar and publishes nothing a third-party mod can read. The
     * dishes wired into it stand near it, so binding to the controller and then sweeping
     * its surroundings for real {@code RadarSource} block entities is what makes the
     * obvious thing to click actually work.
     */
    private static final int SOURCE_SCAN_RADIUS = 48;

    private static Iterable<Contact> contacts(Level level, BlockPos controller) {
        if (!loaded() || !resolve() || !level.isLoaded(controller)) {
            return java.util.List.of();
        }
        java.util.List<Contact> out = new java.util.ArrayList<>();
        for (BlockEntity source : sourcesNear(level, controller)) {
            readContacts(source, out);
        }
        return out;
    }

    /** The radar block entities a bound position speaks for. */
    private static java.util.List<BlockEntity> sourcesNear(Level level, BlockPos controller) {
        BlockEntity bound = level.getBlockEntity(controller);
        if (isController(bound)) {
            return java.util.List.of(bound);
        }
        if (!isRadarModBlock(bound)) {
            return java.util.List.of();
        }

        java.util.List<BlockEntity> found = new java.util.ArrayList<>();
        int minChunkX = (controller.getX() - SOURCE_SCAN_RADIUS) >> 4;
        int maxChunkX = (controller.getX() + SOURCE_SCAN_RADIUS) >> 4;
        int minChunkZ = (controller.getZ() - SOURCE_SCAN_RADIUS) >> 4;
        int maxChunkZ = (controller.getZ() + SOURCE_SCAN_RADIUS) >> 4;
        int reachSqr = SOURCE_SCAN_RADIUS * SOURCE_SCAN_RADIUS;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (BlockEntity candidate :
                        level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (isController(candidate) && candidate.getBlockPos().distSqr(controller) <= reachSqr) {
                        found.add(candidate);
                    }
                }
            }
        }
        return found;
    }

    private static void readContacts(BlockEntity source, java.util.List<Contact> out) {
        try {
            if (!(Boolean) isRunning.invoke(source)) {
                return;
            }
            Collection<?> raw = (Collection<?>) getContacts.invoke(source);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            for (Object track : raw) {
                if (track == null) {
                    continue;
                }
                Object id = contactId.invoke(track);
                Object position = contactPosition.invoke(track);
                Object velocity = contactVelocity.invoke(track);
                if (id instanceof String name && position instanceof Vec3 at) {
                    out.add(new Contact(name, at, velocity instanceof Vec3 moving ? moving : Vec3.ZERO));
                }
            }
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.debug("Radar contact read failed: {}", t.toString());
        }
    }

    private static boolean resolve() {
        if (resolved) {
            return sourceClass != null;
        }
        resolved = true;
        if (!loaded()) {
            return false;
        }
        try {
            Class<?> source = Class.forName(SOURCE_CLASS);
            Class<?> contact = Class.forName("com.happysg.radar.api.tracking.RadarContact");
            getContacts = source.getMethod("getContacts");
            isRunning = source.getMethod("isRunning");
            contactId = contact.getMethod("getId");
            contactPosition = contact.getMethod("getPosition");
            contactVelocity = contact.getMethod("getVelocity");
            sourceClass = source;
            return true;
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.info(
                    "Create Radar is present but its tracking API did not resolve; "
                            + "intercept guidance stays inert. {}",
                    t.toString());
            sourceClass = null;
            return false;
        }
    }
}
