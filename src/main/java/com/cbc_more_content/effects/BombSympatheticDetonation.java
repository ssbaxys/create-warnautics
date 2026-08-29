package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.munitions.DropBombProjectile;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Staggered sympathetic cook-off for placed and flying drop bombs.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class BombSympatheticDetonation {
    private static final double EDGE_MARGIN = 2.25D;
    private static final int MIN_COOKOFF_TICKS = 28;
    private static final int MAX_COOKOFF_TICKS = 92;
    private static final Set<PendingCookoff> PENDING = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Integer> BOMB_BLAST_DEPTH = ThreadLocal.withInitial(() -> 0);

    private BombSympatheticDetonation() {}

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Explosion explosion = event.getExplosion();
        if (isBombBlastActive()) {
            // Warnautics applies blast damage and knockback itself, in
            // BombExplosionHandler#applyBlastToEntities, so that the world path and the
            // Sable path share one model. Clearing the list here stops the vanilla pass
            // from also running: it gates entities on the *block* radius and on CBC's
            // damage calculator, which is why bombs could leave a player standing next
            // to the crater completely unhurt.
            event.getAffectedEntities().clear();
            if (!allowsCookoffFrom(explosion)) {
                event.getAffectedBlocks()
                        .removeIf(pos -> level.getBlockState(pos).getBlock() instanceof DropBombBlock);
            }
            return;
        }
        if (!allowsCookoffFrom(explosion)) {
            event.getAffectedBlocks().removeIf(pos -> level.getBlockState(pos).getBlock() instanceof DropBombBlock);
            event.getAffectedEntities().removeIf(entity -> entity instanceof DropBombProjectile);
            return;
        }
        Vec3 center = explosion.center();
        double radius = Math.max(explosion.radius(), 1.0F);
        double chainRadius = radius + EDGE_MARGIN;
        double chainRadiusSqr = chainRadius * chainRadius;
        Set<BlockPos> bombsToCook = new HashSet<>();

        // Preserve bombs hit by the explosion so their damaged fuzes can cook.
        event.getAffectedBlocks().removeIf(pos -> {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DropBombBlock)) {
                return false;
            }
            if (isActiveCassette(state)) {
                return true;
            }
            if (pos.distToCenterSqr(center.x, center.y, center.z) <= chainRadiusSqr) {
                bombsToCook.add(pos.immutable());
            }
            return true;
        });

        // Include nearby bombs only if no solid block shields the fuze.
        int r = Mth.ceil(chainRadius);
        BlockPos origin = BlockPos.containing(center);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (pos.distToCenterSqr(center.x, center.y, center.z) > chainRadiusSqr) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DropBombBlock
                    && !isActiveCassette(state)
                    && hasDirectBlastPath(level, center, pos, explosion)) {
                bombsToCook.add(pos.immutable());
            }
        }

        // Flying bombs already exposed by the explosion cook immediately.
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof DropBombProjectile bomb) {
                bomb.sympatheticDetonate();
            }
        }

        AABB area = new AABB(center, center).inflate(chainRadius);
        for (DropBombProjectile bomb : level.getEntitiesOfClass(DropBombProjectile.class, area, Entity::isAlive)) {
            if (bomb.distanceToSqr(center) <= chainRadiusSqr
                    && hasDirectBlastPath(level, center, bomb.blockPosition(), explosion)) {
                bomb.sympatheticDetonate();
            }
        }

        for (BlockPos pos : bombsToCook) {
            schedulePlacedBombCookoff(level, pos, MIN_COOKOFF_TICKS, MAX_COOKOFF_TICKS);
        }
    }

    public static void schedulePlacedBombCookoff(ServerLevel level, BlockPos pos, int minTicks, int maxTicks) {
        BlockState initial = level.getBlockState(pos);
        BombSize fallback = initial.getBlock() instanceof DropBombBlock bomb ? bomb.getBombSize() : null;
        scheduleCookoff(level, pos, fallback, minTicks, maxTicks);
    }

    public static boolean isBombBlastActive() {
        return BOMB_BLAST_DEPTH.get() > 0;
    }

    /**
     * Single decision point for "may this explosion ignite a Warnautics bomb?".
     * A Warnautics blast is suppressed unless the pack explicitly opts back into
     * friendly chaining; anything else obeys the external cook-off switch.
     */
    public static boolean allowsCookoffFrom(@javax.annotation.Nullable Explosion explosion) {
        if (isBombBlastActive()) {
            return WarnauticsConfig.friendlyChainDetonation();
        }
        return WarnauticsConfig.externalChainDetonation();
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

    public static void scheduleDetachedBombCookoff(
            ServerLevel level, BlockPos pos, BombSize size, int minTicks, int maxTicks) {
        scheduleCookoff(level, pos, size, minTicks, maxTicks);
    }

    private static void scheduleCookoff(
            ServerLevel level, BlockPos pos, BombSize fallbackSize, int minTicks, int maxTicks) {
        BlockPos cooked = pos.immutable();
        PendingCookoff pending = new PendingCookoff(level, cooked.asLong());
        if (!PENDING.add(pending)) {
            return;
        }

        int low = Math.max(1, minTicks);
        int high = Math.max(low, maxTicks);
        int delay = low + level.random.nextInt(high - low + 1);
        int when = level.getServer().getTickCount() + delay;
        level.getServer().tell(new TickTask(when, () -> {
            try {
                BlockState state = level.getBlockState(cooked);
                if (state.getBlock() instanceof DropBombBlock && !isActiveCassette(state)) {
                    DropBombBlock.detonateInPlace(level, cooked, state);
                } else if (fallbackSize != null) {
                    DropBombBlock.detonateDetached(level, cooked, fallbackSize);
                }
            } finally {
                PENDING.remove(pending);
            }
        }));
    }

    private static boolean isActiveCassette(BlockState state) {
        return state.hasProperty(DropBombBlock.POWERED) && state.getValue(DropBombBlock.POWERED);
    }

    private static boolean hasDirectBlastPath(ServerLevel level, Vec3 center, BlockPos targetPos, Explosion explosion) {
        BlockHitResult hit = level.clip(new ClipContext(
                center,
                Vec3.atCenterOf(targetPos),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                explosion.getDirectSourceEntity()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(targetPos);
    }

    private record PendingCookoff(ServerLevel level, long pos) {}
}
