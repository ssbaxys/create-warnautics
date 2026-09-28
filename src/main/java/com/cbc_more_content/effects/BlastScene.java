package com.cbc_more_content.effects;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** One geometric view of terrain and moving blocks. Only storage access uses plot coordinates. */
public final class BlastScene {
    private final ServerLevel level;
    private final List<ServerSubLevel> bodies = new ArrayList<>();
    private final Long2ObjectOpenHashMap<Sample> blocks = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private static final Sample EMPTY =
            new Sample(BlockPos.ZERO, Vec3.ZERO, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null);

    public BlastScene(ServerLevel level, Vec3 center, double reach) {
        this.level = level;
        for (var body : dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                .getAllSubLevels()) {
            if (body.isRemoved()) {
                continue;
            }
            Vec3 local = body.logicalPose().transformPositionInverse(center);
            var scale = body.logicalPose().scale();
            double localReach = reach / Math.max(1.0E-6D, Math.min(scale.x(), Math.min(scale.y(), scale.z())));
            var box = body.getPlot().getBoundingBox();
            if (local.x + localReach >= box.minX()
                    && local.x - localReach <= box.maxX() + 1
                    && local.y + localReach >= box.minY()
                    && local.y - localReach <= box.maxY() + 1
                    && local.z + localReach >= box.minZ()
                    && local.z - localReach <= box.maxZ() + 1) {
                bodies.add(body);
            }
        }
    }

    public void sample(Vec3 point, List<Sample> result) {
        result.clear();
        add(cell(point), null, result);
        for (ServerSubLevel body : bodies) {
            Vec3 local = body.logicalPose().transformPositionInverse(point);
            var bounds = body.getPlot().getBoundingBox();
            BlockPos pos = cell(local);
            if (pos.getX() < bounds.minX()
                    || pos.getX() > bounds.maxX()
                    || pos.getY() < bounds.minY()
                    || pos.getY() > bounds.maxY()
                    || pos.getZ() < bounds.minZ()
                    || pos.getZ() > bounds.maxZ()) {
                continue;
            }
            add(pos, body, result);
        }
    }

    private BlockPos cell(Vec3 point) {
        return cursor.set(snap(point.x), snap(point.y), snap(point.z));
    }

    // The plot is millions of blocks away. Normalize numerical noise at shared voxel faces in BOTH frames.
    private static double snap(double coordinate) {
        double nearest = Math.rint(coordinate);
        return Math.abs(coordinate - nearest) < 1.0E-7D ? nearest : coordinate;
    }

    private void add(BlockPos pos, ServerSubLevel body, List<Sample> result) {
        long key = pos.asLong();
        Sample block = blocks.get(key);
        if (block == null) {
            if (!level.hasChunkAt(pos)) {
                return;
            }
            var state = level.getBlockState(pos);
            if (state.isAir() || isOpenFluid(level, pos, state)) {
                block = EMPTY;
            } else {
                BlockPos stored = pos.immutable();
                block = new Sample(
                        stored,
                        body == null ? stored.getCenter() : body.logicalPose().transformPosition(stored.getCenter()),
                        state,
                        body);
            }
            blocks.put(key, block);
        }
        if (block != EMPTY) {
            result.add(block);
        }
    }

    public static boolean isOpenFluid(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty();
    }

    public static Vec3 worldPosition(ServerLevel level, BlockPos pos) {
        var body = Sable.HELPER.getContaining(level, pos);
        return body == null ? pos.getCenter() : body.logicalPose().transformPosition(pos.getCenter());
    }

    /** Scans only loaded sections whose palette contains the requested material. */
    public List<Sample> matching(Vec3 center, double radius, Predicate<BlockState> predicate) {
        return matching(center, radius, predicate, null);
    }

    /** Surface work can reject buried voxels before allocating, hashing and sorting candidates. */
    public List<Sample> matching(
            Vec3 center, double radius, Predicate<BlockState> predicate, java.util.Set<BlockPos> opened) {
        List<Sample> result = new ArrayList<>();
        scan(
                BlockPos.containing(center.add(-radius, -radius, -radius)),
                BlockPos.containing(center.add(radius, radius, radius)),
                null,
                center,
                radius,
                predicate,
                result,
                opened);
        for (ServerSubLevel body : bodies) {
            var b = body.getPlot().getBoundingBox();
            Vec3 local = body.logicalPose().transformPositionInverse(center);
            var scale = body.logicalPose().scale();
            // A world-space sphere fits in this local cube even on a rotated, scaled ship.
            // Scanning the whole plot made a small blast traverse every glass-bearing section of a large hull.
            double localRadius = radius / Math.max(1.0E-6D, Math.min(scale.x(), Math.min(scale.y(), scale.z()))) + 1;
            BlockPos low = BlockPos.containing(local.add(-localRadius, -localRadius, -localRadius));
            BlockPos high = BlockPos.containing(local.add(localRadius, localRadius, localRadius));
            scan(
                    new BlockPos(
                            Math.max(b.minX(), low.getX()),
                            Math.max(b.minY(), low.getY()),
                            Math.max(b.minZ(), low.getZ())),
                    new BlockPos(
                            Math.min(b.maxX(), high.getX()),
                            Math.min(b.maxY(), high.getY()),
                            Math.min(b.maxZ(), high.getZ())),
                    body,
                    center,
                    radius,
                    predicate,
                    result,
                    opened);
        }
        return result;
    }

    private void scan(
            BlockPos min,
            BlockPos max,
            ServerSubLevel body,
            Vec3 center,
            double radius,
            Predicate<BlockState> predicate,
            List<Sample> result,
            java.util.Set<BlockPos> opened) {
        var above = new BlockPos.MutableBlockPos();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int low = Math.max(level.getMinBuildHeight(), min.getY());
                int high = Math.min(level.getMaxBuildHeight() - 1, max.getY());
                for (int sy = low >> 4; sy <= high >> 4; sy++) {
                    var section = chunk.getSection(level.getSectionIndex(sy << 4));
                    if (section.hasOnlyAir() || !section.maybeHas(predicate)) {
                        continue;
                    }
                    for (int x = Math.max(cx << 4, min.getX()); x <= Math.min((cx << 4) + 15, max.getX()); x++) {
                        for (int y = Math.max(sy << 4, low); y <= Math.min((sy << 4) + 15, high); y++) {
                            for (int z = Math.max(cz << 4, min.getZ());
                                    z <= Math.min((cz << 4) + 15, max.getZ());
                                    z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!predicate.test(state)) {
                                    continue;
                                }
                                if (opened != null) {
                                    above.set(x, y + 1, z);
                                    boolean sameBody = body == null
                                            || y + 1
                                                    <= body.getPlot()
                                                            .getBoundingBox()
                                                            .maxY();
                                    if (sameBody
                                            && !opened.contains(above)
                                            && !level.getBlockState(above)
                                                    .getCollisionShape(level, above)
                                                    .isEmpty()) {
                                        continue;
                                    }
                                }
                                BlockPos pos = new BlockPos(x, y, z);
                                Vec3 world = body == null
                                        ? pos.getCenter()
                                        : body.logicalPose().transformPosition(pos.getCenter());
                                if (world.distanceToSqr(center) <= radius * radius) {
                                    result.add(new Sample(pos, world, state, body));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public record Sample(BlockPos pos, Vec3 worldCenter, BlockState state, ServerSubLevel body) {}
}
