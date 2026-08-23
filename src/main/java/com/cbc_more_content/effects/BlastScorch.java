package com.cbc_more_content.effects;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Surface scuffing around a blast that is too small to dig a crater.
 * <p>
 * A mine throws its casing outward rather than carving a hole, so the ground it goes off
 * on should look worked over rather than untouched: turf stripped back to bare earth,
 * dressed stone cracked, loose stone knocked down a grade. Nothing here removes a block
 * a player placed for structure — the mapping only ever degrades a block into a softer
 * form of itself.
 */
public final class BlastScorch {
    /** Written without neighbour updates: a scuffed field must not cost a tick of them. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** What each block is knocked down to. Anything absent is left alone. */
    private static final Map<Block, Block> DEGRADE = Map.ofEntries(
            Map.entry(Blocks.GRASS_BLOCK, Blocks.DIRT),
            Map.entry(Blocks.PODZOL, Blocks.DIRT),
            Map.entry(Blocks.MYCELIUM, Blocks.DIRT),
            Map.entry(Blocks.FARMLAND, Blocks.DIRT),
            Map.entry(Blocks.DIRT_PATH, Blocks.DIRT),
            Map.entry(Blocks.DIRT, Blocks.COARSE_DIRT),
            Map.entry(Blocks.ROOTED_DIRT, Blocks.COARSE_DIRT),

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

    private BlastScorch() {
    }

    /**
     * Scuffs the surface inside {@code radius}. Chance falls off with distance, so the
     * ground reads as worst at the seat of the blast and merely disturbed at the edge.
     *
     * @param strength scale on that chance; 1 leaves the rim mostly intact
     */
    public static void scuff(ServerLevel level, Vec3 center, double radius, float strength) {
        if (radius <= 0.0D || strength <= 0.0f) {
            return;
        }
        int r = (int) Math.ceil(radius);
        double radiusSqr = radius * radius;
        BlockPos origin = BlockPos.containing(center);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    double distSqr = dx * dx + dy * dy + dz * dz;
                    if (distSqr > radiusSqr) {
                        continue;
                    }
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    float chance = (float) (1.0D - Math.sqrt(distSqr) / radius) * strength;
                    if (chance <= 0.0f || level.random.nextFloat() > chance) {
                        continue;
                    }
                    scuffOne(level, cursor);
                }
            }
        }
    }

    private static void scuffOne(ServerLevel level, BlockPos.MutableBlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        // Ground cover is simply blown off; it has nothing left to degrade into.
        if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.LEAVES)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            return;
        }

        Block degraded = DEGRADE.get(state.getBlock());
        if (degraded != null) {
            level.setBlock(pos, degraded.defaultBlockState(), FLAGS);
        }
    }
}
