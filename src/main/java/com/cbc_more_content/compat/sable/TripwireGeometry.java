package com.cbc_more_content.compat.sable;

import com.cbc_more_content.entity.TripwireEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Block addresses stay local; lengths, entity collisions and sounds use world coordinates. */
public final class TripwireGeometry {
    private TripwireGeometry() {}

    public static Optional<UUID> owner(Level level, BlockPos pos) {
        SubLevel hull = hull(level, pos);
        return hull == null ? Optional.empty() : Optional.of(hull.getUniqueId());
    }

    @Nullable
    public static SubLevel hull(Level level, BlockPos pos) {
        return ModList.get().isLoaded("sable") ? Sable.HELPER.getContaining(level, pos) : null;
    }

    @Nullable
    public static Vec3 position(Level level, BlockPos pos, Optional<UUID> owner) {
        if (owner.isEmpty()) {
            return TripwireEntity.tie(pos);
        }
        SubLevel hull = hull(level, pos);
        return hull != null && !hull.isRemoved() && owner.get().equals(hull.getUniqueId())
                ? hull.logicalPose().transformPosition(TripwireEntity.tie(pos))
                : null;
    }

    public static Vec3 position(Level level, BlockPos pos) {
        return position(level, pos, owner(level, pos));
    }

    /** Test real collision boxes along the run, excluding both supporting ships. */
    public static boolean crossesHull(
            ServerLevel level, Vec3 a, Vec3 b, Optional<UUID> ownerA, Optional<UUID> ownerB, double reach) {
        if (!ModList.get().isLoaded("sable")) {
            return false;
        }
        var bounds = new BoundingBox3d(
                Math.min(a.x, b.x) - reach,
                Math.min(a.y, b.y) - reach,
                Math.min(a.z, b.z) - reach,
                Math.max(a.x, b.x) + reach,
                Math.max(a.y, b.y) + reach,
                Math.max(a.z, b.z) + reach);
        for (SubLevel hull : Sable.HELPER.getAllIntersecting(level, bounds)) {
            if (hull.isRemoved()
                    || ownerA.filter(hull.getUniqueId()::equals).isPresent()
                    || ownerB.filter(hull.getUniqueId()::equals).isPresent()) {
                continue;
            }
            Vec3 start = hull.logicalPose().transformPositionInverse(a.lerp(b, 0.05));
            Vec3 end = hull.logicalPose().transformPositionInverse(a.lerp(b, 0.95));
            var scale = hull.logicalPose().scale();
            double radius = reach / Math.max(0.25, Math.min(scale.x(), Math.min(scale.y(), scale.z())));
            var area = new net.minecraft.world.phys.AABB(start, end).inflate(radius);
            for (var pos : BlockPos.betweenClosed(
                    BlockPos.containing(area.minX, area.minY, area.minZ),
                    BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
                if (!level.hasChunkAt(pos)) {
                    continue;
                }
                for (var shape :
                        level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
                    var box = shape.move(pos).inflate(radius);
                    if (box.contains(start)
                            || box.contains(end)
                            || box.clip(start, end).isPresent()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
