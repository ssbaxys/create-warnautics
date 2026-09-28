package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.config.WarnauticsConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Glass is part of the same protected explosion list, in either coordinate frame. */
public final class BlastGlassShatter {
    private static final TagKey<Block> GLASS = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "shatterable_glass"));

    private BlastGlassShatter() {}

    public static List<BlockPos> gather(
            ServerLevel level, Vec3 center, double craterRadius, Collection<BlockPos> crater, int cap) {
        if (cap <= 0) {
            return List.of();
        }
        double radius = Math.max(5.0D, craterRadius * WarnauticsConfig.glassShatterRadiusMultiplier());
        BlastScene scene = new BlastScene(level, center, radius);
        var candidates = scene.matching(center, radius, state -> state.is(GLASS));
        candidates.sort(Comparator.comparingDouble(block -> block.worldCenter().distanceToSqr(center)));
        var ignored = new HashSet<>(crater);
        List<BlockPos> result = new ArrayList<>();
        List<BlastScene.Sample> samples = new ArrayList<>();
        for (var glass : candidates) {
            if (ignored.contains(glass.pos())) {
                continue;
            }
            Vec3 ray = glass.worldCenter().subtract(center);
            double distance = ray.length();
            // Preserve the old full inner radius and gradual outer falloff, independent of frame.
            double full = Math.min(radius, craterRadius * 2.0D);
            if (distance > full && level.random.nextDouble() > (radius - distance) / (radius - full)) {
                continue;
            }
            boolean blocked = false;
            for (double t = 0.3D; t < distance - 0.5D; t += 0.3D) {
                scene.sample(center.add(ray.scale(t / distance)), samples);
                for (var block : samples) {
                    if (!ignored.contains(block.pos())
                            && !block.state().is(GLASS)
                            && !block.state()
                                    .getCollisionShape(level, block.pos())
                                    .isEmpty()) {
                        blocked = true;
                    }
                }
                if (blocked) {
                    break;
                }
            }
            if (!blocked) {
                result.add(glass.pos());
            }
            if (result.size() >= cap) {
                break;
            }
        }
        return result;
    }
}
