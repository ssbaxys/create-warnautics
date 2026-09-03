package com.cbc_more_content.effects;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class BlastScorch {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final int SURFACE_BAND = 4;
    private static final int MAX_SCUFFED = 12_000;

    private static final Block[] SOIL_SCARS = {
        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL,
    };

    private static final java.util.Set<Block> SOIL = java.util.Set.of(
            Blocks.GRASS_BLOCK,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.FARMLAND,
            Blocks.DIRT_PATH,
            Blocks.DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.COARSE_DIRT);

    private static final Map<Block, Block> DEGRADE = Map.ofEntries(
            Map.entry(Blocks.STONE, Blocks.COBBLESTONE),
            Map.entry(Blocks.COBBLESTONE, Blocks.GRAVEL),
            Map.entry(Blocks.GRANITE, Blocks.COBBLESTONE),
            Map.entry(Blocks.DIORITE, Blocks.COBBLESTONE),
            Map.entry(Blocks.ANDESITE, Blocks.COBBLESTONE),
            Map.entry(Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE),
            Map.entry(Blocks.TUFF, Blocks.GRAVEL),
            Map.entry(Blocks.CALCITE, Blocks.GRAVEL),
            Map.entry(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS),
            Map.entry(Blocks.CRACKED_STONE_BRICKS, Blocks.COBBLESTONE),
            Map.entry(Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS),
            Map.entry(Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES),
            Map.entry(Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS),
            Map.entry(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS),
            Map.entry(Blocks.SMOOTH_STONE, Blocks.STONE),
            Map.entry(Blocks.POLISHED_GRANITE, Blocks.GRANITE),
            Map.entry(Blocks.POLISHED_DIORITE, Blocks.DIORITE),
            Map.entry(Blocks.POLISHED_ANDESITE, Blocks.ANDESITE),
            Map.entry(Blocks.POLISHED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE),
            Map.entry(Blocks.CUT_SANDSTONE, Blocks.SANDSTONE),
            Map.entry(Blocks.CUT_RED_SANDSTONE, Blocks.RED_SANDSTONE),
            Map.entry(Blocks.SANDSTONE, Blocks.SAND),
            Map.entry(Blocks.RED_SANDSTONE, Blocks.RED_SAND),
            Map.entry(Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW),
            Map.entry(Blocks.CLAY, Blocks.MUD),
            Map.entry(Blocks.PACKED_MUD, Blocks.MUD));

    private BlastScorch() {}

    public static void scuff(ServerLevel level, Vec3 center, double radius, float strength) {
        scuffAt(level, center, radius, strength);
    }

    public static void scuffDeferred(ServerLevel level, Vec3 center, double radius, float strength, int delayTicks) {
        if (radius <= 0.0D || strength <= 0.0f) {
            return;
        }
        var server = level.getServer();
        if (server == null) {
            scuffAt(level, center, radius, strength);
            return;
        }
        int when = server.getTickCount() + Math.max(1, delayTicks);
        server.tell(new TickTask(when, () -> scuffAt(level, center, radius, strength)));
    }

    private static void scuffAt(ServerLevel level, Vec3 center, double radius, float strength) {
        if (radius <= 0.0D || strength <= 0.0f) {
            return;
        }
        int r = (int) Math.ceil(radius);
        double radiusSqr = radius * radius;
        int originX = (int) Math.floor(center.x);
        int originZ = (int) Math.floor(center.z);
        int surfaceY = (int) Math.floor(center.y);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int budget = MAX_SCUFFED;

        for (int dz = -r; dz <= r && budget > 0; dz++) {
            double dzSqr = (double) dz * dz;
            if (dzSqr >= radiusSqr) {
                continue;
            }
            int rx = (int) Math.floor(Math.sqrt(radiusSqr - dzSqr));
            for (int dx = -rx; dx <= rx && budget > 0; dx++) {
                double distSqr = dzSqr + (double) dx * dx;
                if (distSqr > radiusSqr) {
                    continue;
                }
                cursor.set(originX + dx, 0, originZ + dz);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, cursor.getX(), cursor.getZ());
                if (top <= level.getMinBuildHeight()) {
                    continue;
                }
                int fromY = Math.max(level.getMinBuildHeight(), top - SURFACE_BAND);
                int toY = Math.min(level.getMaxBuildHeight() - 1, top + SURFACE_BAND);
                for (int y = toY; y >= fromY; y--) {
                    cursor.set(originX + dx, y, originZ + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    float chance = (float) (1.0D - Math.sqrt(distSqr) / radius) * strength;
                    if (chance <= 0.0f || level.random.nextFloat() > chance) {
                        continue;
                    }
                    if (scuffOne(level, cursor, state)) {
                        budget--;
                        if (budget <= 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private static boolean scuffOne(ServerLevel level, BlockPos.MutableBlockPos pos, BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }

        if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.LEAVES)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            return true;
        }

        Block block = state.getBlock();
        if (SOIL.contains(block)) {
            Block scarred = SOIL_SCARS[level.random.nextInt(SOIL_SCARS.length)];
            if (scarred == block) {
                return false;
            }
            level.setBlock(pos, scarred.defaultBlockState(), FLAGS);
            return true;
        }

        Block degraded = DEGRADE.get(block);
        if (degraded == null) {
            return false;
        }
        level.setBlock(pos, degraded.defaultBlockState(), FLAGS);
        return true;
    }
}
