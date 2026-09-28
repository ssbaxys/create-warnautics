package com.cbc_more_content.effects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** Real water contact and its open surface; all queries use world coordinates. */
public final class BlastWater {
    private BlastWater() {}

    public static boolean contains(ServerLevel level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER)
                && point.y < pos.getY() + fluid.getHeight(level, pos)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Nullable
    public static Contact find(ServerLevel level, Vec3 origin, double reach) {
        List<Vec3> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(origin.add(-reach, -reach, -reach)),
                BlockPos.containing(origin.add(reach, reach, reach)))) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            var fluid = level.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)
                    || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            double height = fluid.getHeight(level, pos);
            Vec3 wet = new Vec3(
                    Math.clamp(origin.x, pos.getX() + 0.01, pos.getX() + 0.99),
                    Math.clamp(origin.y, pos.getY() + 0.01, pos.getY() + height - 0.01),
                    Math.clamp(origin.z, pos.getZ() + 0.01, pos.getZ() + 0.99));
            if (wet.distanceToSqr(origin) <= reach * reach) {
                candidates.add(wet);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(origin::distanceToSqr));
        BlastScene scene = new BlastScene(level, origin, reach + 65);
        List<BlastScene.Sample> samples = new ArrayList<>();
        for (Vec3 wet : candidates) {
            boolean blocked = false;
            int steps = Math.max(1, (int) Math.ceil(origin.distanceTo(wet) / 0.25));
            for (int i = 1; i <= steps; i++) {
                scene.sample(origin.lerp(wet, i / (double) steps), samples);
                if (solid(level, samples)) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) {
                continue;
            }
            BlockPos base = BlockPos.containing(wet);
            for (int dy = 0; dy < 64; dy++) {
                BlockPos pos = base.above(dy);
                if (!level.hasChunkAt(pos) || pos.getY() >= level.getMaxBuildHeight()) {
                    break;
                }
                var fluid = level.getFluidState(pos);
                if (!fluid.is(FluidTags.WATER)) {
                    break;
                }
                double height = fluid.getHeight(level, pos);
                Vec3 surface = new Vec3(wet.x, pos.getY() + height, wet.z);
                scene.sample(new Vec3(wet.x, pos.getY() + Math.min(height * 0.5, 0.5), wet.z), samples);
                if (solid(level, samples)) {
                    break;
                }
                if (!contains(level, surface.add(0, 0.02, 0))) {
                    for (double clearance = 0.05; clearance <= 1.5; clearance += 0.2) {
                        scene.sample(surface.add(0, clearance, 0), samples);
                        if (solid(level, samples)) {
                            return new Contact(wet, null);
                        }
                    }
                    return new Contact(wet, surface);
                }
            }
            return new Contact(wet, null);
        }
        return null;
    }

    private static boolean solid(ServerLevel level, List<BlastScene.Sample> samples) {
        for (var block : samples) {
            if (!block.state().getCollisionShape(level, block.pos()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public record Contact(Vec3 water, @Nullable Vec3 surface) {}
}
