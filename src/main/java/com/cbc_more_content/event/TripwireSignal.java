package com.cbc_more_content.event;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import com.cbc_more_content.CBCMoreContent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Posts left live by a tripwire that has just been broken.
 * <p>
 * The wire itself is not a redstone component — it is an entity, and entities cannot
 * carry a signal. What it can do is mark the two posts it was tied to, and a small mixin
 * reports full power from any block standing on a marked position, in every direction.
 * That is the whole point of the wire snapping rather than exploding: whatever is wired
 * to those posts decides what happens next.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class TripwireSignal {
    /** A pulse long enough for any repeater or contraption to latch it. */
    public static final int PULSE_TICKS = 20;

    private static final Map<Level, Map<BlockPos, Long>> LIVE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private TripwireSignal() {
    }

    /** Marks a post live for {@link #PULSE_TICKS}, and tells its neighbours about it. */
    public static void pulse(ServerLevel level, BlockPos post) {
        LIVE.computeIfAbsent(level, l -> new HashMap<>())
                .put(post.immutable(), level.getGameTime() + PULSE_TICKS);
        level.updateNeighborsAt(post, level.getBlockState(post).getBlock());
    }

    /** Redstone power at this position, 15 while the pulse lasts and 0 otherwise. */
    public static int strength(BlockGetter getter, BlockPos pos) {
        if (!(getter instanceof Level level)) {
            return 0;
        }
        Map<BlockPos, Long> live = LIVE.get(level);
        if (live == null || live.isEmpty()) {
            return 0;
        }
        Long until = live.get(pos);
        return until != null && level.getGameTime() < until ? 15 : 0;
    }

    /** Sweeps out pulses that have run out, updating neighbours as each one drops. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, Long> live = LIVE.get(level);
        if (live == null || live.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<BlockPos, Long>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            if (now < entry.getValue()) {
                continue;
            }
            BlockPos post = entry.getKey();
            it.remove();
            if (level.isLoaded(post)) {
                level.updateNeighborsAt(post, level.getBlockState(post).getBlock());
            }
        }
    }
}
