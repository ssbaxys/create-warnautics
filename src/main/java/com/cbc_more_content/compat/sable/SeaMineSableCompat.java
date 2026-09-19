package com.cbc_more_content.compat.sable;

import com.cbc_more_content.entity.SeaMineEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Hull contact for a moored sea mine.
 * <p>
 * The land mines get their hull contact from Sable's collision callback, which fires
 * for blocks taking part in a physics contact — a moored mine is an entity floating in
 * open water, so nobody steps on it and nobody collides with a block. The sweep here
 * is the entity's own half of the same idea: every sub-level whose global bounds reach
 * the mine's horns goes off, with the box-to-point distance kept in squared space.
 * <p>
 * Sub-level bounds are axis-aligned world-space boxes around each hull, so this is a
 * proximity fuze rather than a horn-by-horn test: close enough counts. A contact fuze
 * that ignored a hundred-metre hull until its centreline touched a sphere half a metre
 * wide would be decorative.
 */
public final class SeaMineSableCompat {
    private SeaMineSableCompat() {}

    /** Extra reach around the mine's own box, so hull sides count without a centre hit. */
    private static final double HULL_REACH = 0.5D;

    public static void sweepHulls(ServerLevel level, SeaMineEntity mine) {
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        ServerSubLevelContainer container;
        try {
            container = SubLevelContainer.getContainer(level);
        } catch (Throwable ignored) {
            return;
        }
        if (container == null) {
            return;
        }

        Vec3 centre = mine.position();
        double reach = mine.getBoundingBox().getSize() * 0.5D + HULL_REACH;
        double reachSqr = reach * reach;

        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved() || !(sub instanceof ServerSubLevel server)) {
                continue;
            }
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) {
                continue;
            }
            double dx = Math.max(box.minX() - centre.x, Math.max(0.0D, centre.x - box.maxX()));
            double dy = Math.max(box.minY() - centre.y, Math.max(0.0D, centre.y - box.maxY()));
            double dz = Math.max(box.minZ() - centre.z, Math.max(0.0D, centre.z - box.maxZ()));
            if (dx * dx + dy * dy + dz * dz <= reachSqr) {
                mine.trigger();
                return;
            }
        }
    }
}
