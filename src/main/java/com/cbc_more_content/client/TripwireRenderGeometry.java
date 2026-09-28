package com.cbc_more_content.client;

import com.cbc_more_content.compat.sable.TripwireGeometry;
import com.cbc_more_content.entity.TripwireEntity;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Use the ship's interpolated pose so the wire and its posts move together on screen. */
public final class TripwireRenderGeometry {
    private TripwireRenderGeometry() {}

    @Nullable
    public static Vec3 position(Level level, BlockPos pos, Optional<UUID> owner) {
        if (owner.isEmpty()) {
            return TripwireEntity.tie(pos);
        }
        var hull = TripwireGeometry.hull(level, pos);
        if (hull instanceof ClientSubLevel client
                && !hull.isRemoved()
                && owner.get().equals(hull.getUniqueId())) {
            return client.renderPose().transformPosition(TripwireEntity.tie(pos));
        }
        return null;
    }
}
