package com.cbc_more_content.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Checks actual nearby hull shapes; broad sub-level bounds alone also cover empty decks and holes. */
public final class SeaMineHullContact {
    private static final double HORN_RADIUS = 0.65D;

    private SeaMineHullContact() {}

    public static boolean touchesHull(ServerLevel level, ServerSubLevel ownBody, Vec3 center) {
        BoundingBox3d area = new BoundingBox3d(
                center.x - HORN_RADIUS,
                center.y - HORN_RADIUS,
                center.z - HORN_RADIUS,
                center.x + HORN_RADIUS,
                center.y + HORN_RADIUS,
                center.z + HORN_RADIUS);
        for (SubLevel hull : Sable.HELPER.getAllIntersecting(level, area)) {
            if (hull == ownBody || hull.isRemoved()) {
                continue;
            }
            Vec3 local = hull.logicalPose().transformPositionInverse(center);
            var scale = hull.logicalPose().scale();
            double radius = HORN_RADIUS / Math.max(0.25D, Math.min(scale.x(), Math.min(scale.y(), scale.z())));
            for (BlockPos pos : BlockPos.betweenClosed(
                    BlockPos.containing(local.add(-radius, -radius, -radius)),
                    BlockPos.containing(local.add(radius, radius, radius)))) {
                if (!level.hasChunkAt(pos)) {
                    continue;
                }
                for (var box :
                        level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
                    double x = local.x - pos.getX();
                    double y = local.y - pos.getY();
                    double z = local.z - pos.getZ();
                    double dx = Math.max(box.minX - x, Math.max(0, x - box.maxX));
                    double dy = Math.max(box.minY - y, Math.max(0, y - box.maxY));
                    double dz = Math.max(box.minZ - z, Math.max(0, z - box.maxZ));
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
