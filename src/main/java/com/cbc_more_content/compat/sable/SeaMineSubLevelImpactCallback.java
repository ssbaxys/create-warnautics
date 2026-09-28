package com.cbc_more_content.compat.sable;

import com.cbc_more_content.block.SeaMineBlockEntity;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

/** Record contact here; block removal must wait until the native physics step finishes. */
public final class SeaMineSubLevelImpactCallback implements BlockSubLevelCollisionCallback {
    public static final SeaMineSubLevelImpactCallback INSTANCE = new SeaMineSubLevelImpactCallback();

    private SeaMineSubLevelImpactCallback() {}

    @Override
    public CollisionResult sable$onCollision(
            BlockPos hitBlockPos, @Nullable BlockPos otherHitBlockPos, Vector3d impactPosition, double impactVelocity) {
        SubLevelPhysicsSystem system;
        try {
            system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        } catch (IllegalStateException ignored) {
            return CollisionResult.NONE;
        }
        ServerLevel level = system.getLevel();
        if (level != null && level.getBlockEntity(hitBlockPos) instanceof SeaMineBlockEntity mine && mine.isArmed()) {
            SableCollisionDetonationQueue.queueSeaMine(level, hitBlockPos);
        }
        return CollisionResult.NONE;
    }
}
