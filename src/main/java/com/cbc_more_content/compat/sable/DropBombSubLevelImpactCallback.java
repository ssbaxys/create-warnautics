package com.cbc_more_content.compat.sable;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.cbc_more_content.block.DropBombBlock;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * When a drop bomb is part of a Sable sub-level and that body hits another sub-level
 * or the normal world hard enough, cook off in place (same idea as CBC fuzed shells).
 */
public final class DropBombSubLevelImpactCallback implements BlockSubLevelCollisionCallback {
    public static final DropBombSubLevelImpactCallback INSTANCE = new DropBombSubLevelImpactCallback();

    /** Mounted payloads should only cook off in a genuinely hard carrier impact. */
    private static final double CARRIER_TRIGGER_VELOCITY = 4.0D;
    /** A loose physics bomb behaves like its released projectile on the first real hit. */
    private static final double LOOSE_BOMB_TRIGGER_VELOCITY = 0.5D;

    private DropBombSubLevelImpactCallback() {
    }

    @Override
    public CollisionResult sable$onCollision(
            BlockPos hitBlockPos,
            @Nullable BlockPos otherHitBlockPos,
            Vector3d impactPosition,
            double impactVelocity) {
        SubLevelPhysicsSystem system;
        try {
            system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        } catch (IllegalStateException ignored) {
            return CollisionResult.NONE;
        }
        if (system == null) {
            return CollisionResult.NONE;
        }
        ServerLevel level = system.getLevel();
        if (level == null || level.isClientSide) {
            return CollisionResult.NONE;
        }

        BlockState state = level.getBlockState(hitBlockPos);
        if (!(state.getBlock() instanceof DropBombBlock)) {
            return CollisionResult.NONE;
        }
        double triggerVelocity = triggerVelocity(level, hitBlockPos, state);
        if (impactVelocity * impactVelocity < triggerVelocity * triggerVelocity) {
            return CollisionResult.NONE;
        }

        // Flush after the current Sable physics sub-step. This is still the same
        // game tick, but avoids mutating plot storage while Rapier is resolving contact.
        SableCollisionDetonationQueue.queueBomb(level, hitBlockPos);

        return new CollisionResult(JOMLConversion.ZERO, true);
    }

    /**
     * Physics-gun throws normally create a body consisting mostly (often entirely)
     * of the bomb. Those bodies need projectile-like impact sensitivity. A bomb bay
     * mounted in a much heavier aircraft keeps the old hard-impact threshold so an
     * ordinary landing cannot ignite the payload.
     */
    private static double triggerVelocity(ServerLevel level, BlockPos pos, BlockState state) {
        SubLevel containing = Sable.HELPER.getContaining(level, pos);
        if (!(containing instanceof ServerSubLevel subLevel)) {
            return CARRIER_TRIGGER_VELOCITY;
        }

        double bombMass = PhysicsBlockPropertyHelper.getMass(level, pos, state);
        double bodyMass = subLevel.getMassTracker().getMass();
        if (Double.isFinite(bombMass)
                && Double.isFinite(bodyMass)
                && bombMass > 0.0D
                && bodyMass > 0.0D
                && bombMass * 2.0D >= bodyMass) {
            return LOOSE_BOMB_TRIGGER_VELOCITY;
        }
        return CARRIER_TRIGGER_VELOCITY;
    }
}
