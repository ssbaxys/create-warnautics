package com.cbc_more_content.siren;

import dev.ryanhcode.sable.Sable;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A stable block address and a world-space fallback for listeners without the ship loaded. */
public record SirenSource(BlockPos pos, @Nullable UUID subLevelId, Vec3 worldPosition) {
    public SirenSource {
        pos = pos.immutable();
    }

    public static SirenSource capture(Level level, BlockPos pos) {
        var body = Sable.HELPER.getContaining(level, pos);
        return body == null
                ? new SirenSource(pos, null, pos.getCenter())
                : new SirenSource(pos, body.getUniqueId(), body.logicalPose().transformPosition(pos.getCenter()));
    }

    /** Resolve every tick so both the stereo position and the distance mix follow translation and rotation. */
    public Vec3 position(Level level) {
        if (this.subLevelId != null) {
            var body = Sable.HELPER.getContaining(level, this.pos);
            if (body != null && !body.isRemoved() && this.subLevelId.equals(body.getUniqueId())) {
                return body.logicalPose().transformPosition(this.pos.getCenter());
            }
        }
        return this.worldPosition;
    }

    public boolean samePost(SirenSource other) {
        return this.pos.equals(other.pos) && java.util.Objects.equals(this.subLevelId, other.subLevelId);
    }
}
