package com.cbc_more_content.siren;

import com.cbc_more_content.CBCMoreContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Where things have just gone off, so a siren can ask rather than watch.
 * <p>
 * Inverted on purpose. A post cannot see an explosion — by the time it looks, the thing
 * that exploded is gone — and having every siren sweep for detonations would be work
 * repeated per post. A blast is one event, so it writes itself down once here, and the
 * posts read the list on the look they were already doing.
 * <p>
 * Anything that reaches a vanilla explosion lands here on its own, which covers Big
 * Cannons shells and this mod's own warheads alike; the mod's handlers also call in
 * directly, so a blast that never builds an {@code Explosion} still counts.
 */
@EventBusSubscriber(
        modid = CBCMoreContent.MOD_ID,
        value = {Dist.CLIENT, Dist.DEDICATED_SERVER})
public final class BlastLog {
    /** How long a blast stays worth reacting to. Comfortably over a siren's look-round. */
    private static final int MEMORY_TICKS = 40;
    /** A cap, so a bombing run cannot grow the list without bound between sweeps. */
    private static final int MAX_ENTRIES = 64;

    private static final Map<Level, List<Blast>> BY_LEVEL = new WeakHashMap<>();

    private BlastLog() {}

    public record Blast(Vec3 at, long tick) {}

    /** Called by the mod's own handlers for blasts that never build an explosion. */
    public static void record(Level level, Vec3 at) {
        if (level == null || level.isClientSide) {
            return;
        }
        synchronized (BY_LEVEL) {
            List<Blast> blasts = BY_LEVEL.computeIfAbsent(level, unused -> new ArrayList<>());
            long now = level.getGameTime();
            blasts.removeIf(blast -> now - blast.tick() > MEMORY_TICKS);
            if (blasts.size() < MAX_ENTRIES) {
                blasts.add(new Blast(at, now));
            }
        }
    }

    /** Whether anything has gone off within {@code radius} of a point lately. */
    public static boolean blastNear(Level level, Vec3 of, double radius) {
        List<Blast> blasts;
        synchronized (BY_LEVEL) {
            blasts = BY_LEVEL.get(level);
            if (blasts == null || blasts.isEmpty()) {
                return false;
            }
            blasts = List.copyOf(blasts);
        }
        long now = level.getGameTime();
        double radiusSqr = radius * radius;
        for (Blast blast : blasts) {
            if (now - blast.tick() <= MEMORY_TICKS && blast.at().distanceToSqr(of) <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        record(event.getLevel(), event.getExplosion().center());
    }
}
