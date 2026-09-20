package com.cbc_more_content.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.create.raycasts.SableRaycastHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.CBCCompatTransformers;

public final class SableDropCompat {
    private SableDropCompat() {}

    // —— queries ——

    public static boolean isInsideSubLevel(Level level, BlockPos pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isInsideSubLevel(Level level, Vec3 pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @javax.annotation.Nullable
    public static dev.ryanhcode.sable.sublevel.ServerSubLevel containingSubLevel(Level level, BlockPos pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub
                    ? sub
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean overlapsAnySubLevel(ServerLevel level, Vec3 center, double reach) {
        ServerSubLevelContainer container = container(level);
        if (container == null) {
            return false;
        }
        double reachSqr = reach * reach;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel != null
                    && !subLevel.isRemoved()
                    && withinReach(subLevel.boundingBox(), center.x, center.y, center.z, reachSqr)) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldSkipVaporize(ServerLevel level, Vec3 center, float blastRadius) {
        return overlapsAnySubLevel(level, center, Math.max(blastRadius * 2.0D, 8.0D));
    }

    public static int subLevelIdAt(ServerLevel level, BlockPos pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub ? sub.getRuntimeId() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static Vec3 clipSubLevels(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3[] hit = new Vec3[1];
        try {
            SableRaycastHelper.rayCastUntilWithSublevels(
                    level,
                    from,
                    to,
                    // World blocks are the ordinary clip's business, not this one's.
                    worldPos -> false,
                    (sub, plotPos) -> {
                        if (hit[0] != null || sub == null || sub.isRemoved()) {
                            return false;
                        }
                        BlockState state = readState(level, plotPos);
                        if (state == null
                                || state.isAir()
                                || state.getCollisionShape(level, plotPos).isEmpty()) {
                            return false;
                        }
                        Vec3 mapped = sub.logicalPose().transformPosition(Vec3.atCenterOf(plotPos));
                        hit[0] = nearSegment(mapped, from, to) ? mapped : to;
                        return true;
                    });
        } catch (Throwable ignored) {
        }
        return hit[0];
    }

    private static final double MAPPING_SLACK = 8.0D;

    private static boolean nearSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 span = to.subtract(from);
        double lengthSqr = span.lengthSqr();
        Vec3 nearest = lengthSqr < 1.0E-6D
                ? from
                : from.add(span.scale(
                        Math.max(0.0D, Math.min(1.0D, point.subtract(from).dot(span) / lengthSqr))));
        return point.distanceToSqr(nearest) <= MAPPING_SLACK * MAPPING_SLACK;
    }

    /** World-space centre of a sub-level by id, or null once it is gone. */
    public static Vec3 subLevelCentre(ServerLevel level, int id) {
        ServerSubLevelContainer container = container(level);
        if (container == null) {
            return null;
        }
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved() || sub.getRuntimeId() != id) {
                continue;
            }
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) {
                return null;
            }
            return new Vec3(
                    (box.minX() + box.maxX()) * 0.5D,
                    (box.minY() + box.maxY()) * 0.5D,
                    (box.minZ() + box.maxZ()) * 0.5D);
        }
        return null;
    }

    // —— coordinate mapping ——

    /** Maps a plot-local launch into parent-world space, adding the carrier's velocity. */
    public static LaunchFrame resolveLaunch(ServerLevel level, Vec3 localPos, Vec3 localVel, Vec3 localOrientation) {
        try {
            if (Sable.HELPER.getContaining(level, localPos) instanceof ServerSubLevel sub) {
                ServerLevel parent = sub.getLevel() == null ? level : sub.getLevel();
                Vector3d carrier = new Vector3d();
                Sable.HELPER.getVelocity(level, JOMLConversion.toJOML(localPos), carrier);
                carrier.mul(1.0D / 20.0D);
                return new LaunchFrame(
                        parent,
                        sub.logicalPose().transformPosition(localPos),
                        sub.logicalPose().transformNormal(localVel).add(carrier.x, carrier.y, carrier.z),
                        localOrientation == null
                                ? null
                                : sub.logicalPose()
                                        .transformNormal(localOrientation)
                                        .normalize());
            }
        } catch (Throwable ignored) {
        }
        return new LaunchFrame(level, localPos, localVel, localOrientation);
    }

    /**
     * Maps a detonation point into the world the blast should actually happen in.
     * Done directly rather than through CBC's transformer list: a projectile impact
     * callback keeps Sable's plot-space hit result, and no setup order may leave the
     * blast thousands of blocks out in the storage grid.
     */
    public static BlastTarget resolveWorldBlast(ServerLevel level, Vec3 localCenter) {
        try {
            if (Sable.HELPER.getContaining(level, localCenter) instanceof ServerSubLevel sub
                    && sub.getLevel() != null) {
                return new BlastTarget(sub.getLevel(), sub.logicalPose().transformPosition(localCenter));
            }
        } catch (Throwable ignored) {
        }
        return new BlastTarget(level, CBCCompatTransformers.transformVec3(level, localCenter));
    }

    public static BlastTarget resolveWorldBlastChecked(ServerLevel level, Vec3 localCenter) {
        if (!ModList.get().isLoaded("sable")) {
            return new BlastTarget(level, CBCCompatTransformers.transformVec3(level, localCenter));
        }
        return resolveWorldBlast(level, localCenter);
    }

    // —— small helpers ——

    private static ServerSubLevelContainer container(ServerLevel level) {
        try {
            return SubLevelContainer.getContainer(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockState readState(BlockGetter source, BlockPos pos) {
        try {
            return source.getBlockState(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean withinReach(BoundingBox3dc aabb, double x, double y, double z, double reachSqr) {
        if (aabb == null) {
            return false;
        }
        double dx = Math.max(aabb.minX() - x, Math.max(0.0D, x - aabb.maxX()));
        double dy = Math.max(aabb.minY() - y, Math.max(0.0D, y - aabb.maxY()));
        double dz = Math.max(aabb.minZ() - z, Math.max(0.0D, z - aabb.maxZ()));
        return dx * dx + dy * dy + dz * dz <= reachSqr;
    }

    public record BlastTarget(ServerLevel level, Vec3 pos) {}

    public record LaunchFrame(ServerLevel level, Vec3 pos, Vec3 vel, Vec3 orientation) {}
}
