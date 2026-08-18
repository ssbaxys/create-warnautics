package com.cbc_more_content.compat.sable;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.cbc_more_content.block.LandMineBlock;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sable hull / plot contact with a ground (or ship-placed) land mine.
 * Large mines arm for vehicle contact; small mines ignore this path.
 */
public final class LandMineSubLevelImpactCallback implements BlockSubLevelCollisionCallback {
    public static final LandMineSubLevelImpactCallback INSTANCE = new LandMineSubLevelImpactCallback();

    /** Any meaningful contact (~1 m/s) — mines are pressure devices, not impact fuzes. */
    private static final double TRIGGER_VELOCITY = 1.0D;

    private LandMineSubLevelImpactCallback() {
    }

    @Override
    public CollisionResult sable$onCollision(
            BlockPos hitBlockPos,
            @Nullable BlockPos otherHitBlockPos,
            Vector3d impactPosition,
            double impactVelocity) {
        if (impactVelocity * impactVelocity < TRIGGER_VELOCITY * TRIGGER_VELOCITY) {
            return CollisionResult.NONE;
        }

        SubLevelPhysicsSystem system;
        try {
            // Sable 2.x throws when queried outside an active physics step.
            system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        } catch (IllegalStateException ignored) {
            return CollisionResult.NONE;
        }
        ServerLevel level = system.getLevel();
        if (level == null || level.isClientSide) {
            return CollisionResult.NONE;
        }

        BlockState state = level.getBlockState(hitBlockPos);
        if (!(state.getBlock() instanceof LandMineBlock mine) || !mine.getMineType().vehicleTrigger) {
            return CollisionResult.NONE;
        }
        if (!state.getValue(LandMineBlock.ARMED)) {
            return CollisionResult.NONE;
        }

        SableCollisionDetonationQueue.queueMine(level, hitBlockPos);
        return new CollisionResult(JOMLConversion.ZERO, true);
    }
}
