package com.cbc_more_content.effects;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class BlastScorch {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    public static final float SCAR_STRENGTH = 1.15F;

    public static int changeBudget() {
        return Math.min(900, com.cbc_more_content.config.WarnauticsConfig.maxBlocksPerDetonation() / 3);
    }

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
        if (radius <= 0
                || strength <= 0
                || !rbasamoyai.createbigcannons.config.CBCConfigs.server()
                        .munitions
                        .projectilesChangeSurroundings
                        .get()
                || rbasamoyai.createbigcannons.config.CBCConfigs.server()
                                .munitions
                                .damageRestriction
                                .get()
                                .explosiveInteraction()
                        == net.minecraft.world.level.Explosion.BlockInteraction.KEEP) {
            return;
        }
        var changes =
                gather(level, center, radius, strength, java.util.List.of(), changeBudget(), level.random.nextLong());
        for (BlockPos pos : BlastProtection.filter(level, center, (float) radius, changes.keySet())) {
            Change change = changes.get(pos);
            if (change != null) {
                change.apply(level, pos);
            }
        }
    }

    /** Plans the exposed soil/stone left AFTER the crater, without bypassing its protection event. */
    public static Map<BlockPos, Change> gather(
            ServerLevel level,
            Vec3 center,
            double radius,
            float strength,
            java.util.Collection<BlockPos> destroyed,
            int cap,
            long seed) {
        Map<BlockPos, Change> result = new java.util.LinkedHashMap<>();
        if (radius <= 0 || cap <= 0 || strength <= 0) {
            return result;
        }
        var removed = new java.util.HashSet<>(destroyed);
        BlastScene scene = new BlastScene(level, center, radius + 1);
        java.util.List<ScarCandidate> candidates = new java.util.ArrayList<>();
        for (var block : scene.matching(
                center,
                radius,
                state -> SOIL.contains(state.getBlock()) || DEGRADE.containsKey(state.getBlock()),
                removed)) {
            if (removed.contains(block.pos()) || block.state().hasBlockEntity()) {
                continue;
            }
            Vec3 delta = block.worldCenter().subtract(center);
            long hash = seed
                    ^ Math.round(delta.x * 4096) * 0x9E3779B97F4A7C15L
                    ^ Math.round(delta.y * 4096) * 0xC2B2AE3D27D4EB4FL
                    ^ Math.round(delta.z * 4096) * 0x165667B19E3779F9L;
            var random = net.minecraft.util.RandomSource.create(hash);
            double chance = Math.min(1, strength * Math.pow(Math.max(0, 1 - delta.length() / radius), 0.55));
            if (random.nextDouble() > chance) {
                continue;
            }
            Block replacement = SOIL.contains(block.state().getBlock())
                    ? SOIL_SCARS[random.nextInt(SOIL_SCARS.length)]
                    : DEGRADE.get(block.state().getBlock());
            if (replacement != block.state().getBlock()) {
                candidates.add(new ScarCandidate(block, replacement, random.nextDouble()));
            }
        }
        // Sample the whole fading footprint. A nearest-first cap spent every change
        // inside the crater and cut off the surrounding damaged ground at a hard ring.
        candidates.sort(java.util.Comparator.comparingDouble(ScarCandidate::priority)
                .thenComparingDouble(candidate -> candidate.block().worldCenter().x)
                .thenComparingDouble(candidate -> candidate.block().worldCenter().y)
                .thenComparingDouble(candidate -> candidate.block().worldCenter().z));
        java.util.List<BlastScene.Sample> samples = new java.util.ArrayList<>();
        for (var candidate : candidates) {
            var block = candidate.block();
            Vec3 surface = block.body() == null
                    ? block.worldCenter().add(0, 0.55, 0)
                    : block.body()
                            .logicalPose()
                            .transformPosition(block.pos().getCenter().add(0, 0.55, 0));
            scene.sample(surface, samples);
            if (blocked(level, samples, removed, block.pos())) {
                continue;
            }
            boolean exposed = true;
            int steps = Math.max(1, (int) Math.ceil(center.distanceTo(surface) / 0.5));
            for (int i = 1; i < steps; i++) {
                scene.sample(center.lerp(surface, i / (double) steps), samples);
                if (blocked(level, samples, removed, block.pos())) {
                    exposed = false;
                    break;
                }
            }
            if (!exposed) {
                continue;
            }
            result.put(
                    block.pos(),
                    new Change(block.state(), candidate.replacement().defaultBlockState()));
            if (result.size() == cap) {
                break;
            }
        }
        return result;
    }

    private record ScarCandidate(BlastScene.Sample block, Block replacement, double priority) {}

    private static boolean blocked(
            ServerLevel level,
            java.util.List<BlastScene.Sample> samples,
            java.util.Set<BlockPos> removed,
            BlockPos target) {
        for (var block : samples) {
            if (!block.pos().equals(target)
                    && !removed.contains(block.pos())
                    && !block.state().getCollisionShape(level, block.pos()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public record Change(BlockState before, BlockState after) {
        public void apply(ServerLevel level, BlockPos pos) {
            if (level.getBlockState(pos).equals(before)) {
                level.setBlock(pos, after, FLAGS);
            }
        }
    }
}
