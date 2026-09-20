package com.cbc_more_content.compat.sable;

import com.cbc_more_content.entity.SeaMineEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class SeaMineSablePhysicsCompat {
    private static final double MASS = 0.8D;
    private static final double HALF_EXTENT = 0.47D;
    /** Rope thickness, matching Simulated's own strands. */
    private static final double ROPE_RADIUS = 0.035D;

    private static final double RISE_SPEED = 1.2D;
    /** Fraction of the speed error corrected per tick — the stiffness of the float. */
    private static final double BUOYANCY_STIFFNESS = 0.08D;
    /** Extra vertical drag on the body while submerged, so it does not oscillate. */
    private static final double WATER_DRAG = 0.05D;
    /** How hard the mooring pulls when the mine tries to drift past its rope length. */
    private static final double ANCHOR_STIFFNESS = 0.35D;

    private SeaMineSablePhysicsCompat() {}

    public static State create(ServerLevel level, SeaMineEntity mine) {
        Pose3d pose = new Pose3d();
        pose.position().set(mine.getX(), mine.getY(), mine.getZ());
        BoxPhysicsObject body = new BoxPhysicsObject(pose, new Vector3d(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT), MASS);
        SubLevelPhysicsSystem.require(level).addObject(body);
        return new State(body);
    }

    public static void tick(ServerLevel level, SeaMineEntity mine, State state) {
        if (state.body.isRemoved()) {
            state.body = create(level, mine).body;
        }

        state.body.updatePose();
        Vec3 position = toVec3(state.body.getPose().position());
        mine.setPos(position.x, position.y, position.z);
        mine.xo = position.x;
        mine.yo = position.y;
        mine.zo = position.z;

        RigidBodyHandle handle = RigidBodyHandle.of(level, state.body);
        if (handle == null || !handle.isValid()) {
            return;
        }

        if (mine.isInWater()) {
            Vector3d velocity = handle.getLinearVelocity(new Vector3d());
            double error = RISE_SPEED - velocity.y;
            double lift = error * BUOYANCY_STIFFNESS;
            handle.applyLinearImpulse(new Vector3d(0.0D, lift, 0.0D));
            handle.applyLinearImpulse(new Vector3d(0.0D, -velocity.y * WATER_DRAG, 0.0D));
        }

        if (mine.isAnchored()) {
            if (state.ropeLength <= 0.0D) {
                double savedLength = mine.getAnchorLength();
                state.ropeLength =
                        savedLength > 0.0D ? savedLength : position.distanceTo(anchorPoint(mine.getAnchor()));
            }
            Vec3 anchor = anchorPoint(mine.getAnchor());
            Vec3 delta = position.subtract(anchor);
            double distance = delta.length();
            if (distance > state.ropeLength && distance > 0.01D) {
                Vec3 target = anchor.add(delta.scale(state.ropeLength / distance));
                Vec3 correction = target.subtract(position);
                handle.applyLinearImpulse(new Vector3d(correction.x, correction.y, correction.z).mul(ANCHOR_STIFFNESS));
                Vector3d velocity = handle.getLinearVelocity(new Vector3d());
                Vec3 velocityVec = new Vec3(velocity.x, velocity.y, velocity.z);
                double along = delta.normalize().dot(velocityVec);
                if (along > 0.0D) {
                    Vec3 outward = delta.scale(along);
                    handle.applyLinearImpulse(new Vector3d(-outward.x, -outward.y, -outward.z).mul(ANCHOR_STIFFNESS));
                }
            }
            updateRope(state, anchor, position);
        }
    }

    private static Vec3 anchorPoint(BlockPos anchor) {
        return Vec3.atCenterOf(anchor).add(0.0D, 0.5D, 0.0D);
    }

    public static void anchor(ServerLevel level, SeaMineEntity mine, BlockPos anchor) {
        if (mine.sableState == null) {
            mine.sableState = create(level, mine);
        }
        State state = mine.sableState;
        Vec3 start = anchorPoint(anchor);
        Vec3 end = mine.position();
        dev.ryanhcode.sable.sublevel.ServerSubLevel host =
                com.cbc_more_content.compat.SableDropCompat.containingSubLevel(level, anchor);
        List<Vector3d> points = List.of(
                new Vector3d(start.x, start.y, start.z),
                new Vector3d((start.x + end.x) * 0.5D, (start.y + end.y) * 0.5D, (start.z + end.z) * 0.5D),
                new Vector3d(end.x, end.y, end.z));
        state.ropeLength = start.distanceTo(end);
        mine.setAnchorLength(state.ropeLength);
        if (state.rope != null && state.rope.isActive()) {
            SubLevelPhysicsSystem.require(level).removeObject(state.rope);
        }
        state.rope = new RopePhysicsObject(points, ROPE_RADIUS);
        state.rope.setAttachment(RopeHandle.AttachmentPoint.START, new Vector3d(start.x, start.y, start.z), host);
        state.rope.setAttachment(RopeHandle.AttachmentPoint.END, new Vector3d(end.x, end.y, end.z), null);
        SubLevelPhysicsSystem.require(level).addObject(state.rope);
        mine.setAnchor(anchor);
    }

    public static void unanchor(ServerLevel level, SeaMineEntity mine) {
        State state = mine.sableState;
        if (state != null && state.rope != null && state.rope.isActive()) {
            SubLevelPhysicsSystem.require(level).removeObject(state.rope);
        }
        if (state != null) {
            state.rope = null;
            state.ropeLength = 0.0D;
        }
        mine.setAnchor(null);
        mine.setAnchorLength(0.0D);
    }

    private static void updateRope(State state, Vec3 anchor, Vec3 mine) {
        if (state.rope == null || !state.rope.isActive()) {
            return;
        }
        state.rope.setAttachment(RopeHandle.AttachmentPoint.END, new Vector3d(mine.x, mine.y, mine.z), null);
        state.rope.setFirstSegmentLength(state.ropeLength * 0.5D);
    }

    public static void remove(ServerLevel level, State state) {
        if (state == null) {
            return;
        }
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.require(level);
        if (state.rope != null && state.rope.isActive()) {
            system.removeObject(state.rope);
        }
        if (!state.body.isRemoved()) {
            system.removeObject(state.body);
        }
    }

    private static Vec3 toVec3(org.joml.Vector3dc value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    public static final class State {
        private BoxPhysicsObject body;
        private RopePhysicsObject rope;
        private double ropeLength;

        private State(BoxPhysicsObject body) {
            this.body = body;
        }
    }
}
