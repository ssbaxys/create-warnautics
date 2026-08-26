package com.cbc_more_content.effects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Fracture cost model shared by the Warnautics block-damage passes.
 * <p>
 * Vanilla charges explosion resistance linearly, which puts obsidian (1200) two hundred
 * times above stone (6) — a scale built so 4-power TNT can never touch it. Charging a
 * sub-linear curve instead leaves ordinary terrain unchanged while letting payload size
 * matter: obsidian comes within reach of the heaviest charges at close range only.
 * <p>
 * Run as a second pass over the crater the ordinary explosion has already cut, adding
 * only what that one refused. Soft ground is gone before this looks at it, so nothing
 * here changes how a field or a house comes apart; what it changes is that a warhead
 * built to break a bunker can now get partway into one instead of scuffing the paint.
 */
public final class BlastFracture {
    private static final double RESISTANCE_EXPONENT = 0.58D;
    /** Ray grid per axis. The rays are the surface of this cube, as vanilla does it. */
    private static final int RAYS_PER_AXIS = 13;
    /** How far a ray advances per step, in blocks. */
    private static final double STEP = 0.5D;
    /** A ceiling on what one blast may add, so a heavy warhead cannot stall a tick. */
    private static final int MAX_ADDED = 3000;
    /** Below this a charge has nothing to spare for hard material anyway. */
    private static final double MIN_POWER = 5.0D;

    private BlastFracture() {
    }

    /** Energy a block absorbs before it breaks. */
    public static double cost(double explosionResistance) {
        return (Math.pow(Math.max(0.0D, explosionResistance), RESISTANCE_EXPONENT) + 0.3D) * 0.30D;
    }

    /** Energy left at {@code distance} from a blast of {@code power}. */
    public static double available(double power, double distance, double radius) {
        return radius <= 0.0D ? 0.0D : power * 1.30D * (1.0D - distance / radius);
    }

    /**
     * Positions the ordinary blast left standing that this payload is heavy enough to
     * break anyway.
     * <p>
     * Walked as rays rather than swept as a sphere, so the same thing that protects a
     * block in every other blast model protects it here: a slab of obsidian shields what
     * is behind it, because the ray pays for the obsidian and runs out.
     */
    public static List<BlockPos> gather(
            ServerLevel level, Vec3 center, double power, Collection<BlockPos> already) {
        List<BlockPos> added = new ArrayList<>();
        if (power < MIN_POWER) {
            return added;
        }
        double radius = power;
        Set<BlockPos> seen = new HashSet<>(already);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Spent per step purely on distance, so a ray dies at the radius even in open air.
        double drain = available(power, 0.0D, radius) * STEP / radius;

        for (int gx = 0; gx < RAYS_PER_AXIS; gx++) {
            for (int gy = 0; gy < RAYS_PER_AXIS; gy++) {
                for (int gz = 0; gz < RAYS_PER_AXIS; gz++) {
                    if (gx != 0 && gx != RAYS_PER_AXIS - 1
                            && gy != 0 && gy != RAYS_PER_AXIS - 1
                            && gz != 0 && gz != RAYS_PER_AXIS - 1) {
                        // Interior of the grid; only its surface gives ray directions.
                        continue;
                    }
                    if (added.size() >= MAX_ADDED) {
                        return added;
                    }
                    double dx = gx / (RAYS_PER_AXIS - 1.0D) * 2.0D - 1.0D;
                    double dy = gy / (RAYS_PER_AXIS - 1.0D) * 2.0D - 1.0D;
                    double dz = gz / (RAYS_PER_AXIS - 1.0D) * 2.0D - 1.0D;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (length < 1.0E-6D) {
                        continue;
                    }
                    walk(level, center, dx / length, dy / length, dz / length,
                            available(power, 0.0D, radius), drain, radius, seen, added, cursor);
                }
            }
        }
        return added;
    }

    private static void walk(
            ServerLevel level, Vec3 center, double dx, double dy, double dz,
            double energy, double drain, double radius,
            Set<BlockPos> seen, List<BlockPos> added, BlockPos.MutableBlockPos cursor) {
        double x = center.x;
        double y = center.y;
        double z = center.z;
        for (double travelled = 0.0D; travelled < radius && energy > 0.0D; travelled += STEP) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir()) {
                double resistance;
                try {
                    resistance = state.getExplosionResistance(level, cursor, null);
                } catch (Throwable ignored) {
                    resistance = state.getBlock().getExplosionResistance();
                }
                // Nothing gets through bedrock, whatever the payload.
                if (resistance >= 3600.0D || state.getDestroySpeed(level, cursor) < 0.0f) {
                    return;
                }
                double toll = cost(resistance);
                if (toll > energy) {
                    return;
                }
                energy -= toll;
                BlockPos at = cursor.immutable();
                if (seen.add(at)) {
                    added.add(at);
                    if (added.size() >= MAX_ADDED) {
                        return;
                    }
                }
            }
            energy -= drain;
            x += dx * STEP;
            y += dy * STEP;
            z += dz * STEP;
        }
    }
}
