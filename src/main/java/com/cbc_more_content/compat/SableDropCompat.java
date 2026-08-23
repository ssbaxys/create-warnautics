package com.cbc_more_content.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;


import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3d;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import rbasamoyai.createbigcannons.CBCCompatTransformers;

/**
 * Sable bridge — only touched when {@code sable} is on the classpath.
 * <p>
 * Coordinate mapping, and nothing else. A bomb launched inside a plot is created in
 * parent-world space with the carrier's inertia already applied, rather than spawned
 * locally and kicked out mid-physics-tick, and a detonation resolved from plot space is
 * mapped to where the blast should actually happen.
 * <p>
 * Blast destruction is deliberately not handled here. Sable patches
 * {@code ServerLevel#explode} and {@code Explosion#explode} itself, so an ordinary
 * explosion already breaks hull blocks; carving them by hand only bypassed that.
 */
public final class SableDropCompat {
    private SableDropCompat() {
    }

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

    /** True if any loaded sub-level comes within {@code reach} of {@code center}. */
    public static boolean overlapsAnySubLevel(ServerLevel level, Vec3 center, double reach) {
        ServerSubLevelContainer container = container(level);
        if (container == null) {
            return false;
        }
        double reachSqr = reach * reach;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel != null && !subLevel.isRemoved()
                    && withinReach(subLevel.boundingBox(), center.x, center.y, center.z, reachSqr)) {
                return true;
            }
        }
        return false;
    }

    /** Vaporize plus heat-map assembly freezes the server tick, so skip it near a hull. */
    public static boolean shouldSkipVaporize(ServerLevel level, Vec3 center, float blastRadius) {
        return overlapsAnySubLevel(level, center, Math.max(blastRadius * 2.0D, 8.0D));
    }

    /**
     * Runtime id of the sub-level containing {@code pos}, or -1 for open world.
     * <p>
     * Resolved here rather than on the client: the designator only reports the block a
     * player was looking at, and the server decides what that block belongs to.
     */
    public static int subLevelIdAt(ServerLevel level, BlockPos pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub
                    ? sub.getRuntimeId()
                    : -1;
        } catch (Throwable ignored) {
            return -1;
        }
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
            return new Vec3((box.minX() + box.maxX()) * 0.5D,
                    (box.minY() + box.maxY()) * 0.5D,
                    (box.minZ() + box.maxZ()) * 0.5D);
        }
        return null;
    }

    // —— coordinate mapping ——

    /** Maps a plot-local launch into parent-world space, adding the carrier's velocity. */
    public static LaunchFrame resolveLaunch(
            ServerLevel level, Vec3 localPos, Vec3 localVel, Vec3 localOrientation) {
        try {
            if (Sable.HELPER.getContaining(level, localPos) instanceof ServerSubLevel sub) {
                ServerLevel parent = sub.getLevel() == null ? level : sub.getLevel();
                Vector3d carrier = new Vector3d();
                Sable.HELPER.getVelocity(level, JOMLConversion.toJOML(localPos), carrier);
                carrier.mul(1.0D / 20.0D);
                return new LaunchFrame(
                        parent,
                        sub.logicalPose().transformPosition(localPos),
                        sub.logicalPose().transformNormal(localVel)
                                .add(carrier.x, carrier.y, carrier.z),
                        localOrientation == null
                                ? null
                                : sub.logicalPose().transformNormal(localOrientation).normalize());
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

    // —— small helpers ——

    private static ServerSubLevelContainer container(ServerLevel level) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return null;
            }
            Vector3d p = new Vector3d(center.x, center.y, center.z);
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                BoundingBox3dc aabb = subLevel.boundingBox();
                if (aabb != null && aabb.contains(p)) {
                    return container;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<WorldBreakTarget> keepBestWorldTargets(
            List<WorldBreakTarget> targets,
            int cap) {
        if (targets.size() <= cap) {
            targets.sort(Comparator.comparingDouble(WorldBreakTarget::score).reversed());
            return targets;
        }

        // Keep only the cap highest-energy blocks while scanning. Sorting a large
        // candidate volume just to discard its tail was a second O(n log n) pass.
        PriorityQueue<WorldBreakTarget> best = new PriorityQueue<>(
                cap + 1, Comparator.comparingDouble(WorldBreakTarget::score));
        for (WorldBreakTarget target : targets) {
            if (best.size() < cap) {
                best.add(target);
            } else if (target.score() > best.peek().score()) {
                best.poll();
                best.add(target);
            }
        }
        List<WorldBreakTarget> selected = new ArrayList<>(best);
        selected.sort(Comparator.comparingDouble(WorldBreakTarget::score).reversed());
        return selected;
    }

    /**
     * Runs the candidate list past {@link ExplosionEvent.Detonate} so protection mods
     * can veto blocks exactly as they would for a vanilla or CBC explosion, and returns
     * the positions that are still allowed to break.
     */
    private static java.util.Set<BlockPos> filterProtectedBlocks(
            ServerLevel level,
            Vec3 center,
            float power,
            List<WorldBreakTarget> targets) {
        List<BlockPos> candidates = new ArrayList<>(targets.size());
        for (WorldBreakTarget target : targets) {
            candidates.add(target.pos());
        }
        return BlastProtection.filter(level, center, power, candidates);
    }

    private static boolean aabbWithinReach(BoundingBox3dc aabb, Vector3d point, double reachSqr) {
        if (aabb == null) {
            return false;
        }
        double dx = Math.max(aabb.minX() - point.x, Math.max(0.0D, point.x - aabb.maxX()));
        double dy = Math.max(aabb.minY() - point.y, Math.max(0.0D, point.y - aabb.maxY()));
        double dz = Math.max(aabb.minZ() - point.z, Math.max(0.0D, point.z - aabb.maxZ()));
        return dx * dx + dy * dy + dz * dz <= reachSqr;
    }

    /**
     * Produces a smooth but torn angular boundary. The multiplier is always in
     * {@code [1 - irregularity, 1]}, so previous radius balance remains the hard
     * maximum and the scan can still use the original radius as its outer bound.
     */
    private static double irregularBlastReach(
            double reach,
            double dx,
            double dy,
            double dz,
            long seed,
            double irregularity) {
        if (reach <= 0.0D || irregularity <= 0.0D) {
            return reach;
        }

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-6D) {
            return reach;
        }

        double nx = dx / distance;
        double ny = dy / distance;
        double nz = dz / distance;
        double phase = (seed & 0xffffL) * (Math.PI * 2.0D / 65536.0D);
        double wave = Math.sin(nx * 3.1D + ny * 4.7D + nz * 2.3D + phase)
                + 0.58D * Math.sin(nx * 6.3D - ny * 3.9D + nz * 5.1D + phase * 1.71D)
                + 0.31D * Math.sin(nx * 10.7D + ny * 8.1D - nz * 7.3D - phase * 0.83D)
                + 0.20D * Math.sin(nx * 21.7D - ny * 18.3D + nz * 24.1D + phase * 2.37D);
        double normalized = Math.max(0.0D, Math.min(1.0D, (wave / 2.09D + 1.0D) * 0.5D));
        return reach * (1.0D - Math.min(0.75D, irregularity) * normalized);
    }

    private static BlockState readState(BlockGetter source, BlockPos pos) {
        try {
            return source.getBlockState(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUnbreakable(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        try {
            return source.getBlockState(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double resistance(BlockState state, BlockGetter source, BlockPos pos) {
        try {
            return Math.max(0.0D, state.getExplosionResistance(source, pos, null));
        } catch (Throwable ignored) {
            return Math.max(0.0D, state.getBlock().getExplosionResistance());
        }
    }

    private static boolean unbreakable(BlockState state, BlockGetter source, BlockPos pos) {
        try {
            return state.getDestroySpeed(source, pos) < 0.0f;
        } catch (Throwable ignored) {
            return state.is(Blocks.BEDROCK);
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

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value) && value > 0.0f;
    }

    @FunctionalInterface
    private interface BlockFilter {
        boolean test(BlockPos pos, BlockState state);
    }

    private record Target(BlockPos pos, double score) {
    }

    public record BlastTarget(ServerLevel level, Vec3 pos) {
    }

    public record LaunchFrame(ServerLevel level, Vec3 pos, Vec3 vel, Vec3 orientation) {
    }
}
