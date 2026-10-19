package com.cbc_more_content.bombsight;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Where a released bomb comes down, worked out with the same per-tick step CBC moves its
 * projectiles by: each tick the velocity loses its drag share and gains gravity, and the
 * bomb moves by the mean of its old and new velocity. Matched against a real bomb's
 * recorded flight, it agrees to well under a block.
 */
public final class BombsightBallistics {
    /** Longest fall worth tracing: three minutes is far beyond any aircraft's altitude. */
    private static final int MAX_TICKS = 3600;

    private BombsightBallistics() {}

    /**
     * @param impact where it comes down, or the last traced point if it never did
     * @param ticks  how long it falls, in ticks
     * @param landed false when the fall ran into ground that is not loaded, or never ended
     */
    public record Fall(Vec3 impact, int ticks, boolean landed) {}

    public static Fall trace(
            ServerLevel level, Vec3 start, Vec3 velocity, double gravity, double drag, boolean quadratic) {
        Vec3 pos = start;
        Vec3 vel = velocity;
        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double speed = vel.length();
            double dragForce = speed < 1.0E-9 ? 0 : Math.min(drag * speed * (quadratic ? speed : 1), speed);
            Vec3 next = speed < 1.0E-9 ? vel : vel.subtract(vel.scale(dragForce / speed));
            next = next.add(0, gravity, 0);
            Vec3 to = pos.add(vel.add(next).scale(0.5));
            if (!level.isLoaded(BlockPos.containing(to)) || to.y < level.getMinBuildHeight()) {
                return new Fall(pos, tick, false);
            }
            Vec3 hit = clip(level, pos, to);
            if (hit != null) {
                return new Fall(hit, tick, true);
            }
            pos = to;
            vel = next;
        }
        return new Fall(pos, MAX_TICKS, false);
    }

    @Nullable
    private static Vec3 clip(ServerLevel level, Vec3 from, Vec3 to) {
        var result = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, CollisionContext.empty()));
        return result.getType() == HitResult.Type.MISS ? null : result.getLocation();
    }
}
