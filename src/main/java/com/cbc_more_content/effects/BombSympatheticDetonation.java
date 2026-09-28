package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.config.WarnauticsConfig;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Deferred reactions: destroying a charge never recursively explodes it inside another explosion. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class BombSympatheticDetonation {
    private static final int MAX_REACTIONS_PER_TICK = 4;
    private static final TicketType<Long> CHAIN_TICKET =
            TicketType.create("warnautics_chain_detonation", Long::compareTo);
    private static final Map<ServerLevel, PriorityQueue<Reaction>> REACTIONS = new HashMap<>();
    private static final Map<CookoffKey, Object> PLACED_COOKOFFS = new HashMap<>();
    private static final ThreadLocal<Integer> BOMB_BLAST_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static long sequence;

    private BombSympatheticDetonation() {}

    /** The caller has already removed the charge and captured its world-space position. */
    public static void scheduleDestroyedCharge(ServerLevel level, Vec3 worldCenter, Runnable detonation) {
        scheduleDestroyedCharges(level, worldCenter, 1, 2, 5, detonation);
    }

    /** Each surviving charge in a destroyed cassette gets its own fuze and full explosion. */
    public static void scheduleDestroyedCharges(
            ServerLevel level, Vec3 worldCenter, int remaining, Runnable detonation) {
        scheduleDestroyedCharges(level, worldCenter, remaining, 8, 12, detonation);
    }

    private static void scheduleDestroyedCharges(
            ServerLevel level, Vec3 worldCenter, int remaining, int minDelay, int maxDelay, Runnable detonation) {
        if (remaining <= 0) {
            return;
        }
        ChunkPos chunk = new ChunkPos(BlockPos.containing(worldCenter));
        long ticket = sequence++;
        // The original block is gone. Keep its chunk loaded through the short fuze,
        // otherwise an off-screen chain can detonate into an unloaded blast scene.
        level.getChunkSource().addRegionTicket(CHAIN_TICKET, chunk, 2, ticket);
        schedule(
                level,
                minDelay + level.random.nextInt(maxDelay - minDelay + 1),
                () -> {
                    // Count from the actual detonation tick, so an overloaded queue cannot
                    // collapse the remaining cassette into several explosions in one tick.
                    // Acquire the next ticket before this reaction releases its own.
                    scheduleDestroyedCharges(level, worldCenter, remaining - 1, minDelay, maxDelay, detonation);
                    detonation.run();
                },
                () -> level.getChunkSource().removeRegionTicket(CHAIN_TICKET, chunk, 2, ticket));
    }

    private static void schedule(ServerLevel level, int delay, Runnable action, Runnable cleanup) {
        REACTIONS
                .computeIfAbsent(
                        level,
                        ignored -> new PriorityQueue<>(
                                Comparator.comparingLong(Reaction::tick).thenComparingLong(Reaction::order)))
                .add(new Reaction(level.getGameTime() + Math.max(1, delay), sequence++, action, cleanup));
    }

    @SubscribeEvent
    public static void afterLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var queue = REACTIONS.get(level);
        if (queue == null) {
            return;
        }
        int count = 0;
        while (!queue.isEmpty() && queue.peek().tick() <= level.getGameTime() && count < MAX_REACTIONS_PER_TICK) {
            Reaction reaction = queue.remove();
            count++;
            try {
                reaction.action().run();
            } finally {
                reaction.cleanup().run();
            }
        }
        if (queue.isEmpty()) {
            REACTIONS.remove(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var queue = REACTIONS.remove(level);
            if (queue != null) {
                queue.forEach(reaction -> reaction.cleanup().run());
            }
            PLACED_COOKOFFS.keySet().removeIf(key -> key.level() == level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        REACTIONS
                .values()
                .forEach(queue -> queue.forEach(reaction -> reaction.cleanup().run()));
        REACTIONS.clear();
        PLACED_COOKOFFS.clear();
        BOMB_BLAST_DEPTH.remove();
        sequence = 0;
    }

    /** Fire and projectile damage need the same block to remain until its damaged fuze burns down. */
    public static void schedulePlacedBombCookoff(ServerLevel level, BlockPos pos, int minTicks, int maxTicks) {
        BlockState initial = level.getBlockState(pos);
        if (!(initial.getBlock() instanceof DropBombBlock)) {
            return;
        }
        BlockPos anchor = initial.getBlock() instanceof MoabBlock ? MoabBlock.bodyOf(initial, pos) : pos.immutable();
        CookoffKey key = new CookoffKey(level, anchor);
        Object token = new Object();
        if (PLACED_COOKOFFS.putIfAbsent(key, token) != null) {
            return;
        }
        int low = Math.max(1, minTicks);
        int high = Math.max(low, maxTicks);
        schedule(
                level,
                low + level.random.nextInt(high - low + 1),
                () -> {
                    if (!PLACED_COOKOFFS.remove(key, token)) {
                        return;
                    }
                    var current = level.getBlockState(anchor);
                    if (current.is(initial.getBlock())) {
                        DropBombBlock.detonateInPlace(level, anchor, current);
                    }
                },
                () -> {});
    }

    public static void cancelPlacedCookoff(ServerLevel level, BlockPos pos) {
        PLACED_COOKOFFS.remove(new CookoffKey(level, pos));
    }

    /** These legacy switches control airborne ignition, never a block physically destroyed by a blast. */
    public static boolean allowsCookoffFrom(@javax.annotation.Nullable Explosion explosion) {
        return isBombBlastActive() || explosion instanceof WarnauticsExplosion
                ? WarnauticsConfig.friendlyChainDetonation()
                : WarnauticsConfig.externalChainDetonation();
    }

    public static boolean isBombBlastActive() {
        return BOMB_BLAST_DEPTH.get() > 0;
    }

    public static void runBombBlast(Runnable action) {
        BOMB_BLAST_DEPTH.set(BOMB_BLAST_DEPTH.get() + 1);
        try {
            action.run();
        } finally {
            int remaining = BOMB_BLAST_DEPTH.get() - 1;
            if (remaining <= 0) {
                BOMB_BLAST_DEPTH.remove();
            } else {
                BOMB_BLAST_DEPTH.set(remaining);
            }
        }
    }

    private record CookoffKey(ServerLevel level, BlockPos pos) {}

    private record Reaction(long tick, long order, Runnable action, Runnable cleanup) {}
}
