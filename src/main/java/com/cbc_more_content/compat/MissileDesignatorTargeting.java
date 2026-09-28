package com.cbc_more_content.compat;

import com.cbc_more_content.siren.SirenSource;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared client/server range and visibility rules. Only the operator's own hull is transparent. */
public final class MissileDesignatorTargeting {
    public static final double MIN_TARGET_RANGE = 50;
    public static final double MAX_RANGE = 220;

    private MissileDesignatorTargeting() {}

    public static boolean canControl(Player player, BlockPos missile) {
        return player.level().isLoaded(missile)
                && player.distanceToSqr(
                                SirenSource.capture(player.level(), missile).worldPosition())
                        <= MAX_RANGE * MAX_RANGE;
    }

    @Nullable
    public static SubLevel operatorHull(Player player, BlockPos missile) {
        var tracked = ((EntityMovementExtension) player).sable$getTrackingSubLevel();
        if (tracked != null && !tracked.isRemoved()) {
            return tracked;
        }
        var host = Sable.HELPER.getContaining(player.level(), missile);
        if (host != null && !host.isRemoved() && host.boundingBox() != null) {
            var b = host.boundingBox();
            var p = player.getEyePosition();
            if (p.x >= b.minX()
                    && p.x <= b.maxX()
                    && p.y >= b.minY()
                    && p.y <= b.maxY()
                    && p.z >= b.minZ()
                    && p.z <= b.maxZ()) {
                return host;
            }
        }
        return null;
    }

    public static boolean canTarget(Player player, BlockPos missile, UUID target, Vec3 centre) {
        var level = player.level();
        var own = operatorHull(player, missile);
        var carrier = Sable.HELPER.getContaining(level, missile);
        if ((own != null && own.getUniqueId().equals(target))
                || (carrier != null && carrier.getUniqueId().equals(target))) {
            return false;
        }
        var launch = SirenSource.capture(level, missile).worldPosition();
        if (launch.distanceToSqr(centre) < MIN_TARGET_RANGE * MIN_TARGET_RANGE
                || player.getEyePosition().distanceToSqr(centre) > MAX_RANGE * MAX_RANGE) {
            return false;
        }
        var ray = new ClipContext(
                player.getEyePosition(), centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        ((ClipContextExtension) ray).sable$setIgnoredSubLevel(own);
        var hit = level.clip(ray);
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        var hitHull = Sable.HELPER.getContaining(level, hit.getBlockPos());
        return hitHull != null && target.equals(hitHull.getUniqueId());
    }
}
