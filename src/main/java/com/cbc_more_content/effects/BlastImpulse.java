package com.cbc_more_content.effects;

import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Momentum absorbed along the same occluded rays that damage terrain and moving hulls. */
public final class BlastImpulse {
    // Momentum is divided over the ray directions, independent of the sampling density.
    private static final double MOMENTUM_SCALE = 6.0D;
    private static final double MAX_SPEED_CHANGE = 12.0D;
    private static final double MAX_SPIN_CHANGE = 4.0D;
    private final Map<BlockPos, Hit> hits = new HashMap<>();

    void absorb(BlastScene.Sample block, Vec3 worldDirection, double pressureLost, float power, int rays) {
        if (block.body() == null || pressureLost <= 0) {
            return;
        }
        double momentum = pressureLost * MOMENTUM_SCALE * power * power / rays;
        Vec3 localImpulse = block.body()
                .logicalPose()
                .transformNormalInverse(worldDirection)
                .scale(momentum);
        Hit hit = hits.computeIfAbsent(block.pos(), ignored -> new Hit(block, new Vector3d()));
        hit.impulse().add(localImpulse.x, localImpulse.y, localImpulse.z);
    }

    /** Apply before removing hull blocks, while the original mass and inertia are still present. */
    public void apply(Set<BlockPos> protectedBlocks) {
        Map<ServerSubLevel, ForceTotal> totals = new HashMap<>();
        for (var entry : hits.entrySet()) {
            var hit = entry.getValue();
            var body = hit.block().body();
            if (protectedBlocks.contains(entry.getKey())
                    || body.isRemoved()
                    || body.getMassTracker().isInvalid()) {
                continue;
            }
            var total = totals.computeIfAbsent(body, ignored -> new ForceTotal());
            var pos = hit.block().pos();
            total.applyImpulseAtPoint(
                    body.getMassTracker(),
                    new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                    hit.impulse());
        }
        hits.clear();
        totals.forEach((body, total) -> {
            var handle = RigidBodyHandle.of(body);
            if (handle == null || !handle.isValid()) {
                return;
            }
            var mass = body.getMassTracker();
            var impulse = total.getLocalForce();
            var torque = total.getLocalTorque();
            // These bounds only tame tiny fragments at the epicentre. Ordinary response is
            // native momentum / mass (and torque / inertia), never a fixed velocity kick.
            double speedChange = impulse.length() / mass.getMass();
            double spinChange = mass.getInverseInertiaTensor()
                    .transform(torque, new Vector3d())
                    .length();
            if (speedChange > MAX_SPEED_CHANGE) {
                impulse.mul(MAX_SPEED_CHANGE / speedChange);
            }
            if (spinChange > MAX_SPIN_CHANGE) {
                torque.mul(MAX_SPIN_CHANGE / spinChange);
            }
            handle.applyLinearAndAngularImpulse(impulse, torque);
        });
    }

    private record Hit(BlastScene.Sample block, Vector3d impulse) {}
}
