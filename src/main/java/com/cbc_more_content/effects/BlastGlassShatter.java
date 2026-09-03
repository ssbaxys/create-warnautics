package com.cbc_more_content.effects;

import com.cbc_more_content.config.WarnauticsConfig;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

public final class BlastGlassShatter {
    private static final Predicate<BlockState> GLASSY =
            state -> state.is(BlockTags.IMPERMEABLE) || state.getBlock() instanceof IronBarsBlock;

    private static final int MAX_SHATTERED = 2048;
    private static final double FULL_SHATTER_RADIUS_FRACTION = 2.0D;

    private BlastGlassShatter() {}

    public static void scheduleFor(
            ServerLevel level, Vec3 center, float craterRadius, BombBurstBudget.Lod lod, LongSet craterBlocks) {
        double radius = Math.max(5.0D, craterRadius * WarnauticsConfig.glassShatterRadiusMultiplier());
        var server = level.getServer();
        if (server == null) {
            shatter(level, center, radius, lod, craterBlocks);
            return;
        }
        server.tell(new TickTask(server.getTickCount() + 1, () -> shatter(level, center, radius, lod, craterBlocks)));
    }

    static void shatter(ServerLevel level, Vec3 center, double radius, BombBurstBudget.Lod lod, LongSet excluded) {
        int cap =
                switch (lod) {
                    case FULL, REDUCED -> MAX_SHATTERED;
                    case MINIMAL -> MAX_SHATTERED / 2;
                    case ESSENTIAL -> MAX_SHATTERED / 4;
                };

        List<BlockPos> candidates = gather(level, center, radius, excluded, cap);
        if (candidates.isEmpty()) {
            return;
        }
        for (BlockPos pos : BlastProtection.filter(level, center, (float) radius, candidates)) {
            level.destroyBlock(pos, false);
        }
    }

    private static List<BlockPos> gather(ServerLevel level, Vec3 center, double radius, LongSet excluded, int cap) {
        List<BlockPos> candidates = new ArrayList<>();
        RandomSource random = level.random;

        int minBuild = level.getMinBuildHeight();
        int minY = Math.max(minBuild, (int) Math.floor(center.y - radius));
        int maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.floor(center.y + radius));
        if (maxY < minY) {
            return candidates;
        }

        int centerX = (int) Math.floor(center.x);
        int centerZ = (int) Math.floor(center.z);
        int maxRing = (int) Math.ceil(radius);
        double radiusSqr = radius * radius;
        double fullRadius = radius / WarnauticsConfig.glassShatterRadiusMultiplier() * FULL_SHATTER_RADIUS_FRACTION;

        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;

        ring:
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    double dxCenter = dx + (center.x - centerX);
                    double dzCenter = dz + (center.z - centerZ);
                    double horizontalSqr = dxCenter * dxCenter + dzCenter * dzCenter;
                    if (horizontalSqr > radiusSqr) {
                        continue;
                    }
                    if (candidates.size() >= cap) {
                        break ring;
                    }

                    int x = centerX + dx;
                    int z = centerZ + dz;
                    int columnChunkX = x >> 4;
                    int columnChunkZ = z >> 4;
                    if (columnChunkX != chunkX || columnChunkZ != chunkZ) {
                        chunk = level.getChunkSource().getChunkNow(columnChunkX, columnChunkZ);
                        chunkX = columnChunkX;
                        chunkZ = columnChunkZ;
                    }
                    if (chunk == null) {
                        continue;
                    }

                    double dyMax = Math.sqrt(radiusSqr - horizontalSqr);
                    int yLow = Math.max(minY, (int) Math.floor(center.y - dyMax));
                    int yHigh = Math.min(maxY, (int) Math.ceil(center.y + dyMax));
                    scanColumn(
                            chunk,
                            candidates,
                            random,
                            x,
                            z,
                            center,
                            dxCenter,
                            dzCenter,
                            horizontalSqr,
                            radiusSqr,
                            fullRadius,
                            radius,
                            minBuild,
                            yLow,
                            yHigh,
                            excluded,
                            cap);
                }
            }
        }
        return candidates;
    }

    private static void scanColumn(
            LevelChunk chunk,
            List<BlockPos> candidates,
            RandomSource random,
            int x,
            int z,
            Vec3 center,
            double dxCenter,
            double dzCenter,
            double horizontalSqr,
            double radiusSqr,
            double fullRadius,
            double radius,
            int minBuild,
            int yLow,
            int yHigh,
            LongSet excluded,
            int cap) {
        LevelChunkSection[] sections = chunk.getSections();

        int firstSection = (yLow - minBuild) >> 4;
        int lastSection = (yHigh - minBuild) >> 4;
        for (int sectionIndex = firstSection; sectionIndex <= lastSection; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir() || !section.maybeHas(GLASSY)) {
                continue;
            }
            int sectionBottom = minBuild + (sectionIndex << 4);
            int from = Math.max(yLow, sectionBottom);
            int to = Math.min(yHigh, sectionBottom + 15);
            for (int y = from; y <= to; y++) {
                if (!GLASSY.test(section.getBlockState(x & 15, y & 15, z & 15))) {
                    continue;
                }
                BlockPos pos = new BlockPos(x, y, z);
                if (excluded.contains(pos.asLong())) {
                    continue;
                }
                double dy = y + 0.5D - center.y;
                double distSqr = horizontalSqr + dy * dy;
                if (distSqr > radiusSqr) {
                    continue;
                }
                double dist = Math.sqrt(distSqr);
                if (dist > fullRadius && random.nextFloat() > falloff(dist, fullRadius, radius)) {
                    continue;
                }
                candidates.add(pos);
                if (candidates.size() >= cap) {
                    return;
                }
            }
        }
    }

    private static float falloff(double dist, double fullRadius, double radius) {
        double span = radius - fullRadius;
        if (span <= 0.0D) {
            return 1.0f;
        }
        return (float) Math.max(0.0D, 1.0D - (dist - fullRadius) / span);
    }
}
