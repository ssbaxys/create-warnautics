package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Delays only cosmetic smoke/spray. Blast damage and chain fuses never enter this queue. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class BlastFxScheduler {
    private static final Map<ServerLevel, PriorityQueue<Job>> QUEUES = new HashMap<>();
    private static long sequence;
    private static final int MAX_QUEUED = 128;
    private static final int JOBS_PER_TICK = 6;

    private BlastFxScheduler() {}

    public static void schedule(ServerLevel level, int delay, Runnable task) {
        var queue = QUEUES.computeIfAbsent(
                level,
                ignored ->
                        new PriorityQueue<>(Comparator.comparingLong(Job::due).thenComparingLong(Job::order)));
        if (queue.size() < MAX_QUEUED) {
            queue.add(new Job(level.getGameTime() + Math.max(1, delay), sequence++, task));
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        for (var entry : QUEUES.entrySet()) {
            long now = entry.getKey().getGameTime();
            var queue = entry.getValue();
            for (int count = 0; count < JOBS_PER_TICK && !queue.isEmpty() && queue.peek().due <= now; count++) {
                Job job = queue.poll();
                // Stale cosmetic bursts should not suddenly appear seconds after the blast.
                if (now - job.due <= 20) {
                    job.action.run();
                }
            }
        }
        QUEUES.values().removeIf(PriorityQueue::isEmpty);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            QUEUES.remove(level);
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent event) {
        QUEUES.clear();
    }

    private record Job(long due, long order, Runnable action) {}
}
