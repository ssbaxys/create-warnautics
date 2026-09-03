package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.config.WarnauticsConfig;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

public final class BlastGlassShatter {
    private static final TagKey<Block> SHATTERABLE_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "shatterable_glass"));

    private static final Predicate<BlockState> SHATTERABLE =
            state -> !state.is(Blocks.BARRIER) && state.is(SHATTERABLE_TAG);

    private static final int MAX_SHATTERED = 2048;
    private static final double FULL_SHATTER_RADIUS_FRACTION = 2.0D;

    private static final long SCAN_BUDGET_PER_TICK = 2_000_000L;

    private static final AtomicLong SCAN_BUDGET = new AtomicLong();
    private static long scanBudgetTick = Long.MIN_VALUE;

    private BlastGlassShatter() {}

    public static void scheduleFor(
            ServerLevel level,
            Vec3 center,
            float craterRadius,
            float blockPower,
            BombBurstBudget.Lod lod,
            LongSet craterBlocks) {
        double radius = Math.max(5.0D, craterRadius * WarnauticsConfig.glassShatterRadiusMultiplier());
        var server = level.getServer();
        if (server == null) {
            shatter(level, center, radius, craterRadius, blockPower, lod, craterBlocks);
            return;
        }
        server.tell(new TickTask(
                server.getTickCount() + 1,
                () -> shatter(level, center, radius, craterRadius, blockPower, lod, craterBlocks)));
    }

    static void shatter(
            ServerLevel level,
            Vec3 center,
            double radius,
            double craterRadius,
            float blockPower,
            BombBurstBudget.Lod lod,
            LongSet excluded) {
        if (lod == BombBurstBudget.Lod.ESSENTIAL) {
            return;
        }
        int cap =
                switch (lod) {
                    case FULL, REDUCED -> MAX_SHATTERED;
                    case MINIMAL -> MAX_SHATTERED / 2;
                    case ESSENTIAL -> MAX_SHATTERED / 4;
                };

        double fullRadius = Math.min(radius, craterRadius * FULL_SHATTER_RADIUS_FRACTION);

        List<BlockPos> candidates = gather(level, center, radius, fullRadius, excluded, cap);
        if (candidates.isEmpty()) {
            return;
        }
        for (BlockPos pos : BlastProtection.filter(level, center, blockPower, candidates)) {
            level.destroyBlock(pos, false);
        }
    }

    private static List<BlockPos> gather(
            ServerLevel level, Vec3 center, double radius, double fullRadius, LongSet excluded, int cap) {
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

        int firstSection = (minY - minBuild) >> 4;
        int lastSection = (maxY - minBuild) >> 4;

        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        LevelChunkSection[] sections = null;
        int chunkMinX = 0;
        int chunkMinZ = 0;

        long tick = scanTick(level);

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
                    if (!consumeScanBudget(tick)) {
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
                        sections = null;
                        if (chunk != null) {
                            sections = chunk.getSections();
                            chunkMinX = chunk.getPos().getMinBlockX();
                            chunkMinZ = chunk.getPos().getMinBlockZ();
                        }
                    }
                    if (sections == null) {
                        continue;
                    }

                    double dyMax = Math.sqrt(radiusSqr - horizontalSqr);
                    int yLow = Math.max(minY, (int) Math.floor(center.y - dyMax));
                    int yHigh = Math.min(maxY, (int) Math.ceil(center.y + dyMax));
                    scanColumn(
                            sections,
                            chunkMinX,
                            chunkMinZ,
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
                            firstSection,
                            lastSection,
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
            LevelChunkSection[] sections,
            int chunkMinX,
            int chunkMinZ,
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
            int firstSection,
            int lastSection,
            int yLow,
            int yHigh,
            LongSet excluded,
            int cap) {
        int lx = x - chunkMinX;
        int lz = z - chunkMinZ;
        int first = Math.max(firstSection, (yLow - minBuild) >> 4);
        int last = Math.min(lastSection, (yHigh - minBuild) >> 4);
        for (int sectionIndex = first; sectionIndex <= last; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir() || !section.maybeHas(SHATTERABLE)) {
                continue;
            }
            int sectionBottom = minBuild + (sectionIndex << 4);
            int from = Math.max(yLow, sectionBottom);
            int to = Math.min(yHigh, sectionBottom + 15);
            for (int y = from; y <= to; y++) {
                if (!SHATTERABLE.test(section.getBlockState(lx, y & 15, lz))) {
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

    private static boolean consumeScanBudget(long tick) {
        if (scanBudgetTick != tick) {
            scanBudgetTick = tick;
            SCAN_BUDGET.set(SCAN_BUDGET_PER_TICK);
        }
        return SCAN_BUDGET.addAndGet(-1L) >= 0L;
    }

    private static long scanTick(ServerLevel level) {
        var server = level.getServer();
        return server == null ? 0L : server.getTickCount();
    }
}
