package com.cbc_more_content.compat.sable;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.LandMineBlock;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Deduplicates collision callbacks and detonates after Rapier has completed the
 * current sub-step. This removes the old one-game-tick latency without changing
 * plot blocks from inside Sable's contact solver.
 */
public final class SableCollisionDetonationQueue {
    private static final Map<Key, Kind> PENDING = new LinkedHashMap<>();
    private static boolean registered;

    private SableCollisionDetonationQueue() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(SableCollisionDetonationQueue::onPostPhysicsTick);
        CBCMoreContent.LOGGER.info("Sable same-tick bomb collision queue enabled");
    }

    public static void queueBomb(ServerLevel level, BlockPos pos) {
        queue(level, pos, Kind.BOMB);
    }

    public static void queueMine(ServerLevel level, BlockPos pos) {
        queue(level, pos, Kind.MINE);
    }

    private static synchronized void queue(ServerLevel level, BlockPos pos, Kind kind) {
        if (level == null || pos == null) {
            return;
        }
        PENDING.putIfAbsent(new Key(level, pos.asLong()), kind);
    }

    private static void onPostPhysicsTick(ForgeSablePostPhysicsTickEvent event) {
        ServerLevel level = event.getPhysicsSystem().getLevel();
        if (level == null) {
            return;
        }

        Map<Key, Kind> ready = new LinkedHashMap<>();
        synchronized (SableCollisionDetonationQueue.class) {
            Iterator<Map.Entry<Key, Kind>> iterator = PENDING.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Key, Kind> entry = iterator.next();
                if (entry.getKey().level == level) {
                    ready.put(entry.getKey(), entry.getValue());
                    iterator.remove();
                }
            }
        }

        for (Map.Entry<Key, Kind> entry : ready.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey().pos);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (entry.getValue() == Kind.BOMB && state.getBlock() instanceof DropBombBlock) {
                DropBombBlock.detonateInPlace(level, pos, state);
            } else if (entry.getValue() == Kind.MINE && state.getBlock() instanceof LandMineBlock) {
                LandMineBlock.tryVehicleDetonate(level, pos);
            }
        }
    }

    private enum Kind {
        BOMB,
        MINE
    }

    private record Key(ServerLevel level, long pos) {}
}
