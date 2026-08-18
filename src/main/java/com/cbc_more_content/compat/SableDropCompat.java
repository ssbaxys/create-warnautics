package com.cbc_more_content.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.effects.BlastFracture;
import com.cbc_more_content.effects.BlastProtection;
import com.cbc_more_content.effects.BlastRubble;
import com.cbc_more_content.effects.BombSympatheticDetonation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import org.joml.Vector3d;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import rbasamoyai.createbigcannons.CBCCompatTransformers;

/**
 * Soft Sable bridge — only referenced when {@code sable} is loaded.
 * <p>
 * Per Sable wiki, entities spawned inside a plot are kicked to global space. We prefer
 * {@link #resolveLaunch} so bombs are created already in parent-world space (pose + ship
 * inertia), avoiding mid-tick kick races with the physics thread.
 * <p>
 * Mass {@code setBlock(AIR)} / finalize against plot blocks races heat-map
 * {@code split}/{@code assembleBlocks} and freezes the server tick (levers stop, bombs vanish).
 */
public final class SableDropCompat {
    private static final int BREAK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int DEFAULT_MAX_SUB_LEVEL_BREAKS = 140;
    /**
     * Safe explosions are used whenever a Sable body is close to the blast. A hard
     * spherical cutoff here made the same bomb suddenly carve a perfect ball merely
     * because a physical object was nearby. This value removes only the outer part
     * of selected angular lobes; it never extends the configured blast radius.
     */
    private static final double SAFE_BLAST_SHAPE_IRREGULARITY = 0.26D;

    private SableDropCompat() {
    }

    /** True if this block position sits inside a Sable sub-level plot. */
    public static boolean isInsideSubLevel(Level level, BlockPos pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isInsideSubLevel(Level level, Vec3 pos) {
        try {
            return Sable.HELPER.getContaining(level, pos) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Sable stores sub-level blocks in a far-away plot grid. A position in that grid
     * must not be passed to vanilla/CBC explosion code as a global coordinate.
     */
    public static boolean isInPlotGrid(Level level, Vec3 pos) {
        try {
            return Sable.HELPER.isInPlotGrid(
                    level,
                    net.minecraft.util.Mth.floor(pos.x) >> 4,
                    net.minecraft.util.Mth.floor(pos.z) >> 4);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Map a local (plot) launch into parent-world space with ship inertia.
     * Sable wiki: prefer not fighting auto-kick — spawn already transformed when possible.
     */
    public static LaunchFrame resolveLaunch(ServerLevel level, Vec3 localPos, Vec3 localVel) {
        return resolveLaunch(level, localPos, localVel, null);
    }

    public static LaunchFrame resolveLaunch(
            ServerLevel level,
            Vec3 localPos,
            Vec3 localVel,
            Vec3 localOrientation) {
        try {
            SubLevel containing = Sable.HELPER.getContaining(level, localPos);
            if (containing instanceof ServerSubLevel serverSub) {
                ServerLevel parent = serverSub.getLevel();
                if (parent == null) {
                    parent = level;
                }
                Vec3 worldPos = serverSub.logicalPose().transformPosition(localPos);
                Vec3 worldVel = serverSub.logicalPose().transformNormal(localVel);
                Vec3 worldOrientation = localOrientation == null
                        ? null
                        : serverSub.logicalPose().transformNormal(localOrientation).normalize();
                Vector3d shipVel = new Vector3d();
                Sable.HELPER.getVelocity(level, JOMLConversion.toJOML(localPos), shipVel);
                shipVel.mul(1.0D / 20.0D);
                worldVel = worldVel.add(shipVel.x, shipVel.y, shipVel.z);
                return new LaunchFrame(parent, worldPos, worldVel, worldOrientation);
            }
        } catch (Throwable ignored) {
        }
        return new LaunchFrame(level, localPos, localVel, localOrientation);
    }

    /**
     * If the entity is inside a sub-level plot, kick to global space.
     * Prefer {@link #resolveLaunch} for new spawns.
     */
    public static boolean kickIfInSubLevel(Entity entity) {
        SubLevel subLevel = Sable.HELPER.getContaining(entity);
        if (subLevel == null) {
            return false;
        }
        EntitySubLevelUtil.kickEntity(subLevel, entity);
        return true;
    }

    /**
     * World-space velocity of the physics object at a local/world position (m/tick after /20).
     */
    public static Vec3 samplePhysicsVelocity(Entity entity) {
        Vector3d vel = new Vector3d();
        Sable.HELPER.getVelocity(entity.level(), JOMLConversion.toJOML(entity.position()), vel);
        return new Vec3(vel.x / 20.0D, vel.y / 20.0D, vel.z / 20.0D);
    }

    /**
     * Resolve cook-off / in-place detonation into parent-world space (CBC shell pattern).
     */
    public static BlastTarget resolveWorldBlast(ServerLevel level, Vec3 localCenter) {
        try {
            SubLevel containing = Sable.HELPER.getContaining(level, localCenter);
            if (containing instanceof ServerSubLevel serverSub) {
                ServerLevel parent = serverSub.getLevel();
                if (parent != null) {
                    // Do this directly instead of relying on CBC's transformer list.
                    // Projectile impact callbacks retain Sable's plot-space hit result,
                    // and setup-order or another transformer must never leave the blast
                    // thousands of blocks away in the storage grid.
                    Vec3 world = serverSub.logicalPose().transformPosition(localCenter);
                    return new BlastTarget(parent, world);
                }
            }
        } catch (Throwable ignored) {
        }
        return new BlastTarget(level, CBCCompatTransformers.transformVec3(level, localCenter));
    }

    /**
     * Skip mass vaporize whenever any ship is in blast range — not only with Sable Destructive.
     * Vaporize + heat-map assembly freezes the integrated server (ticks stop, levers die).
     */
    public static boolean shouldSkipVaporize(ServerLevel level, Vec3 center, float blastRadius) {
        double reach = Math.max(blastRadius * 2.0D, 8.0D);
        return overlapsAnySubLevel(level, center, reach);
    }

    /** True if {@code center} lies inside any loaded sub-level world AABB (bomb hit the ship). */
    public static boolean isCenterInsideAnySubLevel(ServerLevel level, Vec3 center) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return false;
            }
            Vector3d p = new Vector3d(center.x, center.y, center.z);
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                BoundingBox3dc aabb = subLevel.boundingBox();
                if (aabb != null && aabb.contains(p)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * True when a blast is near a ship but not on it — the hang case from Sable Destructive
     * scanning plot storage from an external parent-world detonation.
     */
    public static boolean isExternalShipAdjacentBlast(ServerLevel level, Vec3 center, double reach) {
        return overlapsAnySubLevel(level, center, reach) && !isCenterInsideAnySubLevel(level, center)
                && !isInsideSubLevel(level, center);
    }

    /**
     * Sable Destructive 2.1.1 can feed plot-space offsets into chunk generation while
     * handling an ordinary explosion that overlaps a moving sub-level. Both adjacent
     * and direct hits therefore need the bounded explosion path.
     */
    public static boolean requiresSafeExplosion(ServerLevel level, Vec3 center, double reach) {
        if (isInPlotGrid(level, center) || isInsideSubLevel(level, center)) {
            return true;
        }
        return overlapsAnySubLevel(level, center, reach)
                || isCenterInsideAnySubLevel(level, center);
    }

    /**
     * Replacement terrain pass for explosions that cannot safely post the normal
     * {@code ExplosionEvent.Detonate}. It carves the parent world and every intersected
     * Sable sub-level without ever querying outside a plot's local bounds.
     */
    public static SafeExplosionResult applySafeBlockExplosion(ServerLevel level, Vec3 center, float power) {
        return applySafeBlockExplosion(level, center, power, power);
    }

    /**
     * Variant for compact anti-vehicle charges. The parent terrain can stay
     * restrained while a directly contacted moving structure receives enough
     * local energy to damage its hull.
     */
    public static SafeExplosionResult applySafeBlockExplosion(
            ServerLevel level,
            Vec3 center,
            float worldPower,
            float subLevelPower) {
        return applySafeBlockExplosion(level, center, worldPower, subLevelPower, 1.0f);
    }

    /**
     * @param subLevelRadiusScale changes only the Sable hull reach; fracture power is
     *                            deliberately left unchanged.
     */
    public static SafeExplosionResult applySafeBlockExplosion(
            ServerLevel level,
            Vec3 center,
            float worldPower,
            float subLevelPower,
            float subLevelRadiusScale) {
        if (level == null || center == null || !Double.isFinite(center.x)
                || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                || !Float.isFinite(worldPower) || worldPower <= 0.0f
                || !Float.isFinite(subLevelPower) || subLevelPower <= 0.0f
                || !Float.isFinite(subLevelRadiusScale) || subLevelRadiusScale <= 0.0f) {
            return SafeExplosionResult.NONE;
        }

        // Every pass below works in parent-world space. Previously only the world
        // crater was projected while the hull scan still received the far-away plot
        // coordinate, so a direct bomb hit could damage neither the Sable body nor
        // entities standing at the visible impact point.
        BlastTarget target = resolveWorldBlast(level, center);
        ServerLevel worldLevel = target.level();
        Vec3 worldCenter = target.pos();
        // One coherent field is shared by the terrain and intersected hulls. Using a
        // fresh random decision per block produced either visual noise or, by chance,
        // another almost perfect sphere; a directional field guarantees broad torn
        // lobes on every safe-path detonation.
        long shapeSeed = worldLevel.random.nextLong();
        int worldBlocks = destroyWorldBlocks(
                worldLevel, worldCenter, worldPower, shapeSeed, SAFE_BLAST_SHAPE_IRREGULARITY);
        int subLevelBlocks = destroySubLevelBlocks(
                worldLevel, worldCenter, subLevelPower, subLevelRadiusScale,
                shapeSeed, SAFE_BLAST_SHAPE_IRREGULARITY);
        return new SafeExplosionResult(worldBlocks, subLevelBlocks);
    }

    /**
     * Approximation of the CBC/vanilla crater for the parent world. Candidates are
     * collected before mutation and nearest/highest-energy blocks are processed first,
     * keeping the result stable and bounded during carpet bombing.
     */
    private static int destroyWorldBlocks(
            ServerLevel level,
            Vec3 center,
            float power,
            long shapeSeed,
            double shapeIrregularity) {
        double radius = Math.max(1.5D, power);
        int minX = (int) Math.floor(center.x - radius);
        int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(center.y - radius));
        int minZ = (int) Math.floor(center.z - radius);
        int maxX = (int) Math.ceil(center.x + radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(center.y + radius));
        int maxZ = (int) Math.ceil(center.z + radius);
        // A large bomb sweeps a sphere of roughly sixteen thousand positions. The old
        // flat ceiling of 1024 kept only the innermost tenth of that, which collapsed a
        // 15-block crater into a 6-block scuff — the "barely scratches" report. The
        // server-wide detonation budget is the real limit, so defer to it.
        int cap = Math.min(
                WarnauticsConfig.maxBlocksPerDetonation(),
                Math.max(96, Math.round(power * power * 18.0f)));

        List<WorldBreakTarget> targets = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    double dx = x + 0.5D - center.x;
                    double dy = y + 0.5D - center.y;
                    double dz = z + 0.5D - center.z;
                    double distSqr = dx * dx + dy * dy + dz * dz;
                    double localRadius = irregularBlastReach(
                            radius, dx, dy, dz, shapeSeed, shapeIrregularity);
                    if (distSqr > localRadius * localRadius) {
                        continue;
                    }

                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state;
                    try {
                        state = level.getBlockState(cursor);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (state == null || state.isAir() || isUnbreakable(state, level, cursor)) {
                        continue;
                    }
                    // Water sits at resistance 100 and would otherwise pass the fracture
                    // test for heavier charges, burning the break budget on positions the
                    // sea closes again immediately.
                    if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                        continue;
                    }

                    double distance = Math.sqrt(distSqr);
                    double available = BlastFracture.available(power, distance, localRadius);
                    double resistance = Math.max(0.0D, state.getExplosionResistance(level, cursor, null));
                    double score = available - BlastFracture.cost(resistance);
                    if (score > 0.0D) {
                        targets.add(new WorldBreakTarget(cursor.immutable(), score));
                    }
                }
            }
        }

        targets.sort(Comparator.comparingDouble(WorldBreakTarget::score).reversed());
        if (targets.size() > cap) {
            targets = new ArrayList<>(targets.subList(0, cap));
        }

        // This path replaces the vanilla explosion entirely, so nothing else posts
        // ExplosionEvent.Detonate for it. Claim protection (FTB Chunks, GriefPrevention,
        // …) filters that event and never saw these blocks — which is how bombs came to
        // dig through claimed land. Ask the event first and only break what survives.
        java.util.Set<BlockPos> allowed = filterProtectedBlocks(level, center, power, targets);

        int broken = 0;
        for (WorldBreakTarget target : targets) {
            if (broken >= cap) {
                break;
            }
            if (!allowed.contains(target.pos())) {
                continue;
            }
            BlockState current = level.getBlockState(target.pos());
            if (current.getBlock() instanceof DropBombBlock bomb) {
                if (!BombSympatheticDetonation.allowsCookoffFrom(null)) {
                    continue;
                }
                BombSympatheticDetonation.scheduleDetachedBombCookoff(
                        level, target.pos(), bomb.getBombSize(), 28, 92);
                broken++;
                continue;
            }
            if (DestructiveApi.destroyWorld(level, target.pos())
                    || destroyWorldFallback(level, target.pos())) {
                broken++;
            }
        }
        return broken;
    }

    /**
     * Sable Destructive's 2.1.1 handler scans an unclamped local sphere. The clamp
     * below is the important crash fix: EmbeddedPlotLevelAccessor never receives a
     * coordinate outside the sub-level's actual local block bounds.
     */
    private static int destroySubLevelBlocks(
            ServerLevel level,
            Vec3 center,
            float power,
            float radiusScale,
            long shapeSeed,
            double shapeIrregularity) {
        ServerSubLevelContainer container;
        try {
            container = SubLevelContainer.getContainer(level);
        } catch (Throwable ignored) {
            return 0;
        }
        if (container == null) {
            return 0;
        }

        double reach = Math.max(1.5D, power * 2.0D * radiusScale);
        double reachSqr = reach * reach;
        Vector3d globalCenter = new Vector3d(center.x, center.y, center.z);
        int broken = 0;

        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()
                    || !aabbWithinReach(subLevel.boundingBox(), globalCenter, reachSqr)) {
                continue;
            }

            ServerLevelPlot plot = subLevel.getPlot();
            if (plot == null) {
                continue;
            }
            BoundingBox3ic bounds = plot.getBoundingBox();
            if (bounds == null) {
                continue;
            }

            Vector3d localCenter;
            try {
                localCenter = subLevel.logicalPose().transformPositionInverse(globalCenter, new Vector3d());
            } catch (Throwable ignored) {
                continue;
            }

            int minX = Math.max(bounds.minX(), (int) Math.floor(localCenter.x - reach));
            int minY = Math.max(bounds.minY(), (int) Math.floor(localCenter.y - reach));
            int minZ = Math.max(bounds.minZ(), (int) Math.floor(localCenter.z - reach));
            int maxX = Math.min(bounds.maxX(), (int) Math.ceil(localCenter.x + reach));
            int maxY = Math.min(bounds.maxY(), (int) Math.ceil(localCenter.y + reach));
            int maxZ = Math.min(bounds.maxZ(), (int) Math.ceil(localCenter.z + reach));
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                continue;
            }

            EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();
            BlockPos plotCenter = plot.getCenterBlock();
            boolean internal = false;
            try {
                BoundingBox3dc worldBounds = subLevel.boundingBox();
                internal = worldBounds != null && worldBounds.contains(globalCenter);
            } catch (Throwable ignored) {
            }
            double absorption = internal ? DestructiveApi.internalAbsorptionFactor() : 1.0D;
            // Sable Destructive's per-explosion break budget is tuned for cannon shells
            // and does not scale with charge size, so a large bomb and a small one both
            // removed the same handful of hull blocks. Scale it with payload instead,
            // using the configured value as the floor rather than the ceiling.
            int budget = Math.max(1, Math.max(
                    DestructiveApi.maxExplosionBreaks(),
                    Math.round(power * power * 6.0f)));
            List<SubLevelBreakTarget> targets = new ArrayList<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        double dx = x + 0.5D - localCenter.x;
                        double dy = y + 0.5D - localCenter.y;
                        double dz = z + 0.5D - localCenter.z;
                        double distSqr = dx * dx + dy * dy + dz * dz;
                        double localReach = irregularBlastReach(
                                reach, dx, dy, dz, shapeSeed, shapeIrregularity);
                        if (distSqr > localReach * localReach) {
                            continue;
                        }

                        cursor.set(x, y, z);
                        BlockPos storagePos = cursor.immutable();
                        BlockPos embeddedPos = storagePos.subtract(plotCenter);
                        BlockState state;
                        try {
                            state = accessor.getBlockState(embeddedPos);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (state == null || state.isAir() || isUnbreakable(state, accessor, embeddedPos)) {
                            continue;
                        }

                        // Material resistance is checked only for the nearest bounded
                        // candidates below. Reflective API calls for every block in a
                        // large ship were the main source of delayed detonations.
                        targets.add(new SubLevelBreakTarget(
                                storagePos, embeddedPos, state, distSqr, localReach));
                    }
                }
            }

            // Sorting by plain distance recreated a perfect inner sphere whenever
            // the per-blast break budget was reached before the candidate list ended.
            // Measure depth relative to each direction's ragged reach instead: the
            // budget now scales the torn profile inward instead of cutting it off
            // with another spherical shell.
            targets.sort(Comparator.comparingDouble(SubLevelBreakTarget::relativeDepthSqr));
            int processed = 0;
            for (SubLevelBreakTarget target : targets) {
                if (processed >= budget) {
                    break;
                }
                if (!canExplosionBreak(
                        level,
                        accessor,
                        target.embeddedPos(),
                        target.state(),
                        power,
                        target.distSqr(),
                        target.reach(),
                        absorption)) {
                    continue;
                }
                if (target.state().getBlock() instanceof DropBombBlock bomb) {
                    if (BombSympatheticDetonation.isBombBlastActive()) {
                        continue;
                    }
                    processed++;
                    Vector3d worldTarget = subLevel.logicalPose().transformPosition(
                            new Vector3d(
                                    target.pos().getX() + 0.5D,
                                    target.pos().getY() + 0.5D,
                                    target.pos().getZ() + 0.5D),
                            new Vector3d());
                    try {
                        accessor.setBlock(target.embeddedPos(), Blocks.AIR.defaultBlockState(), BREAK_FLAGS);
                    } catch (Throwable ignored) {
                    }
                    BombSympatheticDetonation.scheduleDetachedBombCookoff(
                            level,
                            BlockPos.containing(worldTarget.x, worldTarget.y, worldTarget.z),
                            bomb.getBombSize(),
                            28,
                            92);
                    broken++;
                    continue;
                }
                processed++;
                // Remove through the accessor captured for this scan. Calling the
                // compatibility API for every target repeats coordinate conversion
                // and, on older Sable Destructive builds, leaves all but the first
                // block addressed outside the embedded plot.
                if (destroyPlotFallback(level, subLevel, accessor, target.storagePos())) {
                    broken++;
                }
            }
        }
        return broken;
    }

    /**
     * Runs the candidate list past {@link ExplosionEvent.Detonate} so protection mods
     * can veto blocks exactly as they would for a vanilla or CBC explosion, and returns
     * the positions that are still allowed to break.
     */
    private static java.util.Set<BlockPos> filterProtectedBlocks(
            ServerLevel level,
            Vec3 center,
            float power,
            List<WorldBreakTarget> targets) {
        List<BlockPos> candidates = new ArrayList<>(targets.size());
        for (WorldBreakTarget target : targets) {
            candidates.add(target.pos());
        }
        return BlastProtection.filter(level, center, power, candidates);
    }

    private static boolean aabbWithinReach(BoundingBox3dc aabb, Vector3d point, double reachSqr) {
        if (aabb == null) {
            return false;
        }
        double dx = Math.max(aabb.minX() - point.x, Math.max(0.0D, point.x - aabb.maxX()));
        double dy = Math.max(aabb.minY() - point.y, Math.max(0.0D, point.y - aabb.maxY()));
        double dz = Math.max(aabb.minZ() - point.z, Math.max(0.0D, point.z - aabb.maxZ()));
        return dx * dx + dy * dy + dz * dz <= reachSqr;
    }

    /**
     * Produces a smooth but torn angular boundary. The multiplier is always in
     * {@code [1 - irregularity, 1]}, so previous radius balance remains the hard
     * maximum and the scan can still use the original radius as its outer bound.
     */
    private static double irregularBlastReach(
            double reach,
            double dx,
            double dy,
            double dz,
            long seed,
            double irregularity) {
        if (reach <= 0.0D || irregularity <= 0.0D) {
            return reach;
        }

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-6D) {
            return reach;
        }

        double nx = dx / distance;
        double ny = dy / distance;
        double nz = dz / distance;
        double phase = (seed & 0xffffL) * (Math.PI * 2.0D / 65536.0D);
        double wave = Math.sin(nx * 3.1D + ny * 4.7D + nz * 2.3D + phase)
                + 0.58D * Math.sin(nx * 6.3D - ny * 3.9D + nz * 5.1D + phase * 1.71D)
                + 0.31D * Math.sin(nx * 10.7D + ny * 8.1D - nz * 7.3D - phase * 0.83D)
                // Block-sized teeth approximate the short uneven rays of the normal
                // CBC/vanilla explosion without querying outside Sable's plot bounds.
                + 0.20D * Math.sin(nx * 21.7D - ny * 18.3D + nz * 24.1D + phase * 2.37D);
        double normalized = Math.max(0.0D, Math.min(1.0D, (wave / 2.09D + 1.0D) * 0.5D));
        return reach * (1.0D - Math.min(0.75D, irregularity) * normalized);
    }

    private static boolean isUnbreakable(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        try {
            return state.getDestroySpeed(level, pos) < 0.0f;
        } catch (Throwable ignored) {
            return state.is(Blocks.BEDROCK);
        }
    }

    private static boolean destroyWorldFallback(ServerLevel level, BlockPos pos) {
        BlockState state;
        try {
            state = level.getBlockState(pos);
        } catch (Throwable ignored) {
            return false;
        }
        if (state == null || state.isAir() || isUnbreakable(state, level, pos)) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        spillContainer(level, Vec3.atCenterOf(pos), blockEntity);
        try {
            level.removeBlockEntity(pos);
            boolean removed = level.setBlock(pos,
                    BlastRubble.replacementFor(level, pos, state), BREAK_FLAGS);
            if (removed) {
                destroySupportedWorldSnow(level, pos);
            }
            return removed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean destroyPlotFallback(
            ServerLevel level,
            ServerSubLevel subLevel,
            EmbeddedPlotLevelAccessor accessor,
            BlockPos pos) {
        ServerLevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return false;
        }
        BlockPos embeddedPos = pos.subtract(plot.getCenterBlock());
        BlockState state;
        try {
            state = accessor.getBlockState(embeddedPos);
        } catch (Throwable ignored) {
            return false;
        }
        if (state == null || state.isAir() || isUnbreakable(state, accessor, embeddedPos)) {
            return false;
        }

        BlockEntity blockEntity = null;
        try {
            blockEntity = accessor.getBlockEntity(embeddedPos);
        } catch (Throwable ignored) {
        }
        Vector3d world = subLevel.logicalPose().transformPosition(
                new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D),
                new Vector3d());
        spillContainer(level, new Vec3(world.x, world.y, world.z), blockEntity);
        try {
            boolean removed = accessor.setBlock(embeddedPos, Blocks.AIR.defaultBlockState(), BREAK_FLAGS);
            if (removed) {
                destroySupportedPlotSnow(subLevel, accessor, pos);
            }
            return removed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Explosion fracture is pressure/resistance based, not a zero-mass collision.
     * Reusing the impact resolver made the first (nearest) block absorb effectively
     * the whole blast and rejected the rest of the sphere.
     */
    private static boolean canExplosionBreak(
            ServerLevel level,
            EmbeddedPlotLevelAccessor accessor,
            BlockPos embeddedPos,
            BlockState state,
            float power,
            double distSqr,
            double reach,
            double absorption) {
        double distance = Math.sqrt(Math.max(0.0D, distSqr));
        double falloff = Math.max(0.0D, 1.0D - distance / Math.max(0.001D, reach));
        double availablePressure = power * (0.15D + 1.45D * falloff)
                / Math.sqrt(Math.max(0.1D, absorption));

        double resistance;
        double hardness;
        try {
            resistance = Math.max(0.0D, state.getExplosionResistance(accessor, embeddedPos, null));
            hardness = Math.max(0.0D, state.getDestroySpeed(accessor, embeddedPos));
        } catch (Throwable ignored) {
            resistance = Math.max(0.0D, state.getBlock().getExplosionResistance());
            hardness = Math.max(0.0D, state.getBlock().defaultDestroyTime());
        }

        double fracturePressure = BlastFracture.cost(resistance)
                + Math.sqrt(hardness + 1.0D) * 0.22D;
        if (availablePressure >= fracturePressure) {
            return true;
        }

        // Very hard finite blocks are no longer absolutely immortal. Only the
        // concentrated core of a heavy bomb gets a tiny breach chance; obsidian
        // and reinforced deepslate remain orders of magnitude harder than stone.
        if (power < 12.0f || falloff < 0.68D || hardness < 20.0D) {
            return false;
        }
        double extremeNeed = Math.sqrt(resistance + 1.0D) * 1.50D
                + Math.sqrt(hardness + 1.0D) * 0.22D;
        double overload = availablePressure / Math.max(1.0D, extremeNeed);
        double chance = Math.max(0.0D, Math.min(0.025D, (overload - 0.55D) * 0.025D));
        if (state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            chance *= 0.18D;
        }
        return chance > 0.0D && level.random.nextDouble() < chance;
    }

    private static void destroySupportedWorldSnow(ServerLevel level, BlockPos supportPos) {
        BlockPos coverPos = supportPos.above();
        if (!level.hasChunkAt(coverPos)) {
            return;
        }
        try {
            if (level.getBlockState(coverPos).is(Blocks.SNOW)) {
                level.setBlock(coverPos, Blocks.AIR.defaultBlockState(), BREAK_FLAGS);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void destroySupportedPlotSnow(
            ServerSubLevel subLevel,
            EmbeddedPlotLevelAccessor accessor,
            BlockPos supportPos) {
        if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) {
            return;
        }
        BlockPos coverPos = supportPos.above();
        try {
            BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
            if (bounds == null
                    || coverPos.getX() < bounds.minX() || coverPos.getX() > bounds.maxX()
                    || coverPos.getY() < bounds.minY() || coverPos.getY() > bounds.maxY()
                    || coverPos.getZ() < bounds.minZ() || coverPos.getZ() > bounds.maxZ()) {
                return;
            }
            BlockPos embeddedCover = coverPos.subtract(subLevel.getPlot().getCenterBlock());
            if (accessor.getBlockState(embeddedCover).is(Blocks.SNOW)) {
                accessor.setBlock(embeddedCover, Blocks.AIR.defaultBlockState(), BREAK_FLAGS);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void spillContainer(ServerLevel level, Vec3 at, BlockEntity blockEntity) {
        if (!(blockEntity instanceof Container container)) {
            return;
        }
        try {
            if (blockEntity instanceof RandomizableContainer randomizable) {
                randomizable.unpackLootTable(null);
            }
            Containers.dropContents(level, BlockPos.containing(at), container);
            container.clearContent();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Optional calls are cached reflectively so CBC More Content still starts when
     * Sable Destructive is absent. Its public break helpers preserve material gates,
     * container handling and user configuration.
     */
    private static final class DestructiveApi {
        private static final Method API_DESTROY_WORLD = method(
                "com.destroynautics.sabledestructive.api.SableDestructiveApi",
                "destroyWorldBlock",
                ServerLevel.class,
                BlockPos.class);
        private static final Method DESTROY_WORLD = method(
                "com.destroynautics.sabledestructive.impact.ImpactBlockBreak",
                "destroyNoBlockDrop",
                ServerLevel.class,
                BlockPos.class);
        private static final Class<?> CONFIG = type(
                "com.destroynautics.sabledestructive.impact.ImpactConfig");

        private DestructiveApi() {
        }

        static boolean destroyWorld(ServerLevel level, BlockPos pos) {
            if (API_DESTROY_WORLD != null) {
                try {
                    return (boolean) API_DESTROY_WORLD.invoke(null, level, pos);
                } catch (Throwable ignored) {
                }
            }
            if (DESTROY_WORLD == null) {
                return false;
            }
            try {
                BlockState before = level.getBlockState(pos);
                DESTROY_WORLD.invoke(null, level, pos);
                return !before.isAir() && level.getBlockState(pos).isAir();
            } catch (Throwable ignored) {
                return false;
            }
        }

        static double internalAbsorptionFactor() {
            return Math.max(0.1D, number("internalAbsorptionFactor", 3.0D));
        }

        static int maxExplosionBreaks() {
            return (int) Math.min(2048.0D,
                    Math.max(1.0D, number("maxExplosionBreaks", DEFAULT_MAX_SUB_LEVEL_BREAKS)));
        }

        private static double number(String name, double fallback) {
            if (CONFIG == null) {
                return fallback;
            }
            try {
                Field field = CONFIG.getField(name);
                return ((Number) field.get(null)).doubleValue();
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static Method method(String owner, String name, Class<?>... parameters) {
            Class<?> type = type(owner);
            if (type == null) {
                return null;
            }
            try {
                return type.getMethod(name, parameters);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Class<?> type(String name) {
            try {
                return Class.forName(name, false, SableDropCompat.class.getClassLoader());
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** True if any loaded sub-level AABB is within {@code reach} of {@code center}. */
    public static boolean overlapsAnySubLevel(ServerLevel level, Vec3 center, double reach) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return false;
            }
            double reachSqr = reach * reach;
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                BoundingBox3dc aabb = subLevel.boundingBox();
                if (aabb == null) {
                    continue;
                }
                double dx = Math.max(aabb.minX() - center.x, Math.max(0.0D, center.x - aabb.maxX()));
                double dy = Math.max(aabb.minY() - center.y, Math.max(0.0D, center.y - aabb.maxY()));
                double dz = Math.max(aabb.minZ() - center.z, Math.max(0.0D, center.z - aabb.maxZ()));
                if (dx * dx + dy * dy + dz * dz <= reachSqr) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    public record BlastTarget(ServerLevel level, Vec3 pos) {
    }

    public record LaunchFrame(ServerLevel level, Vec3 pos, Vec3 vel, Vec3 orientation) {
    }

    public record SafeExplosionResult(int worldBlocks, int subLevelBlocks) {
        public static final SafeExplosionResult NONE = new SafeExplosionResult(0, 0);

        public int totalBlocks() {
            return this.worldBlocks + this.subLevelBlocks;
        }
    }

    private record WorldBreakTarget(BlockPos pos, double score) {
    }

    private record SubLevelBreakTarget(
            BlockPos storagePos,
            BlockPos embeddedPos,
            BlockState state,
            double distSqr,
            double reach) {
        double relativeDepthSqr() {
            return this.distSqr / Math.max(1.0E-8D, this.reach * this.reach);
        }

        BlockPos pos() {
            return this.storagePos;
        }
    }
}
